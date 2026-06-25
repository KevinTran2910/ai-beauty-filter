# Extending Filters

The iOS pipeline is wired in **two ObjC++ bridge files**:

- `native-bridge/GPUPixelImageView.mm` → `_ensurePipeline`
- `native-bridge/GPUPixelCameraView.mm` → `_setupGPUPixelPipeline`

Because the C++ engine is **prebuilt** (`BeautyFilter.xcframework`), you **cannot
edit filter source** from the iOS side. You can only:

1. **Wire a filter that already exists in the engine** (for example
   `LipstickFilter`) into the graph.
2. If you need a brand-new filter, rebuild the xcframework from GPUPixel source
   (outside the scope of this iOS repository).

Pattern for adding an existing engine filter to the graph:

1. Add a `std::shared_ptr<XxxFilter>` ivar to **both** views.
2. `Create()` it in `_ensurePipeline` / `_setupGPUPixelPipeline`.
3. Wire it into the graph before the terminal `_beauty`.
4. Push landmarks if the filter needs a face mesh.
5. Add a setter + prop through the view manager if you want UI control.
6. `.reset()` it in teardown / stopCamera.
7. **Repeat for both views** so it applies to static images and the camera.

## Current Pipeline

```text
_source -> _blusher -> _reshape -> _beauty -> _sinkView
```

```cpp
_source->AddSink(_blusher);
_blusher->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

`_sinkView` is always the terminal, so `_beauty->AddSink(_sinkView)` is fixed. A
new filter is inserted **before** `_beauty`.

## Example: Enabling LipstickFilter

`LipstickFilter` already exists in the prebuilt engine:

```text
prebuilt-sdk/ios/include/gpupixel/filter/lipstick_filter.h   (header)
prebuilt-sdk/ios/res/mouth.png                               (texture, already bundled)
```

`LipstickFilter` extends `FaceMakeupFilter`, so it needs a full landmark mesh just
like the blusher.

### 1. Add the ivar (both views)

In `GPUPixelCameraView.mm` and `GPUPixelImageView.mm`:

```cpp
std::shared_ptr<LipstickFilter> _lipstick;
```

Include the header if `gpupixel/gpupixel.h` does not export it:

```cpp
#include "gpupixel/filter/lipstick_filter.h"
```

### 2. Create and wire the graph

Replace:

```cpp
_source->AddSink(_blusher);
_blusher->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

with:

```cpp
_lipstick = LipstickFilter::Create();

_source->AddSink(_blusher);
_blusher->AddSink(_lipstick);
_lipstick->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

### 3. Push landmarks

Camera (`captureOutput:`):

```cpp
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  if (_blusher)  _blusher->SetFaceLandmarks(landmarks);
  if (_lipstick) _lipstick->SetFaceLandmarks(landmarks);
  if (_reshape)  _reshape->SetFaceLandmarks(landmarks);
#endif
```

Static image (`_reprocess`): the same, after `_faceDetector->Detect(...)`.

### 4. Apply the parameter

In `_applyParams` of both views:

```cpp
if (_lipstick) _lipstick->SetBlendLevel(_lipstickLevel / 10.0f);
```

Add the ivar `float _lipstickLevel;` and a setter:

```objc
- (void)setLipstick:(float)value {
  _lipstickLevel = value;
  if (_lipstick) _lipstick->SetBlendLevel(value / 10.0f);  // camera: apply now
  // image view: replace the line above with [self _scheduleReprocess];
}
```

### 5. Expose the prop to React Native

`GPUPixelViewManager.mm` and `GPUPixelImageViewManager.mm`:

```objc
RCT_EXPORT_VIEW_PROPERTY(lipstick, float)
```

`src/index.ts`:

```ts
export interface FilterParamProps {
  smoothing?: number;
  whitening?: number;
  faceSlim?: number;
  eyeEnlarge?: number;
  blusher?: number;
  lipstick?: number;  // new
}
```

`App.tsx`:

```ts
const SLIDERS = [
  // ...
  { key: 'lipstick', label: 'Lipstick' },
];
```

### 6. Teardown

In camera `stopCamera` and image `_teardown`:

```cpp
_lipstick.reset();
```

## React Native Integration Note

- **Camera view:** the setter applies immediately to the engine (the next frame
  reflects the change).
- **Image view:** the setter must call `_scheduleReprocess` to re-render the
  static image (there is no frame loop). Coalescing at the end of the run loop
  avoids redundant renders.

## Writing a Brand-New Filter

This requires editing **GPUPixel source** and rebuilding the xcframework — it
cannot be done from the iOS repo alone. The steps (in the engine repo):

- Subclass `Filter`, `InitWithShaderString(vertex, fragment)`.
- Fetch uniform locations after the program initializes.
- Override `OnRenderWithTexture`, set uniforms, then render.
- Use `FilterGroup` for multi-pass/multi-input filters, like `BeautyFaceFilter`.
- Rebuild both the device and simulator slices, exporting the header into
  `include/gpupixel/filter/`.
- Repackage `BeautyFilter.xcframework` and drop it into `prebuilt-sdk/ios/`.

## Shader Compatibility

The GPUPixel engine shares shaders across platforms (OpenGL ES / desktop /
WebGL). Keep new shaders in the same pattern as neighboring filters so the
cross-platform build is not broken.

## Checklist

- [ ] Does the filter already exist in the prebuilt engine? (If not → rebuild the
      xcframework.)
- [ ] Add the shared_ptr ivar to **both views**.
- [ ] `Create()` in `_ensurePipeline` / `_setupGPUPixelPipeline`.
- [ ] Wire the graph **before** `_beauty`.
- [ ] Push landmarks in `captureOutput:` (camera) and `_reprocess` (image) if the
      filter needs a mesh.
- [ ] Apply the parameter in `_applyParams` (both views).
- [ ] Setter: camera applies immediately, image calls `_scheduleReprocess`.
- [ ] `RCT_EXPORT_VIEW_PROPERTY` in both managers.
- [ ] Update `FilterParamProps` (index.ts) + the slider list (App.tsx).
- [ ] `.reset()` in teardown / stopCamera.
- [ ] Test on a physical iPhone (landmark-driven filters do not run on the
      simulator).
- [ ] Test a frame with no face; the filter must pass through.
