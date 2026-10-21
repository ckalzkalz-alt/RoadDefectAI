#!/usr/bin/env python3
"""Run the trained model over sampled frames of a field video, on the desktop.

This is the fast way to find out whether a field video contains anything the model recognises,
before spending time pushing clips to a device. It uses the exact app decode path (same
predict_tflite helpers), so the numbers preview what the app's video mode would report.

    python scan_field_video.py --video ..\VIDEO\IMG_1901.MOV --samples 40
    python scan_field_video.py --video ..\VIDEO\IMG_1901.MOV --start 600 --seconds 120 --samples 60
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import cv2
import numpy as np

from predict_tflite import CLASS_NAMES, decode, detect_scale, load_interpreter, to_preds


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--video", type=Path, required=True)
    ap.add_argument("--model", type=Path,
                    default=repo / "app" / "src" / "main" / "assets" / "road_defect_v1.tflite")
    ap.add_argument("--samples", type=int, default=40)
    ap.add_argument("--start", type=float, default=0.0, help="Start time in seconds.")
    ap.add_argument("--seconds", type=float, default=0.0, help="Window length; 0 = whole video.")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--conf", type=float, default=0.25, help="The app's gate.")
    ap.add_argument("--loose", type=float, default=0.10, help="A permissive gate, to see near-misses.")
    ap.add_argument("--save-hits", type=Path, default=None,
                    help="Write annotated frames that produced detections, for visual review.")
    args = ap.parse_args()

    if not args.video.is_file():
        raise SystemExit(f"video not found: {args.video}")

    interp = load_interpreter(args.model)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    scale = detect_scale(interp, [int(v) for v in inp["shape"]], args.imgsz)

    cap = cv2.VideoCapture(str(args.video))
    if not cap.isOpened():
        raise SystemExit(f"could not open {args.video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)

    lo = int(args.start * fps)
    hi = int((args.start + args.seconds) * fps) if args.seconds > 0 else total - 1
    hi = min(hi, max(0, total - 1))
    if hi <= lo:
        raise SystemExit("empty time window")
    positions = np.linspace(lo, hi, args.samples).astype(int)

    if args.save_hits:
        args.save_hits.mkdir(parents=True, exist_ok=True)

    tally = Counter()
    rows = []
    hits = 0
    loose_hits = 0
    for pos in positions:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(pos))
        ok, frame = cap.read()
        if not ok:
            continue
        x = cv2.cvtColor(cv2.resize(frame, (args.imgsz, args.imgsz)), cv2.COLOR_BGR2RGB)
        x = x.astype(np.float32) / 255.0
        interp.set_tensor(inp["index"], np.expand_dims(x, 0))
        interp.invoke()
        preds = to_preds(interp.get_tensor(out["index"]), len(CLASS_NAMES))

        strict = decode(preds, args.conf, scale)
        loose = decode(preds, args.loose, scale)
        if strict:
            hits += 1
        if loose:
            loose_hits += 1
        for _, c, _ in strict:
            tally[CLASS_NAMES[c]] += 1
        rows.append((pos / fps, len(loose), len(strict),
                     ", ".join(f"{CLASS_NAMES[c]}:{s:.2f}" for s, c, _ in strict[:3])))

        if args.save_hits and strict:
            img = frame.copy()
            for s, c, (x1, y1, x2, y2) in strict:
                p1 = (int(x1 * img.shape[1]), int(y1 * img.shape[0]))
                p2 = (int(x2 * img.shape[1]), int(y2 * img.shape[0]))
                cv2.rectangle(img, p1, p2, (0, 0, 255), 3)
                cv2.putText(img, f"{CLASS_NAMES[c]} {s:.2f}", (p1[0], max(20, p1[1] - 6)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
            cv2.imwrite(str(args.save_hits / f"hit_{int(pos/fps):05d}s.jpg"), img)

    cap.release()

    print(f"video        : {args.video.name}")
    print(f"window       : {args.start:.0f}s .. {args.start + (args.seconds or (total/fps)):.0f}s")
    print(f"sampled      : {len(rows)} frames")
    print()
    print(f"{'t(s)':>7} {'@loose':>7} {'@conf':>6}  detections at conf")
    for t, nl, ns, lbl in rows:
        print(f"{t:7.0f} {nl:7} {ns:6}  {lbl}")
    print()
    print(f"frames with a detection @{args.conf}: {hits}/{len(rows)}")
    print(f"frames with a detection @{args.loose}: {loose_hits}/{len(rows)}")
    print(f"detections @{args.conf} by class: {dict(tally)}  total={sum(tally.values())}")
    if args.save_hits:
        print(f"annotated hits -> {args.save_hits}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
