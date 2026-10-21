# Training a road-defect model for RoadDefectAI

This walks through turning the RDD2022 **China_MotorBike** dataset into the single
TFLite file the Android app loads (`app/src/main/assets/road_defect_v1.tflite`).

Everything lives in [`training/`](../training/). Nothing here ships in the APK; the
converter/trainer/export scripts are for your dev machine only.

## 1. Dataset

Source folder (configurable, either nesting level works):

```
RDD2022_released_through_CRDDC2022/RDD2022/China_MotorBike/China_MotorBike/
├── train/
│   ├── images/           1,977 JPG (512×512)
│   └── annotations/xmls/ 1,977 Pascal VOC XML
└── test/images/            500 JPG (NO labels – RDD2022 test set is unlabelled)
```

Raw RDD label counts in the train set: `D00` 2,678 · `D10` 1,096 · `D20` 641 · `D40` 235
· `Repair` 277.

### Class mapping (must match `DefectType.kt`)

| App index | App class | RDD label | Meaning |
|---|---|---|---|
| 0 | `pothole` | `D40` | potholes |
| 1 | `longitudinal_crack` | `D00` | longitudinal crack |
| 2 | `transverse_crack` | `D10` | transverse crack |
| 3 | `alligator_crack` | `D20` | alligator/fatigue crack |

`Repair` (repaired asphalt patches) is **dropped** — it is not one of the app's four
defects. Images that contain *only* Repair boxes are excluded from training.

## 2. Environment setup

```powershell
winget install --id Python.Python.3.11 --scope user
powershell -ExecutionPolicy Bypass -File training\install.ps1
```

`install.ps1` creates `training\.venv`, installs CUDA 12.1 PyTorch (use `-CpuOnly` if
you have no NVIDIA GPU) and Ultralytics. Verify it prints `cuda True` on a GPU box.

## 3. Convert the dataset to YOLO format

```powershell
cd training
.\.venv\Scripts\python.exe convert_rdd_to_yolo.py `
    --rdd ..\RDD2022_released_through_CRDDC2022\RDD2022\China_MotorBike `
    --out dataset
```

This writes `dataset/images/{train,val}`, `dataset/labels/{train,val}` and
`dataset/data.yaml` with an 85/15 stratified-by-shuffle split (fixed `--seed 42`).
Use `--dry-run` to inspect counts without writing, `--copy` to copy instead of
hard-link, `--val-fraction` / `--seed` to change the split.

Expected result on this dataset: **1,644 train / 290 val** images and the four
classes in the exact order above.

## 4. Train

```powershell
.\.venv\Scripts\python.exe train.py                 # yolov8n, 640px, 100 epochs
.\.venv\Scripts\python.exe train.py --model yolov8s.pt --epochs 150 --batch 12
```

- 640 input matches what the app feeds the detector.
- Defaults on an 8 GB GPU (RTX 4060): `yolov8n` + `batch 16` + `cache` off. Add
  `--cache` for faster epochs (dataset is tiny), lower `--batch` if you hit OOM.
- Output: `training/runs/road_defect_v1/weights/best.pt` plus `results.png`,
  `confusion_matrix.png` and `PR_curve.png` for review.

## 5. Export to TFLite and install into the app

```powershell
.\.venv\Scripts\python.exe export_tflite.py
```

This is **not** a plain `yolo export format=tflite`: Ultralytics ≥ 8.4.83 replaced
`format='tflite'` with the unified **LiteRT** export, which fails on Windows with
`LiteRT export only supported on Linux x86 and macOS`. So `export_tflite.py` does the
two steps Ultralytics used to do internally, both of which work on Windows:

1. `best.pt` → **ONNX** (Ultralytics, 640px, simplified, opset 13)
2. ONNX → **TFLite float32** via **`onnx2tf`**

It then verifies the result before touching the app: input shape must be
`[1,640,640,3]`, the output must carry an 8-channel axis, **and** it feeds the same
random tensor through ONNX and TFLite and reports `max|onnx - tflite|`. Only then does
it copy the file to `app/src/main/assets/road_defect_v1.tflite`. Bump
`DetectorFactory.MODEL_VERSION` (e.g. `RoadDefectAI-v1.1`) so stored inspections record
which model produced them.

The detector (`TfliteRoadDefectDetector`) accepts either output layout,
`[1, 4+C, N]` or `[1, N, 4+C]`; anything else fails fast at start-up.

### Box scale — the one that silently kills detections

Ultralytics decodes anchors in **input-pixel space**: on a real export the box channels
ran 2.77..639.0 with `imgsz=640`, identically in ONNX and TFLite. `BoundingBox`
expects normalised 0..1, so feeding raw output through `coerceIn(0f, 1f)` collapses
every box to a degenerate sliver and `isValid()` rejects it — the app reports **zero
detections** while looking perfectly healthy.

