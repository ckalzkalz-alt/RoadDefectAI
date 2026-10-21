#!/usr/bin/env python3
"""Cut a short clip from a long field video, around a chosen time range.

Field videos are minutes long; running the app over all of one takes far too long. This cuts a
short, representative MP4 that the app's "Validate from video" screen can analyse in a minute or
two, and downsamples to a sensible width so the emulator can decode it reliably.

    python make_field_clip.py --video ..\VIDEO\IMG_1901.MOV --start 120 --seconds 60
    python make_field_clip.py --video ..\VIDEO\IMG_1901.MOV --start 600 --seconds 30 --width 720
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2


def main() -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--video", type=Path, required=True)
    ap.add_argument("--start", type=float, default=0.0, help="Start time in seconds.")
    ap.add_argument("--seconds", type=float, default=30.0, help="Clip length in seconds.")
    ap.add_argument("--width", type=int, default=720, help="Output width (aspect preserved).")
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    if not args.video.is_file():
        raise SystemExit(f"video not found: {args.video}")

    cap = cv2.VideoCapture(str(args.video))
    if not cap.isOpened():
        raise SystemExit(f"could not open {args.video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    out_w = args.width
    out_h = int(round(h * out_w / w)) if w else h
    # Even dimensions keep every encoder happy.
    out_h -= out_h % 2
    out_w -= out_w % 2

    out = args.out or (here / "field_analysis" /
                       f"{args.video.stem}_{int(args.start)}s_{int(args.seconds)}s.mp4")
    out.parent.mkdir(parents=True, exist_ok=True)

    cap.set(cv2.CAP_PROP_POS_FRAMES, int(args.start * fps))
    writer = cv2.VideoWriter(str(out), cv2.VideoWriter_fourcc(*"mp4v"), fps, (out_w, out_h))
    if not writer.isOpened():
        raise SystemExit("could not open the video writer")

    want = int(args.seconds * fps)
    written = 0
    try:
        while written < want:
            ok, frame = cap.read()
            if not ok:
                break
            writer.write(cv2.resize(frame, (out_w, out_h), interpolation=cv2.INTER_AREA))
            written += 1
    finally:
        writer.release()
        cap.release()

    dur = written / fps if fps else 0
    print(f"wrote      : {out}")
    print(f"source     : {args.video.name} from {args.start:.0f}s")
    print(f"clip       : {written} frames @ {fps:.2f} fps = {dur:.1f} s at {out_w}x{out_h}")
    print(f"app note   : at 500 ms sampling this is ~{int(dur/0.5)} analysed frames")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
