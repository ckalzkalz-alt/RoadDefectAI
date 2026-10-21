# RoadDefect AI (Android MVP)

Mount phone → Start Inspection → drive → on-device AI detects **pothole, longitudinal crack, transverse crack, alligator crack** → defect auto-captured with GPS → duplicates suppressed → saved locally → stop → review list → map.

## Honest status

- **A trained model IS included.** `app/src/main/assets/road_defect_v1.tflite` (12.7 MB) is a YOLOv8n detector trained on RDD2022 China_MotorBike (1,644 images, 4 classes, 100 epochs). Measured on the 290-image held-out split: **mAP50 0.895, mAP50-95 0.536** (per-class numbers and caveats in [`training/README.md`](training/README.md)). Delete the asset to fall back to `MockRoadDefectDetector`, which emits synthetic detections and shows a red **MOCK DETECTOR** banner; mock output says nothing about real AI performance.
- Built and verified: 45 unit tests pass (`app/src/test`, including 3 that pin the box-scale probe); debug APK `app/build/outputs/apk/debug/app-debug.apk` (46.9 MB, up from 34.9 MB now that it embeds the 12.7 MB model) builds successfully. Instrumented tests (`app/src/androidTest`, controller + Room + storage linkage) need a device/emulator and have **not** been run.
- `TfliteRoadDefectDetector`'s output path is now validated against the trained model (shapes, box scale, per-image detections vs ground truth). The trained model has never been run on a real drive, so field behaviour (motion blur, rain, night, angle) is still unmeasured.

## Open and run

1. Android Studio (Koala+ or newer), open this folder, let it sync (Gradle 8.9 wrapper is provided). JDK 17, compileSdk 34, minSdk 26.
2. Run on a physical phone (camera + GPS). Unit tests: `./gradlew test`. Device tests: `./gradlew connectedAndroidTest`.

## Architecture

```
ui/            Compose screens (Home, Setup, Inspection, Summary, Map, History, Settings)
inspection/    InspectionController  – state machine, frame pipeline, GPS stamping (owned by Application)
camera/        CameraPreview + FrameAnalyzer (CameraX, throttled by inferenceFps)
ai/            RoadDefectDetector interface, TfliteRoadDefectDetector, MockRoadDefectDetector, DetectorFactory
tracking/      DefectTracker – de-duplication / persistence logic (pure Kotlin)
location/      LocationService (fused GPS), LocationAssessor, DistanceAccumulator (pure logic)
data/          Room: inspections, defects, route_points; ImageStore; InspectionRepository
config/        DetectionConfig + ConfigStore (every threshold lives here)
```

The AI layer does not know about Room, CameraX or the UI; the data layer does not know about the AI layer.

## How duplicates are prevented

Per analysed frame: drop detections below `minConfidence` (default 70 %) or malformed → match to an open track of the same type (IoU or nearby centre) → a track becomes **one** record when it persisted `minPersistFrames` (3) frames **or** hit `strongSingleFrameConfidence` (92 %) → suppressed if the same type was recorded within `cooldownMs` (4 s) **and** within `minDistanceBetweenSameTypeM` (8 m). A clearly better later frame replaces the stored image/confidence (max 3 times per defect). Tracks unseen for 1.5 s expire.

## Replacing / training the model

Contract (`TfliteRoadDefectDetector`): YOLOv8-style detector, input `[1,S,S,3]` float32 RGB 0..1, output `[1, 4+4, N]` (or `[1, N, 8]`) = cx, cy, w, h normalised 0..1, then 4 class scores. Class order must be:

| index | label |
|---|---|
| 0 | pothole |
| 1 | longitudinal_crack |
| 2 | transverse_crack |
| 3 | alligator_crack |

Do not merge the crack classes. The full pipeline (dataset conversion, training, ONNX→`onnx2tf`→TFLite export, installation, evaluation) lives in [`training/`](training/README.md) with instructions in [`docs/TRAINING.md`](docs/TRAINING.md); `training\run_all.ps1` runs it end to end. Bump `DetectorFactory.MODEL_VERSION` (stored on every inspection for later model comparison) whenever the asset changes. A model with a different number of classes fails fast at start-up with a clear message instead of giving wrong results.

**Box scale (important).** Ultralytics exports decode anchors in *input-pixel* space (e.g. 2.77..639.0 at input 640), **not** the normalised 0..1 named above. `TfliteRoadDefectDetector` probes the model once at start-up and rescales accordingly, so both conventions work; do not assume the raw output is normalised.

Future categories (rutting, edge breaks, …) are added to `DefectType` and the model; they are intentionally not implemented.

## Behaviour notes

- **Offline-first.** Camera, AI, GPS, storage need no network. The map shows markers and route without data; background tiles only load online (INTERNET permission is used for that alone).
- **GPS.** Fix worse than 20 m → defect saved but flagged *LOW GPS ACCURACY*; no fresh fix (5 s) → saved with **no coordinates** (flagged *NO GPS*). Speed is shown only when the provider supplies it.
- **Safety.** Setup shows the required mounting message with an acknowledgement. During driving there are no pop-ups; Back is disabled and **Stop must be held 1.5 s**. The screen stays on while inspecting.
- **Lifecycle.** CameraX stops when the app is backgrounded, so the inspection is **auto-paused** (clearly flagged) rather than silently recording nothing. If the process is killed mid-inspection it is marked INTERRUPTED with data kept. Rotation is handled in-place (no camera restart); a 180° flip does not trigger a configuration change and is not re-applied until the next one.
- **Storage.** One downscaled JPEG (≤1280 px, quality 80) per defect, written atomically. Rejected detections are kept (status REJECTED) but excluded from totals and the map.
- **Cloud-ready.** Rows carry a `uuid` and `syncState`; nothing uploads yet.
- Permissions: camera, fine/coarse location, internet (map tiles). No motion-sensor permission is required.

## Known limitations / next steps

1. Provide and validate a real trained model (the biggest item).
2. Tune `inferenceFps`, thresholds and the duplicate distance/cooldown with field drives; at high speed the 8 m / 4 s defaults may merge close defects of the same type.
3. Run on devices to confirm overlay alignment, thermal behaviour and battery use on long inspections.
4. No UI automation tests for permission dialogs/rotation yet; those rules are covered at logic level (`CameraRulesTest`) and the controller at instrumented level.
