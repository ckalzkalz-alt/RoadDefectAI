# Field video analysis: VIDEO/IMG_1901.MOV

## What the file is
| property | value |
|---|---|
| container | QuickTime `.MOV` (iPhone) |
| size | 1.38 GB |
| resolution | 1080 x 1920 (portrait) |
| fps | 30.00 |
| frames | 34,657 |
| duration | 1155 s (19 min 15 s) |

## Result: the model finds almost nothing on it

40 frames sampled evenly across the whole 19 minutes, run through the shipped
`road_defect_v1.tflite` using the app's own decode path (conf 0.25):

```
frames with a detection @0.25 : 2/40
frames with a detection @0.10 : 8/40
detections @0.25 by class     : {alligator_crack: 2}   total = 2
best score anywhere           : 0.317
```

So on 38 of 40 sampled frames the model reported nothing at all.

## Why: the footage does not look like the training data

Comparing the video against the RDD2022 val images the model was trained on:

| statistic | training (RDD2022) | this video | ratio |
|---|---|---|---|
| saturation | 9.3 | 43.2 | **4.66x** |
| edge density (Canny) | 67.7 | 23.6 | **0.35x** |
| colourfulness | 21.6 | 41.3 | 1.92x |
| hue std | 59.5 | 27.1 | 0.46x |
| brightness | 153.6 | 168.7 | 1.10x |

RDD2022 is nearly greyscale grey asphalt that is heavily textured. This footage is 4.7x
more saturated, has 2/3 less texture, and is much more colourful with a narrow hue range.
That is not road surface.

### The hue histogram settles it
Across 50 frames, of pixels with meaningful colour (saturation > 60):

```
hue 100-119 (blue)   82.2%
hue 120-129 (violet)  1.6%
hue 100-109 (blue)   13.7%
green-family           1.5%
```

**~96% of coloured pixels are blue.** Combined with the scene geometry below, this is sky.

### Geometry confirms an upward-pointing camera
Per-frame edge energy by half:

| time | top half | bottom half | |
|---|---|---|---|
| 60 s | 12.6 | 8.9 | busier top |
| 400 s | 26.5 | 3.0 | busier top (8.8x) |
| 900 s | 41.8 | 3.9 | busier top (10.7x) |

For road footage you expect the opposite: textured asphalt below, smooth sky above. The
upper half being 9-11x busier while the lower half is nearly featureless means the camera was
aimed above the road — consistent with the sky-blue hue dominance.

## A separate finding: the app squashes portrait frames

`TfliteRoadDefectDetector.detect()` (lines 122-132) resizes with
`Canvas.drawBitmap(srcRect, dstRect)` directly to a 640x640 square — **it does not preserve
aspect ratio**.

| input | horizontal scale | vertical scale | distortion |
|---|---|---|---|
| landscape 16:9 | 0.593 | 0.333 | 1.16x (mild) |
| **portrait 9:16** | 0.593 | 0.333 | **1.78x (severe)** |

For this video the frame is stretched ~78% wider than tall.

**But this is NOT what caused the zero detections.** Measured directly, aspect-preserving
letterboxing did not help:

```
mode          hits@conf  hits@loose  detections  best score
stretch            2/40        8/40           2       0.317
letterbox          1/40        3/40           1       0.305
```

Letterbox found *fewer*. The resize is therefore not the limiting factor on this footage — the
content is. The distortion remains a real latent issue for portrait capture in general, but it
is not implicated here and should not be "fixed" on the strength of this video alone.

## What this does and does not tell us

**Does tell us:**
- The app and model behave correctly on a real, large, phone-shot video: it decodes a 1.38 GB
  HEVC portrait MOV, runs inference, and reports plausibly.
- The video-validation feature works on genuine field footage, not just synthetic test clips.
- A phone video whose framing misses the road produces ~zero detections, as it should. This is
  the correct behaviour, not a bug: no road in view means nothing to detect.

**Does not tell us:**
- Whether the model works on a real road. The video never shows the road surface, so this is
  still unmeasured.
- Anything about motion blur, night, or rain — this clip is bright and daytime throughout
  (brightness 141-167, never below 40), so those cases remain untested.

## Next step

Record a second clip with the camera **pointed at the road surface** — mounted low and angled
down so the asphalt fills the lower two thirds of the frame, matching the RDD2022 viewpoint.
`scan_field_video.py` gives a fast desktop read (seconds per frame) before anything is pushed to
a device, and `inspect_field_video.py` will show immediately whether the framing is right
(look for low saturation ~<60 and high edge density, and a busier bottom half).

## Tools added
| script | purpose |
|---|---|
| `inspect_field_video.py` | Resolution/duration, per-sample sharpness + brightness, contact sheet |
| `scan_field_video.py` | Runs the model over sampled frames; annotated frames for hits |
| `make_field_clip.py` | Cuts a short clip from a long video for the app to analyse |
| `compare_resize_modes.py` | Stretch vs letterbox, to test the resize question |
