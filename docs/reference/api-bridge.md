# Bridge API Reference

React Native talks to the C++ engine through an **ObjC++ bridge** using two
mechanisms:

- **Prop-driven (recommended):** `RCTViewManager` exports the native view's
  props; RN sets a prop, the ObjC++ setter runs, and the value reaches the
  engine.
- **Imperative (legacy):** `GPUPixelModule` (`RCTBridgeModule`) calls methods
  through `viewRegistry`. Less reliable under New Architecture.

| Component | File | Role |
|---|---|---|
| `GPUPixelImageView` | `native-bridge/GPUPixelImageView.mm` | Static-image view |
| `GPUPixelImageViewManager` | `native-bridge/GPUPixelImageViewManager.mm` | Exports view + props (`GPUPixelImageView`) |
| `GPUPixelCameraView` | `native-bridge/GPUPixelCameraView.mm` | Real-time camera view |
| `GPUPixelViewManager` | `native-bridge/GPUPixelViewManager.mm` | Exports view + props (`GPUPixelCameraView`) |
| `GPUPixelModule` | `native-bridge/GPUPixelModule.mm` | Imperative native module (legacy) |

## TypeScript Surface

`app-template/src/index.ts`:

```ts
export interface FilterParamProps {
  smoothing?: number;  // 0 – 10
  whitening?: number;  // 0 – 10
  faceSlim?: number;   // 0 – 10
  eyeEnlarge?: number; // 0 – 10
  blusher?: number;    // 0 – 10
}

export interface GPUPixelImageViewProps extends FilterParamProps {
  style?: ViewStyle;
  imageUri?: string;   // file:// or an absolute path
}

export interface GPUPixelCameraViewProps extends FilterParamProps {
  style?: ViewStyle;
}

export const GPUPixelImageView =
  requireNativeComponent<GPUPixelImageViewProps>('GPUPixelImageView');
export const GPUPixelCameraView =
  requireNativeComponent<GPUPixelCameraViewProps>('GPUPixelCameraView');
```

The names `'GPUPixelImageView'` and `'GPUPixelCameraView'` match the
`RCT_EXPORT_MODULE(...)` calls in the view managers.

## Prop API — GPUPixelImageView

The manager (`GPUPixelImageViewManager.mm`) exports:

```objc
RCT_EXPORT_MODULE(GPUPixelImageView)
RCT_EXPORT_VIEW_PROPERTY(imageUri, NSString)
RCT_EXPORT_VIEW_PROPERTY(smoothing, float)
RCT_EXPORT_VIEW_PROPERTY(whitening, float)
RCT_EXPORT_VIEW_PROPERTY(faceSlim, float)
RCT_EXPORT_VIEW_PROPERTY(eyeEnlarge, float)
RCT_EXPORT_VIEW_PROPERTY(blusher, float)
```

Each prop maps to a setter of the same name on the view. Every setter calls
`_scheduleReprocess`, which coalesces changes into a single render pass at the
end of the run loop:

```objc
- (void)setImageUri:(NSString*)uri { [self _loadImage:uri]; [self _scheduleReprocess]; }
- (void)setSmoothing:(float)value  { _smoothing = value;  [self _scheduleReprocess]; }
// ... whitening / faceSlim / eyeEnlarge / blusher follow the same pattern
```

### Image decode — `_loadImage:`

1. Strip the `file://` prefix to obtain the path.
2. `[UIImage imageWithContentsOfFile:path]`.
3. Compute pixel size: `width * scale`, `height * scale`.
4. Draw into a `CGBitmapContext` RGBA
   (`kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big`).
5. **Bake orientation:** flip the Y axis (`TranslateCTM(0,h)` + `ScaleCTM(1,-1)`)
   before `drawInRect`, producing a top-left-origin frame with the correct EXIF
   orientation.
6. Store into `std::vector<uint8_t> _rgba`, set `_imgWidth/_imgHeight`, and set
   `_hasImage = YES`.

### Reprocess — `_reprocess`

```objc
[self _ensurePipeline];                       // create the graph if needed; requires valid bounds
if (!_pipelineReady || !_hasImage) return;
[self _applyParams];
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  _landmarks = _faceDetector->Detect(data, _imgWidth, _imgHeight, _imgWidth*4,
                                     GPUPIXEL_MODE_FMT_PICTURE, GPUPIXEL_FRAME_TYPE_RGBA);
  _blusher->SetFaceLandmarks(_landmarks);
  _reshape->SetFaceLandmarks(_landmarks);
#endif
_source->ProcessData(data, _imgWidth, _imgHeight, _imgWidth*4, GPUPIXEL_FRAME_TYPE_RGBA);
```

`_scheduleReprocess` uses `dispatch_async(main_queue)` guarded by a
`_reprocessScheduled` flag, so multiple prop changes collapse into exactly one
render.

## Prop API — GPUPixelCameraView

The manager (`GPUPixelViewManager.mm`) exports:

