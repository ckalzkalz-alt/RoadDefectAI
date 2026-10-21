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

---

# Field video analysis: `Video 2/assess 2.mp4` (third clip)

## What the file is
| property | value |
|---|---|
| container | MP4 |
| size | 1017.5 MB |
| resolution | 1080 x 1920 (portrait) |
| fps | 30.00 |
| frames | 11,937 |
| duration | 397.9 s (6 min 38 s) |

## Result: better than clip 1, still not road footage

60 frames sampled evenly, run through the shipped `road_defect_v1.tflite` using the app's own
decode path:

```
frames with a detection @0.25 : 13/60
frames with a detection @0.10 : 19/60
detections @0.25 by class     : {alligator_crack: 12, longitudinal_crack: 1, pothole: 1}  total = 14
best score anywhere           : 0.88 (alligator_crack) at t=391s
```

This is a real improvement on clip 1 (2/40 frames, best 0.317): 13/60 frames and a 0.88 peak.
The model is finding *something*. But the class mix is skewed almost entirely to
`alligator_crack`, which is the class most tolerant of a coarse, distant, low-contrast view —
and the peak scores cluster on a handful of moments rather than persisting.

## Why it is still weak: the frame is half black bars, and pointed high

**1. It is heavily letterboxed — 948 of 1920 rows are picture.** The camera app exported a
roughly 16:9 landscape capture inside a portrait 1080x1920 container: rows 0–320 and 1600–1920
are pure black (brightness 0.0). Only **49.4%** of each frame is real picture, so the model
throws away half of every frame before it even resizes to 640x640.

**2. The camera is still aimed at sky, not asphalt.** Breaking a frame into six horizontal
bands:

| rows | brightness | saturation | edge density | what it is |
|---|---|---|---|---|
| 0–320 | 0.0 | 0.0 | 0.0 | black bar |
| 320–640 | 5.1–6.6 | 3.6–7.3 | 1.0–2.3 | dark / distant upper scene |
| 640–960 | 118–181 | 27–58 | 2.6–22.2 | sky / bright distant scene |
| 960–1280 | 115–157 | 30–66 | 2.6–18.7 | mid-distance |
| 1280–1600 | 86–148 | 41–68 | **1.0–35.9** | supposed road, but nearly featureless |
| 1600–1920 | 0.0 | 0.0–3.3 | 0.0 | black bar |

The lower third — the band that would be road in an RDD2022-style frame — has **edge density
median ~7** against a training reference of **67.7**. That is roughly **0.1x** the texture the
model was trained on. At t=0 it is 2.9; at t=99s it is 1.0. A smooth surface at that distance
is indistinguishable from a wall or a sky, so no cracks can register.

**3. Saturation is still far off.** Saturation on the picture band runs **15–68** (median ~35)
against a training reference of **9.3** — 2–7x too colourful. RDD2022 asphalt is nearly
greyscale.

## Comparison across all three clips

| | clip 1 `IMG_1901.MOV` | clip 2 `Road assesment.mp4` | clip 3 `assess 2.mp4` |
|---|---|---|---|
| resolution | 1080x1920 | 720x1280 | 1080x1920 |
| duration | 19 min | 19 min | 6.6 min |
| picture fraction | full frame | 23.0% (294/1280 rows) | **49.4%** (948/1920 rows) |
| picture-band edge | 23.6 | 9.9 | **~7 (lower third)** |
| saturation | 43.2 | 31.1 | 35 (median) |
| hits @0.25 | 2/40 | (4.4% of frames on device) | 13/60 |
| best score | 0.317 | 0.720 (cropped) | 0.88 |

Clip 3 is the best of the three numerically, but the *reason* it scores higher is not better
framing — it is that more of the frame contains a mid-distance scene the model can weakly
associate with cracks. It is not closer to the training distribution in the way that matters.

## What this does and does not settle

Settled: the model **can** produce confident detections on real phone footage (0.88), so the
pipeline from capture through decode to detection works end to end on genuine video.

Not settled: **whether the model detects defects on a real road surface.** All three clips share
the same defect — the camera is mounted too high and angled too far up, so asphalt never fills
the lower frame. Until that changes, every result measures "distant scene the model half
recognises", not road-defect detection.

## Next step (unchanged, but now better evidenced)

The fix is **camera position, not code or model**. Three clips have now failed the same way,
which makes it a mounting habit rather than bad luck. Specifically:

- mount **low** (handlebar or chest height, not held up at eye level)
- angle **down** so asphalt fills the **lower two thirds** and the horizon sits in the upper
  third at most
- **landscape**, so no black bars are introduced (`Video 2` and `assess 2` are both portrait
  containers with bars — the phone is being held vertically while recording a wide picture)
- ~60 s is plenty

Gate the re-shoot with `inspect_field_video.py`: reject the clip **before** pushing it to a
device if the picture band's edge density is not well above ~20, or saturation is not below ~60,
or the bottom half is not clearly busier than the top half.

A useful hard check while recording: if the horizon is anywhere near the middle of the screen,
the camera is too high.

---

# Field video analysis: `Video 2/Video Project 3.mp4` (fourth clip)

