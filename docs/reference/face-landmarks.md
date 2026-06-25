# Face Landmarks — Coordinate System

## Overview

Face detection uses **mars-face-kit** (packaged in `libmars-face-kit.a` +
`MNN.framework`, device slice only). `FaceDetector::Detect()` normalizes each
keypoint `(x, y)` to `[0, 1]` in input-image space (top-left origin, x to the
right, y downward).

The ObjC++ bridge calls the detector directly inside each view:

```cpp
std::vector<float> landmarks = _faceDetector->Detect(
    data, width, height, stride, fmt, type);
```

Where:

- **Camera** (`GPUPixelCameraView`): `fmt = GPUPIXEL_MODE_FMT_VIDEO`,
  `type = GPUPIXEL_FRAME_TYPE_BGRA`.
- **Static image** (`GPUPixelImageView`): `fmt = GPUPIXEL_MODE_FMT_PICTURE`,
  `type = GPUPIXEL_FRAME_TYPE_RGBA`.

The landmarks are then pushed into the filters:

```cpp
if (_blusher) _blusher->SetFaceLandmarks(landmarks);
if (_reshape) _reshape->SetFaceLandmarks(landmarks);
```

This entire block is inside `#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR`, so the
**simulator has no landmarks** (the macro is not defined for the simulator slice).

## Face Regions and Index Ranges (mars-face-kit standard)

```
        Forehead
    ┌──────────────┐
    │  17 ─── 26   │  ← Eyebrows: 17–21 (left), 22–26 (right)
    │  36  38  45  │  ← Eyes: 36–41 (left), 42–47 (right)
    │    30─33     │  ← Nose bridge: 27–30, nostrils: 31–35
    │  48─────54   │  ← Mouth outer: 48–59
    │  60─────67   │  ← Mouth inner: 60–67
    │    8         │  ← Chin tip: 8
    └──┐         ┌─┘
       0─────────16   ← Jawline: 0–16 (17 points)
```

| Index range | Face region | Notes |
|---|---|---|
| 0 – 16 | Jawline / chin contour | 17 points, 0=left, 16=right, 8=chin tip |
| 17 – 21 | Left eyebrow | Outer → inner |
| 22 – 26 | Right eyebrow | Inner → outer |
| 27 – 30 | Nose bridge | Top-down |
| 31 – 35 | Nose tip + nostrils | 31=tip |
| 36 – 41 | Left eye | 36=outer, 39=inner |
| 42 – 47 | Right eye | 42=inner, 45=outer |
| 48 – 59 | Mouth outer lip | 48=left, 54=right |
| 60 – 67 | Mouth inner lip | |
| 68 – 75 | Pupils + eye centers | |
| 76 – 83 | Iris points | |
| 84 – 105 | Additional contour | Cheeks, extras |

> **Note:** mars-face-kit is closed-source. The mapping above is inferred from
> the behavior of `FaceReshapeFilter` and `FaceMakeupFilter`. `FaceReshapeFilter`
> needs at least 106 points; `FaceMakeupFilter` uses mesh indices up to 110, so
> it needs at least 111 points.

## Indices Used in Code

The engine hardcodes the following shader indices:

### FaceReshapeFilter — thinFace

```cpp
// 9 origin -> target pairs hardcoded in the shader:
(3 -> 44), (29 -> 44), (7 -> 45), (25 -> 45), (10 -> 46),
(22 -> 46), (14 -> 49), (18 -> 49), (16 -> 49)
```

### FaceReshapeFilter — bigEye

```cpp
enlargeEye(center=landmarks[74], radiusAnchor=landmarks[72], bigEyeDelta); // left eye
enlargeEye(center=landmarks[77], radiusAnchor=landmarks[75], bigEyeDelta); // right eye
```

### FaceMakeupFilter — triangle mesh

A 111-point (0–110) triangle mesh covering eyebrows, eyes, cheeks (the
`BlusherFilter` region), nose, lips, and chin. Triangle indices are hardcoded in
the engine's `face_makeup_filter.cc`.

## Coordinate Transforms

### mars-face-kit output → filter input

```
[0, 1] normalized  →  stays [0, 1] when passed to SetFaceLandmarks()
```

### FaceMakeupFilter — clip space

```cpp
float clipX = landmark.x * 2.0f - 1.0f;  // [0,1] -> [-1,1]
float clipY = landmark.y * 2.0f - 1.0f;
```

### FaceReshapeFilter — UV space

```cpp
float dist = distance(uv, anchorPoint);
vec2 warpedUV = uv + direction * falloff(dist) * magnitude;
```

## Detector Input on iOS

| | Value |
|---|---|
| Input format | BGRA (camera) / RGBA (image) |
| Detection size | full-resolution frame |
| Throttle | every frame |
| Orientation | AVFoundation rotates/mirrors (camera), EXIF baked in (image) |
| Where Detect is called | inside each ObjC++ view |

Detection currently runs every frame at full resolution. Adding a throttle and/or
downscaling is the first candidate for raising FPS (see
[architecture.md](architecture.md) and
[ios-integration-guide.md](../integration/ios-integration-guide.md)).

## Debug: Exposing Landmarks to JS

`Detect()` returns a `std::vector<float>` that is kept inside the view
(`_landmarks` in `GPUPixelImageView`) and is **not** sent to JS. To debug or
overlay in React Native:

1. Add an `onFaceDetected` event to the view manager using
   `RCTBubblingEventBlock` / `RCTDirectEventBlock`.
2. In the detect callback, convert the `std::vector<float>` into an `NSArray` and
   emit the event.
3. In JS, read `event.nativeEvent.landmarks` (an array of `[0,1]`-normalized x,y
   pairs) and draw the overlay with `<Svg>` or an absolutely positioned `<View>`.

Consecutive values form an `(x, y)` pair; multiply by the view size to get
pixels.
