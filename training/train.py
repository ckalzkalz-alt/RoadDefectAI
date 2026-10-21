#!/usr/bin/env python3
"""Train a YOLOv8 detector for RoadDefectAI on the prepared RDD2022 dataset.

Usage (from the training/ folder, venv active):
    python train.py                 # yolov8n, 640px, 100 epochs
    python train.py --model yolov8s.pt --epochs 150 --batch 12

The produced weights land in runs/<name>/weights/best.pt; feed them to
export_tflite.py to get app/src/main/assets/road_defect_v1.tflite.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


def main() -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, default=here / "dataset" / "data.yaml")
    ap.add_argument("--model", default="yolov8n.pt", help="Base weights (yolov8n/s/m). Downloadable name or path.")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--imgsz", type=int, default=640, help="Must match the app's expected input size.")
    ap.add_argument("--batch", type=int, default=16, help="-1 for auto; lower it if you hit OOM on 8 GB.")
    ap.add_argument("--patience", type=int, default=25, help="Early-stop patience (epochs).")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--device", default="0", help="'0' = first CUDA GPU, 'cpu' for CPU.")
    ap.add_argument("--name", default="road_defect_v1")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--cache", action="store_true", help="Cache images in RAM (small dataset => faster epochs).")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    if not args.data.is_file():
        raise SystemExit(f"data.yaml not found at {args.data}. Run convert_rdd_to_yolo.py first.")

    model = YOLO(args.model if args.resume else args.model)
    model.train(
        data=str(args.data),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        patience=args.patience,
        workers=args.workers,
        device=args.device,
        project=str(here / "runs"),
        name=args.name,
        seed=args.seed,
        cache=args.cache,
        resume=args.resume,
        exist_ok=False,
        # Road-defect friendly defaults (kept explicit so they end up in the run config).
        hsv_h=0.015, hsv_s=0.7, hsv_v=0.4,
        degrees=0.0, translate=0.1, scale=0.5, shear=0.0,
        perspective=0.0, flipud=0.0, fliplr=0.5,
        mosaic=1.0, mixup=0.0, close_mosaic=10,
    )
    print(f"\nDone. Best weights: {here / 'runs' / args.name / 'weights' / 'best.pt'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
