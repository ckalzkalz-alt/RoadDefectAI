#!/usr/bin/env python3
"""Compare detection on a letterboxed video: full frame vs letterbox-cropped content.

Some phone/exported videos are not true full-frame footage. They carry wide black bars and the
real picture occupies only a horizontal strip in the middle (e.g. 720x1280 with a 294-row picture,
which is really a 2.45:1 ultra-wide image in a portrait container).

Feeding the full frame to the model wastes most of the 640x640 input on black padding. This script
measures the cost by running the SAME frames through both paths:

  as-is : resize the whole frame (carries the black bars)  <- what the app does today
  crop  : detect the content rows, crop the bars, then resize

    python compare_letterbox_crop.py --video "..\\Video 2\\Road assesment.mp4" --samples 40

Note: cropping is only a fair comparison if the content strip is itself road-like. If it is not,
a higher score does not mean the model is working — read it together with inspect_field_video.py.
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

from predict_tflite import CLASS_NAMES, decode, detect_scale, load_interpreter, to_preds


def content_band(frame: np.ndarray, black_level: int = 8, row_frac: float = 0.9):
    """Return (top, bottom) rows that carry picture, ignoring uniform black bars."""
    g = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    row_black = (g < black_level).mean(axis=1)
    rows = np.where(row_black < row_frac)[0]
    if len(rows) < 10:
        return 0, frame.shape[0] - 1
    return int(rows.min()), int(rows.max())


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

    def infer(img_bgr: np.ndarray):
        if img_bgr.shape[0] != args.imgsz or img_bgr.shape[1] != args.imgsz:
            img_bgr = cv2.resize(img_bgr, (args.imgsz, args.imgsz))
        x = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
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

    res = {"asis": Counter(), "crop": Counter()}
    hits = {"asis": 0, "crop": 0}
    loose_hits = {"asis": 0, "crop": 0}
    best = {"asis": 0.0, "crop": 0.0}
    band_fracs = []
    n = 0

    for pos in positions:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(pos))
        ok, frame = cap.read()
        if not ok:
            continue
        n += 1
        t, b = content_band(frame)
        band = frame[t:b + 1, :]
        band_fracs.append(band.shape[0] / frame.shape[0])

        for mode, img in (("asis", frame), ("crop", band)):
            strict, loos = infer(img)
            if strict:
                hits[mode] += 1
            if loos:
                loose_hits[mode] += 1
            for _, c, _ in strict:
                res[mode][CLASS_NAMES[c]] += 1
            for s, _, _ in strict:
                best[mode] = max(best[mode], s)
    cap.release()

    print(f"video        : {args.video.name}")
    print(f"frames       : {n} sampled")
    print(f"content band : {np.mean(band_fracs)*100:.1f}% of frame height, on average")
    print(f"conf         : {args.conf}  (loose {args.loose})")
    print()
    print(f"{'mode':<20}{'hits@conf':>11}{'hits@loose':>12}{'dets':>7}{'best':>8}")
    print("-" * 58)
    for m, lbl in (("asis", "as-is (full frame)"), ("crop", "letterbox cropped")):
        print(f"{lbl:<20}{hits[m]:>8}/{n}{loose_hits[m]:>9}/{n}{sum(res[m].values()):>7}{best[m]:>8.3f}")
    print()
    for m in ("asis", "crop"):
        print(f"  {m:<6} by class: {dict(res[m])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