`TfliteRoadDefectDetector` therefore runs one all-zero probe frame at start-up and
picks a multiplier (`1/inputSize` for a pixel-space export, `1` if already
normalised); `probeBoxScale` is documented next to `boxScaleMultiplier` in the class,
and unit tests pin both conventions. `predict_tflite.py` and `eval_tflite.py` perform
the same probe. If you replace the export, keep one of those scripts in the loop —
they print the detected scale.

**Windows gotchas this script works around** (all hit here during setup):

| Symptom | Cause | Fix |
|---|---|---|
| `LiteRT export only supported on Linux x86 and macOS` | Ultralytics ≥ 8.4.83 redirects `format='tflite'` to `format='litert'`, which is POSIX-only | export ONNX first, convert with `onnx2tf` (what the script does) |
| `No module named 'onnx_graphsurgeon'` / `No module named 'ai_edge_litert'` | `onnx2tf` declares these inconsistently across versions | `pip install onnx-graphsurgeon ai-edge-litert` |
| `ValueError: This file contains pickled (object) data` | `onnx2tf`'s validation sample URL returns HTTP 404 with a 9-byte `Not Found` body | seed `calibration_image_sample_data_20x128x128x3_float32.npy` in its cwd so it never calls out (the script does this) |

## 6. Validate before trusting it

The shipped model was measured on this dataset; reproduce or re-measure with:

```powershell
# Score the exported TFLite itself on the held-out val split (no labels needed for test/).
.\.venv\Scripts\python.exe eval_tflite.py --nms-iou 0.5
```

Results for `road_defect_v1.tflite` (290 val images, 661 GT boxes, app-identical
post-processing — pixel boxes rescaled, NMS IoU 0.5, conf floor 0.001):

| class | GT | mAP50 | mAP50-95 |
|---|---|---|---|
| pothole | 26 | 0.966 | 0.539 |
| longitudinal_crack | 362 | 0.880 | 0.543 |
| transverse_crack | 170 | 0.831 | 0.465 |
| alligator_crack | 103 | 0.904 | 0.598 |
| **all** | 661 | **0.895** | **0.536** |

Cross-checks: `best.pt` 0.935/0.611 (Ultralytics training) and `best.onnx`
0.927/0.583 (`yolo val`). The TFLite trails the `.pt` by ~0.04 mAP50, consistent with
the ~1e-3 float differences the conversion introduces, not a layout bug — over all 290
val images TFLite and the reference ONNX produce **identical detections** at conf 0.25.

**NMS must be applied before AP.** Scoring raw model output without NMS gives a bogus
mAP50 of ~0.27, because every duplicate anchor of an already-matched object counts as a
false positive. `eval_tflite.py` applies it (`--nms-iou`, default 0.5 = what the app
does; Ultralytics uses 0.7 and gives ~0.893).

Other checks:

1. Watch the training curves — `transverse_crack` is weakest at high IoU (0.465).
2. Run the **exported TFLite** over the unlabelled test images with the same decode the
   app uses, and eyeball the annotated output:

   ```powershell
   .\.venv\Scripts\python.exe predict_tflite.py --limit 50
   ```

   It prints tensor shapes, the detected box scale, detections per class and writes
   annotated JPEGs to `training/predictions/`. Wrong box scale or layout shows up here.
3. Confirm the **MOCK DETECTOR** banner is gone in the app once the asset exists.
4. Only then treat field detections as real. Re-check the README's honesty notes.

## 7. Wiring the CSV/scripts together (optional)

```powershell
# full pipeline, one command
powershell -ExecutionPolicy Bypass -File training\run_all.ps1
```

## Caveats

- **Class imbalance** — `pothole` has only 26 boxes in the val split (235 raw) and
  `alligator_crack` 103 (641 raw). In practice they still scored well here (0.966 and
  0.904 mAP50), but the small counts mean those estimates are noisy; add more of those
  classes before trusting small differences between classes.
- **Resolution** — source images are 512×512; training/export run at 640 (upscaled).
  640 is kept to match the app contract. If aspect/quality suffers, retrain with
  `--imgsz 512` **and** set the app input size to match.
- **Test set is unlabelled**, so it cannot give an mAP — use it for visual QA only.
  The reported metrics come from the 15 % validation split held out from `train/`.
- **Domain** — this is motorcycle-mounted imagery from China; performance on other
  viewpoints/roads will differ. Nothing here has been measured on a real drive.

## Files

| File | Purpose |
|---|---|
| `training/convert_rdd_to_yolo.py` | VOC → YOLO, 4-class mapping, 85/15 split |
| `training/train.py` | Ultralytics YOLOv8 training wrapper |
| `training/export_tflite.py` | TFLite export + verification + copy to app assets |
| `training/eval_tflite.py` | mAP of the exported TFLite on the val split (app-identical post-processing) |
| `training/predict_tflite.py` | Runs the exported TFLite over images (app-identical decode + ONNX fidelity check) |
| `training/run_all.ps1` | convert → train → export → evaluate in one command |
| `training/install.ps1` | One-shot venv + deps setup |
| `training/requirements.txt` | Extra Python deps |