## What the file is
| property | value |
|---|---|
| container | MP4 |
| size | 254.7 MB |
| resolution | **1920 x 1080 (landscape)** |
| fps | 30.00 |
| frames | 3,154 |
| duration | 105.1 s (1 min 45 s) |

This is the first clip in the requested format: landscape, no portrait container, short. It is
also the first **night** clip.

## Result: the first confident crack detections, on an actual road surface

60 frames sampled evenly, run through the shipped `road_defect_v1.tflite` using the app's own
decode path:

```
frames with a detection @0.25 : 5/60
frames with a detection @0.10 : 6/60
detections @0.25 by class     : {longitudinal_crack: 3, transverse_crack: 2}  total = 5
best scores                   : longitudinal_crack 0.80 (t=27s), 0.76 (t=68s)
                                transverse_crack   0.69 (t=48s)
```

This is qualitatively different from the previous three clips. The classes are now **linear
cracks** rather than `alligator_crack`, the scores are high, and they land on isolated moments
rather than a diffuse wash of weak matches. `longitudinal_crack` at 0.80 and
`transverse_crack` at 0.69 are the most credible detections seen on any field footage so far.

## What is genuinely working here

| statistic (picture band) | training | clip 3 `assess 2` | **this clip** |
|---|---|---|---|
| edge density | 67.7 | ~7 | **0.7 .. 20.2 (median ~8)** |
| saturation | 9.3 | ~35 | **4.6 .. 16.2 (median ~12.5)** |
| brightness | 153.6 | ~140 | **39 .. 58** |

**Saturation is now close to the training distribution** - median ~12.5 against 9.3, where the
previous clips were 2-7x too colourful. Grey road at night is much closer to the nearly-greyscale
RDD2022 asphalt than bright daytime footage was.

Sharpness is also the best of any clip: **median 110.9**, p90 444.4, against a training reference
of 67.7. The camera is steady and the surface is in focus.

## What still limits it

**1. It is night footage, and the model has never seen night.** Every one of 24 sampled frames is
below brightness 40 (p10 20.2, p90 29.1); the picture band runs 39-58. Training data is
brightness 153.6. The model is operating far outside its brightness range, which likely explains
why only 5/60 frames fire despite good geometry and strong individual scores. Night is also a
domain the training set does not cover at all, so this is a real generalisation gap, not a
framing problem.

**2. Black bars, again - 44.9% of the frame is discarded.** Picture occupies rows 417-1011, a
595-row band of 1080. Rows 0-417 are pure black (brightness 0.0). The app resizes the RAW frame
straight to 640x640, so it is cropping away **44.9% of every frame** and squashing what remains.
(The bars are horizontal here, so unlike clip 2 this costs vertical detail only, but it is still
nearly half the image gone before the model sees it.)

**3. Edge density is still low in the band (median ~8 vs 67.7).** At night the sensor noise floor
and low contrast suppress the texture cues the model relies on. Two samples did reach 15.6 and
20.2, and those are the moments near the 0.76-0.80 detections - consistent with texture being the
gating factor.

**4. It is short.** 105 s at ~5 detections per 60 frames is a small sample; the 0.80 detection is
a single moment, not a sustained track.

## Comparison across all four clips

| | clip 1 `IMG_1901` | clip 2 `Road assesment` | clip 3 `assess 2` | **clip 4 `Video Project 3`** |
|---|---|---|---|---|
| resolution | 1080x1920 P | 720x1280 P | 1080x1920 P | **1920x1080 L** |
| duration | 19 min | 19 min | 6.6 min | 1.75 min |
| light | day | day | day | **night** |
| picture fraction | full | 23.0% | 49.4% | **55.1%** |
| band edge density | 23.6 | 9.9 | ~7 | ~8 |
| band saturation | 43.2 | 31.1 | ~35 | **~12.5** |
| hits @0.25 | 2/40 | ~4% of frames | 13/60 | 5/60 |
| best score | 0.317 | 0.720 (cropped) | 0.88 | **0.80** |
| classes hit | alligator | longitudinal | alligator | **longitudinal + transverse** |

## Reading of this result

This clip is the strongest evidence so far that the pipeline and the model both work on real
footage: landscape framing, close to training saturation, sharp, and it produces high-confidence
**linear crack** detections - the classes that require actually resolving a line on the road
surface, unlike the diffuse `alligator_crack` matches on the earlier clips.

What it does **not** establish is daytime performance, because it is a night clip and the model's
training set has no night data. Two things now stand between this and a clean validation:

1. **The bars.** A landscape clip should have none. Rows 0-417 being black means the recording
   app is still exporting a letterboxed container. ~45% of every frame is thrown away for no
   reason.
2. **Light.** A daytime landscape clip with this framing and no bars is the missing measurement.

## Next step

This clip is close enough to be worth repeating in daylight rather than rethinking. Specifically:

- keep this **landscape** framing and this camera height/angle
- record in **daylight** (the daytime clips 1-3 all failed on framing; this one's framing is fine
  and its only defect is that it is night)
- eliminate the **black bars** - ~45% of the frame is being discarded; check the recording app
  export settings, since a 1920x1080 landscape capture should fill its own frame
- ~60 s is enough

The daytime version of this exact shot is the measurement that has been missing for four clips,
and this clip is the first one where repeating the shot in better light is the obvious next move
rather than re-shooting from scratch.
