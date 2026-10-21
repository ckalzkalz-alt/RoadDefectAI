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

## Validating the app's detection on a video (no site trial needed)

The app has a **Home → "Validate from video"** screen that runs the trained detector over a video
file. It is DETECTION VALIDATION ONLY: it bypasses `InspectionController` entirely, so no GPS is
read, nothing is written to Inspection History, and the defect tracker is not involved. That is
deliberate — the tracker and the inference throttle both reason about elapsed wall-clock time, so
feeding them video frames would make confirmation fire spuriously or never, a clock failure that
looks exactly like a detector failure.

Because results are sampled by *video time*, they are reproducible and attributable to an exact
position in the video. Note that a source image held on screen for a full sampling interval is
analysed more than once, so the "Run total" counts an object once per analysed frame — it is a
frame-level count, not a per-object count.

Build a clip with known ground truth, then check the app against an independent reference:

```powershell
# 30 val images, 1 s each -> a 30 s video with 78 known boxes
.\.venv\Scripts\python.exe make_validation_video.py --limit 30

# what the reference implementation finds (expected app totals)
.\.venv\Scripts\python.exe verify_video_run.py --limit 30

adb push validation_video\rdd_val_clip.mp4 /sdcard/Movies/
# then in the app: Validate from video -> Choose a video -> Analyse video
```

Measured 2026-10 (30 images, 500 ms sampling, so each image is analysed twice):

| class | GT | reference/app (per image) | reference expected | app reported |
|---|---|---|---|---|
| pothole | 8 | 8 | 16 | 16 |
| longitudinal_crack | 33 | 41 | 82 | 83 |
| transverse_crack | 26 | 26 | 52 | 56 |
| alligator_crack | 11 | 12 | 24 | 24 |
| **all** | **78** | **87** | **174** | **179** |

The app reproduces an independent implementation of the same model to within 2.9% (2 classes exact);
the small deltas are `MediaMetadataRetriever.getFrameAtTime` landing on a neighbouring frame. This
exercises the whole on-device path: video decode → the shared `FrameDecoder` preprocessing → TFLite
inference → box decode → the same on-screen overlay the inspection draws.

**What this does not validate:** GPS, distance, routing, persistence, or the tracker's
confirmation logic. Those still need a real drive. A phone video also has a different lens, height
and angle than RDD2022's motorcycle camera, so a clip of your own road measures domain shift.

### Checking real field footage (fast, on the desktop)

Before pushing a phone video to a device, these answer the useful questions in seconds per frame.
`scan_field_video.py` uses the app's own decode path, so its counts preview what the app will report.

```powershell
# is the footage usable at all? framing, sharpness, brightness, contact sheet
.\.venv\Scripts\python.exe inspect_field_video.py --video ..\VIDEO\IMG_1901.MOV --samples 48

# what does the model actually find on it?
.\.venv\Scripts\python.exe scan_field_video.py --video ..\VIDEO\IMG_1901.MOV --samples 40 --save-hits field_analysis\hits

# cut a short clip the app can chew through
.\.venv\Scripts\python.exe make_field_clip.py --video ..\VIDEO\IMG_1901.MOV --start 120 --seconds 60
```

**A real 19-minute phone video found almost nothing, and the reason was the framing, not the
model.** Full write-up: [`field_analysis/FINDINGS.md`](field_analysis/FINDINGS.md). In short —
40 frames sampled across `VIDEO/IMG_1901.MOV` (1080×1920 portrait, 19 min) gave **2 detections,
best score 0.317**. The footage is ~96% blue-dominated with saturation 4.7× the training data and
one third its texture, and its upper half is 9–11× busier than its lower half: the camera was
pointed **above the road**, not at it. A clip of sky yields no defects, correctly.

That fix is a re-shoot, not a code change — mount low and angle down so asphalt fills the lower
two thirds. `inspect_field_video.py` shows immediately whether the framing is right (look for
saturation under ~60 and a busier **bottom** half).

A separate, latent issue surfaced while investigating: `TfliteRoadDefectDetector.detect()` resizes
straight to a 640×640 square without preserving aspect ratio — a 1.16× squeeze in landscape but
**1.78× in portrait**. Measured against aspect-preserving letterboxing on the same frames, it
turned out **not** to be the limiting factor here (letterbox found *fewer* detections), so it was
left alone. It is worth revisiting on road footage captured in portrait.

Generated (git-ignored): `.venv/`, `dataset/`, `runs/`, `predictions/`, `validation_video/`, `field_analysis/*.jpg`, `*.pt`, `*.onnx`, `*.tflite`.

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
