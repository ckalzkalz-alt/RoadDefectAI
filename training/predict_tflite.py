#!/usr/bin/env python3
"""Smoke-test the exported TFLite model the way the Android app runs it.

Loads app/src/main/assets/road_defect_v1.tflite, runs it over RDD2022 images using
the SAME decode logic as TfliteRoadDefectDetector (cx,cy,w,h normalised + 4 class
scores, either [1,8,N] or [1,N,8] layout), draws the boxes and prints a per-class
summary. Use this to sanity-check layout/scale before trusting the app.

    python predict_tflite.py                       # 30 test images, annotated output
    python predict_tflite.py --limit 100 --conf 0.3
    python predict_tflite.py --images dataset\images\val --compare-onnx --limit 40
                                                 # ^ prove TFLite matches the ONNX

--compare-onnx feeds each image through both models and reports the raw output
error and whether the final detections agree. This is the meaningful fidelity
check: comparing random-noise tensors overstates float error.
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import numpy as np

CLASS_NAMES = ["pothole", "longitudinal_crack", "transverse_crack", "alligator_crack"]


def load_interpreter(model_path: Path, num_threads: int = 4):
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore

        return Interpreter(model_path=str(model_path), num_threads=num_threads)
    except Exception:
        import tensorflow as tf  # type: ignore

        return tf.lite.Interpreter(model_path=str(model_path), num_threads=num_threads)


def iou(a, b) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    denom = area_a + area_b - inter
    return inter / denom if denom > 0 else 0.0


def nms(dets, thr=0.5):
    kept = []
    for d in sorted(dets, key=lambda x: -x[0]):
        if all(not (d[1] == k[1] and iou(d[2], k[2]) > thr) for k in kept):
            kept.append(d)
    return kept


def to_preds(raw: np.ndarray, nc: int) -> np.ndarray:
    """Normalise either [1, C, N] or [1, N, C] to [N, 4+nc] (mirrors the app)."""
    y = np.squeeze(raw, 0)
    channels = 4 + nc
    if y.shape[0] == channels:
        return y.T
    if y.shape[1] == channels:
        return y
    raise SystemExit(f"Unexpected output shape {raw.shape}; expected an axis of {channels}")


def decode(preds: np.ndarray, conf: float, scale: float = 1.0):
    """scale converts raw cx/cy/w/h into 0..1 (1/640 for an input-pixel export)."""
    dets = []
    for cx, cy, w, h, *scores in preds:
        c = int(np.argmax(scores))
        s = float(scores[c])
        if s < conf:
            continue
        cx, cy, w, h = cx * scale, cy * scale, w * scale, h * scale
        dets.append((s, c, (float(cx - w / 2), float(cy - h / 2), float(cx + w / 2), float(cy + h / 2))))
    return nms(dets)


def detect_scale(interp, in_shape: np.ndarray, imgsz: int) -> float:
    """Mirror of TfliteRoadDefectDetector.probeBoxScale: one all-zero inference to tell
    whether the export emits input-pixel boxes (divide by imgsz) or normalised 0..1."""
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], np.zeros((1, imgsz, imgsz, 3), dtype=np.float32))
    interp.invoke()
    p = to_preds(interp.get_tensor(out["index"]), len(CLASS_NAMES))
    return 1.0 / imgsz if float(np.abs(p[:, :4]).max()) > 1.5 else 1.0


def preprocess(img_path: Path, imgsz: int) -> np.ndarray:
    from PIL import Image

    im = Image.open(img_path).convert("RGB")
    x = np.asarray(im.resize((imgsz, imgsz)), dtype=np.float32) / 255.0
    return np.expand_dims(x, 0)


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parent
    rdd_test = (repo / "RDD2022_released_through_CRDDC2022" / "RDD2022"
                / "China_MotorBike" / "China_MotorBike" / "test" / "images")

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, default=repo / "app" / "src" / "main" / "assets" / "road_defect_v1.tflite")
    ap.add_argument("--images", type=Path, default=rdd_test, help="Folder of .jpg images to run.")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--out", type=Path, default=here / "predictions")
    ap.add_argument("--no-save", action="store_true", help="Skip writing annotated images.")
    ap.add_argument("--compare-onnx", action="store_true",
                    help="Also run the reference ONNX (runs/*/weights/best.onnx) and report agreement.")
    args = ap.parse_args()

    if not args.model.is_file():
        raise SystemExit(f"model not found: {args.model} (run export_tflite.py first)")
    if not args.images.is_dir():
        raise SystemExit(f"images dir not found: {args.images}")

    from PIL import Image, ImageDraw

    interp = load_interpreter(args.model)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    in_shape, out_shape = [int(v) for v in inp["shape"]], [int(v) for v in out["shape"]]
    print(f"model      : {args.model}")
    print(f"input      : {in_shape} {inp['dtype'].__name__}")
    print(f"output     : {out_shape} {out['dtype'].__name__}")
    print(f"contract ok: input {in_shape == [1, args.imgsz, args.imgsz, 3]}, "
          f"8-channel axis {8 in out_shape}")
    if in_shape != [1, args.imgsz, args.imgsz, 3]:
        print(f"WARNING input is not [1,{args.imgsz},{args.imgsz},3] - adjust --imgsz")
    scale = detect_scale(interp, in_shape, args.imgsz)
    print(f"box scale  : {scale:.6f} "
          f"({'input-pixel export -> divided by input size' if scale != 1.0 else 'already normalised 0..1'})")

    ref = None
    if args.compare_onnx:
        import onnxruntime as ort

        cands = sorted((here / "runs").glob("*/weights/best.onnx"),
                       key=lambda p: p.stat().st_mtime, reverse=True)
        if not cands:
            raise SystemExit("no runs/*/weights/best.onnx - run export_tflite.py first")
        ref = ort.InferenceSession(str(cands[0]), providers=["CPUExecutionProvider"])
        print(f"reference  : {cands[0]}")

    images = sorted(p for p in args.images.iterdir()
                    if p.suffix.lower() in (".jpg", ".jpeg", ".png"))[: args.limit]
    if not images:
        raise SystemExit(f"no images under {args.images}")

    if not args.no_save:
        args.out.mkdir(parents=True, exist_ok=True)

    totals, raw_diffs, agree = Counter(), [], 0
    imgs_with_dets = 0
    for img_path in images:
        x = preprocess(img_path, args.imgsz)
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        lite_out = interp.get_tensor(out["index"])
        preds = to_preds(lite_out, len(CLASS_NAMES))
        dets = decode(preds, args.conf, scale)
        if dets:
            imgs_with_dets += 1

        if ref is not None:
            ref_out = ref.run(None, {ref.get_inputs()[0].name: x.transpose(0, 3, 1, 2)})[0]
            r_preds = to_preds(ref_out, len(CLASS_NAMES))
            raw_diffs.append(float(np.max(np.abs(r_preds - preds))))
            r_dets = decode(r_preds, args.conf, scale)
            # "Agree" = same number of detections, and each ONNX box has a TFLite twin
            # with the same class, IoU > 0.9 and score within 0.02.
            ok = len(r_dets) == len(dets)
            if ok:
                for s2, c2, box2 in r_dets:
                    twin = [s1 for s1, c1, box1 in dets if c1 == c2 and iou(box1, box2) > 0.9]
                    if not twin or abs(twin[0] - s2) > 0.02:
                        ok = False
                        break
            if ok:
                agree += 1

        if not args.no_save:
            img = Image.open(img_path).convert("RGB")
            draw = ImageDraw.Draw(img)
            for s, c, (x1, y1, x2, y2) in dets:
                box = [x1 * img.width, y1 * img.height, x2 * img.width, y2 * img.height]
                draw.rectangle(box, outline=(255, 0, 0), width=3)
                draw.text((box[0] + 3, box[1] + 3), f"{CLASS_NAMES[c]} {s:.2f}", fill=(255, 255, 0))
            img.save(args.out / img_path.name, quality=85)

        for _, c, _ in dets:
            totals[CLASS_NAMES[c]] += 1

    print(f"\nRan {len(images)} images ({args.images})")
    if not args.no_save:
        print(f"Annotated -> {args.out}")
    print(f"Images with >=1 detection: {imgs_with_dets}/{len(images)}")
    print(f"Detections by class @conf>={args.conf}: {dict(totals)}")

    if ref is not None:
        mx, mean = max(raw_diffs), float(np.mean(raw_diffs))
        print(f"\nTFLite vs ONNX ({len(raw_diffs)} images):")
        print(f"  max raw output diff : {mx:.3e}   mean {mean:.3e}")
        print(f"  identical detections: {agree}/{len(images)}")
        # Detection agreement is the pass criterion: onnx2tf restructures convs into
        # NHWC, which reorders float accumulation (~1e-3 elementwise) but must not
        # change what the app would actually see.
        if agree == len(images) and mx < 1e-2:
            print("  => PASS: conversions produce the same detections (diff is float re-ordering only)")
        elif agree == len(images):
            print("  => PASS on detections, but raw diff > 1e-2 - inspect with Netron")
        else:
            print("  => FAIL: detections differ from the reference ONNX")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
