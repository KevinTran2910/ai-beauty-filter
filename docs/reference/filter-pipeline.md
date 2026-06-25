# Filter Pipeline

The native pipeline is a directed graph:

```text
Source -> Filter -> Filter -> ... -> Sink
```

Each filter takes the texture from the previous node, renders a shader into a new
framebuffer, and passes the result to the next sink. The engine is the **prebuilt
GPUPixel** library inside `BeautyFilter.xcframework`.

## Main Graph

Both `GPUPixelImageView` and `GPUPixelCameraView` wire the graph **identically**
in `_setupGPUPixelPipeline` / `_ensurePipeline`:

```text
SourceRawData
  -> BlusherFilter
  -> FaceReshapeFilter
  -> BeautyFaceFilter
  -> SinkView
```

```cpp
_source->AddSink(_blusher);
_blusher->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

The terminal filter is always `BeautyFaceFilter`, followed by `SinkView`. The iOS
demo always renders to `SinkView` for both static images and the camera — it only
displays results and has no capture/save feature.

When the detector is off (simulator), `_blusher` and `_reshape` remain in the
graph but pass through because there are no landmarks; only the smoothing and
whitening of `BeautyFaceFilter` take effect.

## SourceRawData

**Header:** `prebuilt-sdk/ios/include/gpupixel/source/source_raw_data.h`

Receives a frame from the bridge through a single API:

```cpp
void ProcessData(const uint8_t* data, int width, int height,
                 int stride, GPUPIXEL_FRAME_TYPE type);
```

Two formats are used in the iOS app:

```text
Camera:  GPUPIXEL_FRAME_TYPE_BGRA   (from a 32BGRA CVPixelBuffer)
Image:   GPUPIXEL_FRAME_TYPE_RGBA   (from a CGBitmapContext)
```

BGRA path:

```text
CVPixelBuffer 32BGRA (1280x720)
-> CVPixelBufferGetBaseAddress + stride
-> SourceRawData::ProcessData(..., BGRA)
-> shader converts BGRA to RGB
-> NoRotation (AVFoundation already rotated/mirrored)
```

RGBA path:

```text
UIImage -> CGBitmapContext RGBA (top-left, orientation baked in)
-> SourceRawData::ProcessData(..., RGBA)
-> NoRotation
```

`SourceRawData` reuses its GL texture/framebuffer when the dimensions do not
change.

## BlusherFilter

**Header:** `prebuilt-sdk/ios/include/gpupixel/filter/blusher_filter.h`
**Base:** `FaceMakeupFilter`
**Requires:** valid landmarks (device only)

Overlays `res/blusher.png` onto the cheek region using the triangle mesh in
`FaceMakeupFilter`.

Bridge mapping:

```cpp
_blusher->SetBlendLevel(value / 10.0f);  // value: 0..10
```

If there is no face or the landmarks are insufficient, the filter passes through.

## FaceReshapeFilter

**Header:** `prebuilt-sdk/ios/include/gpupixel/filter/face_reshape_filter.h`
**Requires:** ~106 landmark points (device only)

Two effects:

- Face slim: `curveWarp` pulls the contour region inward.
- Eye enlarge: `enlargeEye` around the two eye centers.

Bridge mapping:

```cpp
_reshape->SetFaceSlimLevel(value / 200.0f);  // faceSlim 0..10
_reshape->SetEyeZoomLevel(value / 100.0f);   // eyeEnlarge 0..10
```

Warping is computed in UV space `[0,1]`. Only effective on device (a detector is
required).

## BeautyFaceFilter

**Header:** `prebuilt-sdk/ios/include/gpupixel/filter/beauty_face_filter.h`
**Type:** `FilterGroup`

This filter does **not** require landmarks, so it works on the simulator too.

Internal graph:

```text
input
  -> BilateralFilter -----------\
  -> BoxHighPassFilter ---------+-> BeautyFaceUnitFilter -> output
  -> original ------------------/
```

### BilateralFilter

Two passes (horizontal/vertical) of edge-preserving blur based on color distance.
No landmarks needed.

### BoxHighPassFilter

Produces a detail/variance map from `original - boxblur(original)`. Helps the
shader decide which regions to smooth.

### BeautyFaceUnitFilter

**Header:** `prebuilt-sdk/ios/include/gpupixel/filter/beauty_face_unit_filter.h`

1. Skin smoothing:
   - edge detection to preserve edges
   - an RGB-based skin heuristic
   - blends the original with the bilateral output
   - keeps/sharpens detail from the high-pass map

2. Whitening: a LUT chain `lookup_gray.png` -> `lookup_origin.png` ->
   `lookup_skin.png` -> `lookup_light.png` (uniform `lookUpCustom`), blended by
   the `whiten` uniform.

Bridge mapping:

```cpp
_beauty->SetBlurAlpha(value / 10.0f);  // smoothing 0..10
_beauty->SetWhite(value / 20.0f);      // whitening 0..10
```

The iOS bridge **always** calls `SetWhite(value / 20.0f)`, so moving the whitening
slider back to 0 resets whitening correctly.

## SinkView

**Header:** `prebuilt-sdk/ios/include/gpupixel/sink/sink_view.h`

```cpp
static std::shared_ptr<SinkView> Create(void* parent_view);
```

`parent_view` is the `UIView` itself (the bridge passes `(__bridge void*)self`).
`SinkView` creates a GL layer (`CAEAGLLayer`) on the view and renders the terminal
texture **directly to the screen**.

Because of this, `SinkView` needs valid bounds before it is created — the bridge
waits for `layoutSubviews` / `didMoveToWindow` before calling `_ensurePipeline`:

```objc
if (CGRectIsEmpty(self.bounds)) return;  // SinkView needs bounds
```

The demo has **no** `SinkRawData` / `glReadPixels` path — there is no CPU readback
and no file output. Both modes render straight to the screen.

## Face Detector

**Header:** `prebuilt-sdk/ios/include/gpupixel/face_detector/face_detector.h`
**Backend:** `MNN.framework` + `libmars-face-kit.a` (device slice only)

```cpp
std::vector<float> Detect(const uint8_t* data, int width, int height,
                          int stride, GPUPIXEL_MODE_FMT fmt,
                          GPUPIXEL_FRAME_TYPE type);
```

The camera uses `GPUPIXEL_MODE_FMT_VIDEO` + `GPUPIXEL_FRAME_TYPE_BGRA`. Static
images use `GPUPIXEL_MODE_FMT_PICTURE` + `GPUPIXEL_FRAME_TYPE_RGBA`.

Returned landmarks are pushed into `BlusherFilter` and `FaceReshapeFilter`.
`BeautyFaceFilter` does not need landmarks.

The detection code is wrapped in `#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR`, so the
simulator slice (which does not define this macro) compiles it out entirely.

## Adding Filters

See [extending-filters.md](extending-filters.md). Key point: because the terminal
is `BeautyFaceFilter -> SinkView` and the graph is wired in **both**
`GPUPixelImageView.mm` and `GPUPixelCameraView.mm`, a new filter must be added to
**both files** to apply to both static images and the camera.