```objc
RCT_EXPORT_MODULE(GPUPixelCameraView)
RCT_EXPORT_VIEW_PROPERTY(smoothing, float)
RCT_EXPORT_VIEW_PROPERTY(whitening, float)
RCT_EXPORT_VIEW_PROPERTY(faceSlim, float)
RCT_EXPORT_VIEW_PROPERTY(eyeEnlarge, float)
RCT_EXPORT_VIEW_PROPERTY(blusher, float)
```

Camera setters apply to the engine **immediately** (no coalescing) and also cache
the value so it can be re-applied once the pipeline is ready:

```objc
- (void)setSmoothing:(float)value {
  _smoothing = value;
  if (_beauty) _beauty->SetBlurAlpha(value / 10.0f);
}
```

### Automatic lifecycle

The camera **starts and stops automatically** with the view lifecycle — JS does
not need to call any method:

```objc
- (void)didMoveToWindow {
  [super didMoveToWindow];
  if (self.window) [self _maybeStartCamera];
  else             [self stopCamera];
}
- (void)layoutSubviews { [super layoutSubviews]; [self _maybeStartCamera]; }

- (void)_maybeStartCamera {
  if (_pipelineReady || _starting) return;
  if (!self.window) return;
  if (CGRectIsEmpty(self.bounds)) return;  // SinkView needs valid bounds
  [self startCamera];
}
```

`startCamera` requests camera permission
(`AVCaptureDevice requestAccessForMediaType:`) and then sets up the pipeline and
`AVCaptureSession`. `stopCamera` stops the session and calls `.reset()` on every
shared_ptr.

> On the simulator, `startCamera` returns early (`#if TARGET_OS_SIMULATOR`)
> because there is no camera.

### Camera frame callback

```objc
- (void)captureOutput:(AVCaptureOutput*)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection*)connection {
  if (!_pipelineReady) return;
  CVImageBufferRef pb = CMSampleBufferGetImageBuffer(sampleBuffer);
  CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
  // baseAddress / width / height / stride
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  landmarks = _faceDetector->Detect(base, w, h, stride,
                                    GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_BGRA);
  _blusher->SetFaceLandmarks(landmarks);
  _reshape->SetFaceLandmarks(landmarks);
#endif
  _source->ProcessData(base, w, h, stride, GPUPIXEL_FRAME_TYPE_BGRA);
  CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
}
```

This runs on the serial queue `com.gpupixel.camera`. There is no detection
throttle and no downscaling.

## Imperative API — GPUPixelModule (legacy)

`GPUPixelModule.mm` exports an `RCTBridgeModule` with `methodQueue = main_queue`.
Each method looks the view up through `RCTUIManager viewRegistry[reactTag]`:

| Method | Parameters | Effect |
|---|---|---|
| `startCamera(reactTag)` | view tag | calls `[view startCamera]` |
| `stopCamera(reactTag)` | view tag | calls `[view stopCamera]` |
| `setBeautyParams(reactTag, smoothing, whitening)` | 0..10 | `setSmoothing` + `setWhitening` |
| `setReshapeParams(reactTag, faceSlim, eyeEnlarge)` | 0..10 | `setFaceSlim` + `setEyeEnlarge` |
| `setMakeupParams(reactTag, blusher)` | 0..10 | `setBlusher` |

> **Not recommended** under New Architecture: `viewRegistry` does not reliably
> retain Fabric/interop views, so `viewRegistry[reactTag]` may return nil. Use
> the prop-driven API instead.

## Parameter Mapping

The public UI range is `0..10`, scaled down before reaching the engine:

| Prop | UI range | Native mapping | Target |
|---|---:|---|---|
| `smoothing` | `0..10` | `value / 10.0` | `BeautyFaceFilter::SetBlurAlpha` |
| `whitening` | `0..10` | `value / 20.0` | `BeautyFaceFilter::SetWhite` |
| `faceSlim` | `0..10` | `value / 200.0` | `FaceReshapeFilter::SetFaceSlimLevel` |
| `eyeEnlarge` | `0..10` | `value / 100.0` | `FaceReshapeFilter::SetEyeZoomLevel` |
| `blusher` | `0..10` | `value / 10.0` | `BlusherFilter::SetBlendLevel` |

`faceSlim`, `eyeEnlarge`, and `blusher` only take effect when landmarks are
available (device builds). `smoothing` and `whitening` work on the simulator too.

## Error Handling

The bridge does not throw; it logs through `NSLog` with the `[GPUPixel]` prefix:

- Camera permission denied by the user: `"Người dùng từ chối quyền camera."`
- Permission denied/restricted: `"Quyền camera bị từ chối/giới hạn..."`
- No front camera (typically the simulator): `"Không tìm thấy camera trước..."`

To productionize this (event callbacks such as `onReady` / `onError` /
`onCameraPermissionDenied`), see
[ios-integration-guide.md](../integration/ios-integration-guide.md).
