#!/usr/bin/env python3
"""Build a test video from RDD2022 val images, for exercising the app's video-validation path.

The app's "Validate from video" mode runs the trained detector over a video file. To check that
mode against something with KNOWN ground truth, this script turns a slice of the prepared val
split into a short MP4: one image per second, in order.

Why this is a fair test:
  - the val images already have YOLO labels, so what the model should see is known
  - the app samples every 500 ms of video time, so consecutive images are each analysed
  - it isolates DETECTION from the camera: no camera, no GPS, no tracker, fully reproducible

Output defaults to training/validation_video/rdd_val_clip.mp4 so it can be pushed to a device with
`adb push`. Generation is done with OpenCV (already a dependency) so no extra package is needed.

    python make_validation_video.py --limit 30
    python make_validation_video.py --limit 60 --seconds-per-image 1.0 --fps 10
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

CLASS_NAMES = ["pothole", "longitudinal_crack", "transverse_crack", "alligator_crack"]


def load_labels(label_path: Path) -> list[tuple[int, float, float, float, float]]:
    """YOLO rows -> (class, cx, cy, w, h), skipping malformed lines."""
    out = []
    if not label_path.is_file():
        return out
    for line in label_path.read_text().splitlines():
        p = line.split()
        if len(p) == 5:
            try:
                out.append((int(p[0]), float(p[1]), float(p[2]), float(p[3]), float(p[4])))
            except ValueError:
                continue
    return out


def main() -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--images", type=Path, default=here / "dataset" / "images" / "val")
    ap.add_argument("--labels", type=Path, default=here / "dataset" / "labels" / "val")
    ap.add_argument("--out", type=Path, default=here / "validation_video" / "rdd_val_clip.mp4")
    ap.add_argument("--limit", type=int, default=30, help="How many images to include.")
    ap.add_argument("--seconds-per-image", type=float, default=1.0)
    ap.add_argument("--fps", type=int, default=10)
    ap.add_argument("--size", type=int, default=640, help="Frame size; 640 matches the model input.")
    ap.add_argument("--draw-labels", action="store_true",
                    help="Burn ground-truth boxes into the video (for manual comparison against the app).")
    args = ap.parse_args()

    if not args.images.is_dir():
        raise SystemExit(f"images dir not found: {args.images}")

    images = sorted(p for p in args.images.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))[: args.limit]
    if not images:
        raise SystemExit(f"no images under {args.images}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(args.out), fourcc, float(args.fps), (args.size, args.size))
    if not writer.isOpened():
        raise SystemExit("could not open the video writer (is the mp4v codec available?)")

    held = int(round(args.seconds_per_image * args.fps))
    counts = {i: 0 for i in range(len(CLASS_NAMES))}
    frames_written = 0
    try:
        for img_path in images:
            img = cv2.imread(str(img_path))
            if img is None:
                continue
            img = cv2.resize(img, (args.size, args.size), interpolation=cv2.INTER_AREA)

            labels = load_labels(args.labels / f"{img_path.stem}.txt")
            for cls, cx, cy, w, h in labels:
                if cls in counts:
                    counts[cls] += 1
                if args.draw_labels:
                    x1 = int((cx - w / 2) * args.size)
                    y1 = int((cy - h / 2) * args.size)
                    x2 = int((cx + w / 2) * args.size)
                    y2 = int((cy + h / 2) * args.size)
                    cv2.rectangle(img, (x1, y1), (x2, y2), (0, 255, 0), 2)
                    cv2.putText(img, CLASS_NAMES[cls] if cls < len(CLASS_NAMES) else str(cls),
                                (x1, max(12, y1 - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

            for _ in range(held):
                writer.write(img)
                frames_written += 1
    finally:
        writer.release()

    duration = frames_written / args.fps if args.fps else 0
    print(f"wrote      : {args.out}")
    print(f"images     : {len(images)}")
    print(f"frames     : {frames_written} @ {args.fps} fps  ({duration:.1f} s)")
    print(f"gt boxes   : " + ", ".join(f"{CLASS_NAMES[i]}={counts[i]}" for i in range(len(CLASS_NAMES))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
