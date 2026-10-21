#!/usr/bin/env python3
"""Measure how much the app's non-aspect-preserving resize costs on real footage.

TfliteRoadDefectDetector.detect() stretches every frame to a square 640x640 with
Canvas.drawBitmap src->dst, which does NOT preserve aspect ratio. In landscape that is a mild
~1.16x squeeze; in PORTRAIT it is ~1.78x, which distorts exactly the elongated features (cracks)
the model was trained on.

This runs the same frames through both preprocessing paths so the cost can be seen rather than
guessed:
  A) stretch  : resize to 640x640, ignoring aspect   (what the app does today)
  B) letterbox: resize preserving aspect, pad to 640 (the standard YOLO approach)

    python compare_resize_modes.py --video ..\VIDEO\IMG_1901.MOV --samples 40
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

from predict_tflite import CLASS_NAMES, decode, detect_scale, load_interpreter, to_preds


def letterbox(frame: np.ndarray, size: int, pad_value: int = 114) -> np.ndarray:
    """Aspect-preserving fit into a size x size square, padded (standard YOLO preprocessing)."""
    h, w = frame.shape[:2]
    scale = min(size / w, size / h)
    nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
    resized = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_AREA)
    out = np.full((size, size, 3), pad_value, dtype=np.uint8)
    top, left = (size - nh) // 2, (size - nw) // 2
    out[top:top+nh, left:left+nw] = resized
    return out


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--video", type=Path, required=True)
    ap.add_argument("--model", type=Path,
                    default=repo / "app" / "src" / "main" / "assets" / "road_defect_v1.tflite")
    ap.add_argument("--samples", type=int, default=40)
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--seconds", type=float, default=0.0)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--loose", type=float, default=0.10)
    args = ap.parse_args()

    if not args.video.is_file():
        raise SystemExit(f"video not found: {args.video}")

    interp = load_interpreter(args.model)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    scale = detect_scale(interp, [int(v) for v in inp["shape"]], args.imgsz)

    def run(frame_bgr: np.ndarray, mode: str):
        img = cv2.resize(frame_bgr, (args.imgsz, args.imgsz)) if mode == "stretch" \
            else letterbox(frame_bgr, args.imgsz)
        x = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        interp.set_tensor(inp["index"], np.expand_dims(x, 0))
        interp.invoke()
        preds = to_preds(interp.get_tensor(out["index"]), len(CLASS_NAMES))
        return decode(preds, args.conf, scale), decode(preds, args.loose, scale)

    cap = cv2.VideoCapture(str(args.video))
    if not cap.isOpened():
        raise SystemExit(f"could not open {args.video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    lo = int(args.start * fps)
    hi = int((args.start + args.seconds) * fps) if args.seconds > 0 else total - 1
    hi = min(hi, max(0, total - 1))
    positions = np.linspace(lo, hi, args.samples).astype(int)

    tally = {"stretch": Counter(), "letterbox": Counter()}
    hits = {"stretch": 0, "letterbox": 0}
    loose_hits = {"stretch": 0, "letterbox": 0}
    best = {"stretch": 0.0, "letterbox": 0.0}
    n = 0

    for pos in positions:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(pos))
        ok, frame = cap.read()
        if not ok:
            continue
        n += 1
        for mode in ("stretch", "letterbox"):
            strict, loos = run(frame, mode)
            if strict:
                hits[mode] += 1
            if loos:
                loose_hits[mode] += 1
            for _, c, _ in strict:
                tally[mode][CLASS_NAMES[c]] += 1
            for s, _, _ in strict:
                best[mode] = max(best[mode], s)
    cap.release()

    print(f"video   : {args.video.name}")
    print(f"frames  : {n} sampled from {args.start:.0f}s..{args.start + (args.seconds or total/fps):.0f}s")
    print(f"conf    : {args.conf}  (loose {args.loose})")
    print()
    print(f"{'mode':<12} {'hits@conf':>10} {'hits@loose':>11} {'detections':>11} {'best score':>11}")
    print("-" * 60)
    for mode in ("stretch", "letterbox"):
        print(f"{mode:<12} {hits[mode]:>7}/{n} {loose_hits[mode]:>8}/{n} "
              f"{sum(tally[mode].values()):>11} {best[mode]:>11.3f}")
    print()
    for mode in ("stretch", "letterbox"):
        print(f"{mode:<12} by class: {dict(tally[mode])}")
    print()
    if hits["letterbox"] > hits["stretch"]:
        print("=> Letterbox finds MORE. The app's stretch is costing detections on this footage.")
    elif hits["letterbox"] == hits["stretch"] and sum(tally["letterbox"].values()) == sum(tally["stretch"].values()):
        print("=> No difference on this footage: the model is not finding anything either way,")
        print("   so the resize is not the limiting factor here - look at the content/orientation.")
    else:
        print("=> Stretch does not lose detections here.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
