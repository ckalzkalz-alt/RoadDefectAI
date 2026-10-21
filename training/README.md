# training/

Dev-machine only. Turns RDD2022 into the TFLite model the Android app loads.

Full guide: [`../docs/TRAINING.md`](../docs/TRAINING.md).

Quick start (Windows):

```powershell
# 1. environment (needs Python 3.10-3.12 installed)
powershell -ExecutionPolicy Bypass -File install.ps1

# 2. dataset (VOC -> YOLO, app's 4-class order, 85/15 split)
.\.venv\Scripts\python.exe convert_rdd_to_yolo.py `
    --rdd ..\RDD2022_released_through_CRDDC2022\RDD2022\China_MotorBike --out dataset

# 3. train
.\.venv\Scripts\python.exe train.py

# 4. export into app/src/main/assets/road_defect_v1.tflite
.\.venv\Scripts\python.exe export_tflite.py

# 5. score the exported file on the held-out split (do not skip this)
.\.venv\Scripts\python.exe eval_tflite.py --nms-iou 0.5
```

Or all of it at once: `powershell -ExecutionPolicy Bypass -File run_all.ps1`.

Generated (git-ignored): `.venv/`, `dataset/`, `runs/`, `predictions/`, `*.pt`, `*.onnx`, `*.tflite`.

## Measured result (China_MotorBike, 2026-10)

The shipped `app/src/main/assets/road_defect_v1.tflite` on the 290-image held-out
split (661 ground-truth boxes), scored by `eval_tflite.py` with the app's own
post-processing (input-pixel boxes rescaled, NMS IoU 0.5, conf floor 0.001):

| class | GT | mAP50 | mAP50-95 |
|---|---|---|---|
| pothole | 26 | 0.966 | 0.539 |
| longitudinal_crack | 362 | 0.880 | 0.543 |
| transverse_crack | 170 | 0.831 | 0.465 |
| alligator_crack | 103 | 0.904 | 0.598 |
| **all** | 661 | **0.895** | **0.536** |

For reference, `best.pt` scored 0.935/0.611 during training and Ultralytics'
validator on `best.onnx` scored 0.927/0.583, so the TFLite conversion is faithful.

**Box scale.** Ultralytics decodes anchors in *input-pixel* space (2.77..639.0 at
input 640), not the normalised 0..1 that `BoundingBox` documents. Feeding that
straight through clamped every box to a sliver and reported zero detections.
`TfliteRoadDefectDetector` now probes the export once at start-up and rescales, so
both conventions work; `eval_tflite.py`/`predict_tflite.py` mirror the same probe.

Caveats: `pothole` and `alligator_crack` have few training boxes (235 / 641 raw);
`transverse_crack` is weakest at high IoU (0.465); the 500-image RDD test split has
no public labels, so it can only be inspected visually.
