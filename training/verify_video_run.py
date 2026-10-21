#!/usr/bin/env python3
"""Verify the app's video-validation mode against an independent reference.

The app's "Validate from video" screen runs the trained detector over a video, sampling every
500 ms of video time. This script reproduces exactly that on the desktop, so the app's on-screen
numbers can be checked against a known-good implementation.

It answers one specific question: does the app's video path measure the same thing the reference
detector measures? Because a video repeats each source image across multiple sampled frames, the
expected total is (per-image detections x number of sampled frames per image) - NOT the per-image
count. This script prints both, so the app's reported total can be compared directly.

    python verify_video_run.py --images dataset/images/val --limit 30 \
        --seconds-per-image 1.0 --interval-ms 500
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import numpy as np

from predict_tflite import CLASS_NAMES, decode, detect_scale, load_interpreter, preprocess, to_preds


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, default=repo / "app" / "src" / "main" / "assets" / "road_defect_v1.tflite")
    ap.add_argument("--images", type=Path, default=here / "dataset" / "images" / "val")
    ap.add_argument("--labels", type=Path, default=here / "dataset" / "labels" / "val")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--limit", type=int, default=30)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--seconds-per-image", type=float, default=1.0,
                    help="Must match make_validation_video.py so the replications are right.")
    ap.add_argument("--interval-ms", type=int, default=500,
                    help="Must match VideoDetectionRunner.DEFAULT_INTERVAL_MS.")
    args = ap.parse_args()

    if not args.model.is_file():
        raise SystemExit(f"model not found: {args.model}")
    if not args.images.is_dir():
        raise SystemExit(f"images dir not found: {args.images}")

    # How many times each source image is analysed, given the sampling interval.
    replications = max(1, int(round(args.seconds_per_image * 1000 / args.interval_ms)))

    interp = load_interpreter(args.model)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    scale = detect_scale(interp, [int(v) for v in inp["shape"]], args.imgsz)

    images = sorted(p for p in args.images.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))[: args.limit]
    if not images:
        raise SystemExit(f"no images under {args.images}")

    per_image = Counter()
    gt = Counter()
    for img_path in images:
        x = preprocess(img_path, args.imgsz)
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        preds = to_preds(interp.get_tensor(out["index"]), len(CLASS_NAMES))
        for _, c, _ in decode(preds, args.conf, scale):
            per_image[CLASS_NAMES[c]] += 1

        label_file = args.labels / f"{img_path.stem}.txt"
        if label_file.is_file():
            for line in label_file.read_text().splitlines():
                p = line.split()
                if len(p) == 5:
                    idx = int(p[0])
                    if 0 <= idx < len(CLASS_NAMES):
                        gt[CLASS_NAMES[idx]] += 1

    print(f"model        : {args.model}")
    print(f"images       : {len(images)}")
    print(f"sampling     : every {args.interval_ms} ms -> each image analysed {replications}x")
    print()
    print(f"{'class':<20} {'GT':>6} {'per-image':>10} {'expected':>9}")
    print("-" * 48)
    tot_gt = tot_pi = tot_ex = 0
    for n in CLASS_NAMES:
        g, pi = gt[n], per_image[n]
        ex = pi * replications
        tot_gt += g; tot_pi += pi; tot_ex += ex
        print(f"{n:<20} {g:>6} {pi:>10} {ex:>9}")
    print("-" * 48)
    print(f"{'TOTAL':<20} {tot_gt:>6} {tot_pi:>10} {tot_ex:>9}")
    print()
    print("The app's 'Total detections' should be close to the 'expected' column:")
    print("each source image is held for the full interval, so everything is counted")
    print(f"{replications}x. Small differences come from getFrameAtTime picking a")
    print("neighbouring frame. This is NOT a per-object count.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
