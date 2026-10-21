#!/usr/bin/env python3
"""Inspect a field video before running detection on it.

Answers the questions you need before trusting any numbers from it:
  - how long is it, what resolution, what orientation
  - is it actually a road (or a pocket/ceiling/blank wall)
  - how sharp / how bright is it (motion blur and darkness are the usual killers)
  - where are the interesting stretches, so a clip can be cut from a useful part

Writes a contact sheet (grid of sampled frames) plus per-sample quality statistics, so the video
can be judged without watching 19 minutes of it.

    python inspect_field_video.py --video ..\VIDEO\IMG_1901.MOV
    python inspect_field_video.py --video ..\VIDEO\IMG_1901.MOV --samples 60
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = Path(__file__).resolve().parent
    ap.add_argument("--video", type=Path, required=True)
    ap.add_argument("--samples", type=int, default=48, help="How many frames to sample across the video.")
    ap.add_argument("--cols", type=int, default=8, help="Contact-sheet columns.")
    ap.add_argument("--out", type=Path, default=here / "field_analysis")
    ap.add_argument("--tile", type=int, default=240, help="Contact-sheet tile size (px).")
    args = ap.parse_args()

    if not args.video.is_file():
        raise SystemExit(f"video not found: {args.video}")
    args.out.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(str(args.video))
    if not cap.isOpened():
        raise SystemExit(f"could not open {args.video}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    duration_s = total / fps if fps else 0

    print(f"video      : {args.video}")
    print(f"resolution : {w} x {h}  ({'portrait' if h > w else 'landscape'})")
    print(f"fps        : {fps:.2f}   frames: {total}   duration: {duration_s:.1f} s "
          f"({duration_s/60:.1f} min)")

    # Evenly spaced sample positions across the whole video.
    positions = np.linspace(0, max(0, total - 1), args.samples).astype(int)
    tiles, sharp, bright = [], [], []
    for i, pos in enumerate(positions):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(pos))
        ok, frame = cap.read()
        if not ok:
            tiles.append(np.zeros((args.tile, args.tile, 3), np.uint8))
            sharp.append(0.0)
            bright.append(0.0)
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        # Laplacian variance: the standard cheap blur proxy. Low = blurry.
        sharp.append(float(cv2.Laplacian(gray, cv2.CV_64F).var()))
        bright.append(float(gray.mean()))
        small = cv2.resize(frame, (args.tile, args.tile), interpolation=cv2.INTER_AREA)
        label = f"{pos/fps:6.0f}s"
        cv2.putText(small, label, (4, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
        tiles.append(small)
    cap.release()

    # Contact sheet
    cols = args.cols
    rows = (len(tiles) + cols - 1) // cols
    sheet = np.zeros((rows * args.tile, cols * args.tile, 3), np.uint8)
    for i, t in enumerate(tiles):
        r, c = divmod(i, cols)
        sheet[r*args.tile:(r+1)*args.tile, c*args.tile:(c+1)*args.tile] = t
    sheet_path = args.out / (args.video.stem + "_contact_sheet.jpg")
    cv2.imwrite(str(sheet_path), sheet, [cv2.IMWRITE_JPEG_QUALITY, 88])

    sharp_a, bright_a = np.array(sharp), np.array(bright)
    print()
    print(f"{'t(s)':>8} {'sharpness':>11} {'brightness':>11}")
    for i, pos in enumerate(positions):
        print(f"{pos/fps:8.0f} {sharp[i]:11.1f} {bright[i]:11.1f}")

    print()
    print(f"sharpness  : median {np.median(sharp_a):.1f}  p10 {np.percentile(sharp_a,10):.1f}  "
          f"p90 {np.percentile(sharp_a,90):.1f}")
    print(f"brightness : median {np.median(bright_a):.1f}  p10 {np.percentile(bright_a,10):.1f}  "
          f"p90 {np.percentile(bright_a,90):.1f}")
    blurry = int((sharp_a < 100).sum())
    dark = int((bright_a < 40).sum())
    print(f"samples under sharpness 100 (likely blurred): {blurry}/{len(sharp)}")
    print(f"samples under brightness 40 (likely dark)   : {dark}/{len(bright)}")
    print()
    print(f"contact sheet -> {sheet_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
