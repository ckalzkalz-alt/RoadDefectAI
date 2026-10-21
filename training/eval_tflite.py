#!/usr/bin/env python3
"""Evaluate the *shipped* TFLite model on the validation split.

Training reports mAP for best.pt; this measures the file that actually ships
(app/src/main/assets/road_defect_v1.tflite), so export bugs can't hide behind
good PyTorch numbers. Reads the same YOLO-format ground truth as training.

    python eval_tflite.py
    python eval_tflite.py --conf 0.001
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from predict_tflite import CLASS_NAMES, detect_scale, iou, load_interpreter, nms, preprocess, to_preds


def load_gt(labels_dir: Path, images_dir: Path):
    """stem -> [(class_idx, (x1, y1, x2, y2))], normalised to the image."""
    gt = {}
    for lbl in sorted(labels_dir.glob("*.txt")):
        img = None
        for ext in (".jpg", ".jpeg", ".png"):
            if (images_dir / f"{lbl.stem}{ext}").is_file():
                img = images_dir / f"{lbl.stem}{ext}"
                break
        if img is None:
            continue
        boxes = []
        for line in lbl.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) != 5:
                continue
            c, cx, cy, w, h = int(parts[0]), *map(float, parts[1:])
            boxes.append((c, (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)))
        gt[lbl.stem] = boxes
    return gt


def all_detections(interp, inp, out, images_dir, imgsz, conf, scale=1.0,
                   nms_iou=0.5, max_det=300):
    """stem -> [(score, class, box)] for every image, boxes normalised to 0..1.

    NMS is mandatory before AP: without it every duplicate anchor of an already-matched
    object counts as a false positive and precision collapses (that mistake showed up
    as a bogus mAP50 of 0.265). Default IoU 0.5 matches TfliteRoadDefectDetector;
    Ultralytics' own validator uses 0.7 with max_det=300.
    """
    preds = {}
    for img_path in sorted(images_dir.iterdir()):
        if img_path.suffix.lower() not in (".jpg", ".jpeg", ".png"):
            continue
        x = preprocess(img_path, imgsz)
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        p = to_preds(interp.get_tensor(out["index"]), len(CLASS_NAMES))
        dets = []
        for cx, cy, w, h, *scores in p:
            c = int(np.argmax(scores))
            s = float(scores[c])
            if s >= conf:
                cx, cy, w, h = cx * scale, cy * scale, w * scale, h * scale
                dets.append((s, c, (float(cx - w / 2), float(cy - h / 2),
                                   float(cx + w / 2), float(cy + h / 2))))
        preds[img_path.stem] = nms(dets, nms_iou)[:max_det]
    return preds


def ap_at_iou(preds, gt, class_idx, iou_thr, n_gt, use_101_point=True) -> float:
    """All-points (VOC-style) or 101-point interpolated AP for one class."""
    if n_gt == 0:
        return 0.0
    # Flatten predictions across images, keeping the global score order.
    flat = []
    for stem, dets in preds.items():
        for s, c, box in dets:
            if c == class_idx:
                flat.append((s, stem, box))
    flat.sort(key=lambda t: -t[0])
    if not flat:
        return 0.0

    matched = {stem: set() for stem in gt}  # set of ORIGINAL gt indices already claimed
    tps, fps = [], []
    for s, stem, box in flat:
        best, best_i, best_iou = None, None, iou_thr
        for gi, (c, gbox) in enumerate(gt.get(stem, [])):
            if c != class_idx or gi in matched[stem]:
                continue
            v = iou(box, gbox)
            if v >= best_iou:
                best, best_i, best_iou = 1.0, gi, v
        if best is not None:
            matched[stem].add(best_i)
            tps.append(1.0)
            fps.append(0.0)
        else:
            tps.append(0.0)
            fps.append(1.0)

    tp_c = np.cumsum(tps)
    fp_c = np.cumsum(fps)
    recall = tp_c / n_gt
    precision = tp_c / np.maximum(tp_c + fp_c, 1e-9)

    if not use_101_point:
        # All-points: envelope of precision for monotonically increasing recall.
        mrec = np.concatenate(([0.0], recall, [1.0]))
        mpre = np.concatenate(([0.0], precision, [0.0]))
        for i in range(len(mpre) - 2, -1, -1):
            mpre[i] = max(mpre[i], mpre[i + 1])
        idx = np.where(mrec[1:] != mrec[:-1])[0]
        return float(np.sum((mrec[idx + 1] - mrec[idx]) * mpre[idx + 1]))

    # 101-point interpolation (COCO).
    ap = 0.0
    for t in np.linspace(0, 1, 101):
        p_at_r = precision[recall >= t]
        ap += (p_at_r.max() if p_at_r.size else 0.0)
    return float(ap / 101.0)


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, default=repo / "app" / "src" / "main" / "assets" / "road_defect_v1.tflite")
    ap.add_argument("--dataset", type=Path, default=here / "dataset")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--conf", type=float, default=0.001,
                    help="Low floor; AP integrates over the full PR curve (default 0.001).")
    ap.add_argument("--nms-iou", type=float, default=0.5,
                    help="NMS IoU before AP. 0.5 = what the app does; 0.7 = Ultralytics default.")
    ap.add_argument("--max-det", type=int, default=300,
                    help="Detections kept per image after NMS (Ultralytics uses 300).")
    args = ap.parse_args()

    images_dir = args.dataset / "images" / "val"
    labels_dir = args.dataset / "labels" / "val"
    if not images_dir.is_dir() or not labels_dir.is_dir():
        raise SystemExit(f"dataset not found at {args.dataset} - run convert_rdd_to_yolo.py")

    gt = load_gt(labels_dir, images_dir)
    n_gt = [sum(1 for g in gt.values() for c, _ in g if c == ci) for ci in range(len(CLASS_NAMES))]
    print(f"Evaluating {args.model}")
    print(f"val images: {len(gt)}   ground-truth boxes: {sum(n_gt)} "
          f"({dict(zip(CLASS_NAMES, n_gt))})")

    interp = load_interpreter(args.model)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    scale = detect_scale(interp, inp["shape"], args.imgsz)
    print(f"box scale  : {scale:.6f} "
          f"({'input-pixel export -> divided by input size' if scale != 1.0 else 'already normalised'})")
    print(f"postprocess: NMS IoU {args.nms_iou}, max_det {args.max_det}, conf floor {args.conf}")
    preds = all_detections(interp, inp, out, images_dir, args.imgsz, args.conf, scale,
                           args.nms_iou, args.max_det)

    ious = [round(0.5 + 0.05 * i, 2) for i in range(10)]
    rows = []
    for ci, name in enumerate(CLASS_NAMES):
        if n_gt[ci] == 0:
            rows.append((name, 0, 0.0, 0.0))
            continue
        m50 = ap_at_iou(preds, gt, ci, 0.5, n_gt[ci])
        m_all = float(np.mean([ap_at_iou(preds, gt, ci, t, n_gt[ci]) for t in ious]))
        rows.append((name, n_gt[ci], m50, m_all))

    print(f"\n{'class':<20} {'GT':>5} {'mAP50':>8} {'mAP50-95':>10}")
    for name, n, a50, aall in rows:
        print(f"{name:<20} {n:>5} {a50:>8.3f} {aall:>10.3f}")
    overall50 = float(np.mean([r[2] for r in rows if r[1] > 0]))
    overallall = float(np.mean([r[3] for r in rows if r[1] > 0]))
    print(f"{'all':<20} {sum(n_gt):>5} {overall50:>8.3f} {overallall:>10.3f}")
    print("\nCompare with best.pt (from training): mAP50 0.935, mAP50-95 0.611")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
