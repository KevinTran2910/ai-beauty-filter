# Architecture

## Overview

The iOS app is built from three layers:

- **React Native (TypeScript)**: UI, the image/camera mode toggle, the image
  picker, the five sliders, and parameter passing through props.
- **ObjC++ bridge** (`native-bridge/*.mm`): connects React Native to the C++
  engine, manages `AVCaptureSession`, decodes images, and owns view lifecycle.
- **Native C++ engine** (`BeautyFilter.xcframework`): the GPUPixel filter graph,
  OpenGL ES rendering, and the face-detector wrapper. This is a **prebuilt
  binary**; the iOS repository does not rebuild the C++ code.

The integration is **prop-driven**: React Native sets props on the native view
and each setter forwards the value to the engine. There is **no readback** —
both the static-image and camera paths render straight into `SinkView`, a
`CAEAGLLayer` hosted on the `UIView` itself.

```text
React Native App.tsx (mode: image | camera)
  -> props (smoothing, whitening, faceSlim, eyeEnlarge, blusher, imageUri)
  -> RCTViewManager (RCT_EXPORT_VIEW_PROPERTY)
  -> GPUPixelImageView / GPUPixelCameraView (ObjC++)
  -> SourceRawData (BGRA or RGBA)
  -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter
  -> SinkView (renders directly onto the UIView, no readback)
```

Two processing paths share the same pipeline:

```text
Real-time camera:
  AVCaptureSession (front, BGRA 1280x720)
  -> captureOutput delegate on a serial queue
  -> FaceDetector::Detect(BGRA, VIDEO) per frame
  -> SourceRawData::ProcessData(BGRA)
  -> pipeline -> SinkView

Static image:
  UIImage -> CGBitmapContext RGBA (orientation baked in)
  -> FaceDetector::Detect(RGBA, PICTURE)
  -> SourceRawData::ProcessData(RGBA)
  -> pipeline -> SinkView
```

## React Native Layer

`app-template/App.tsx` is responsible for:

- Toggling between the `image` and `camera` modes.
- `launchImageLibrary` (react-native-image-picker) for selecting a photo.
- Five `0..10` sliders: smoothing, whitening, faceSlim, eyeEnlarge, blusher.
- Rendering `GPUPixelImageView` or `GPUPixelCameraView` and passing parameters
  **through props**.

`app-template/src/index.ts` declares the two native components with
`requireNativeComponent`:

```ts
export const GPUPixelImageView =
  requireNativeComponent<GPUPixelImageViewProps>('GPUPixelImageView');
export const GPUPixelCameraView =
  requireNativeComponent<GPUPixelCameraViewProps>('GPUPixelCameraView');
```

The UI is pure React Native; there is no separate native screen.

## Bridge Layer

`native-bridge/` contains:

| File | Role |
|---|---|
| `GPUPixelImageView.{h,mm}` | Native view for static-image processing |
| `GPUPixelImageViewManager.{h,mm}` | Exports `GPUPixelImageView` + props to RN |
| `GPUPixelCameraView.{h,mm}` | Native view for real-time camera |
| `GPUPixelViewManager.{h,mm}` | Exports `GPUPixelCameraView` + props to RN |
| `GPUPixelModule.{h,mm}` | Imperative native module (legacy): start/stop camera and setters via `viewRegistry` |

`GPUPixelModule` is the older imperative control path. The current demo prefers
the **prop-driven** approach because, under React Native New Architecture,
lookup through `RCTUIManager viewRegistry` is less reliable for Fabric/interop
views. See [api-bridge.md](api-bridge.md).

## Native Layer

The engine lives in `prebuilt-sdk/ios/BeautyFilter.xcframework`. Each ObjC++ view
owns its pipeline locally using `std::shared_ptr`:

```cpp
std::shared_ptr<SourceRawData>     _source;
std::shared_ptr<SinkView>          _sinkView;
std::shared_ptr<BeautyFaceFilter>  _beauty;
std::shared_ptr<FaceReshapeFilter> _reshape;
std::shared_ptr<BlusherFilter>     _blusher;
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
std::shared_ptr<FaceDetector>      _faceDetector;
#endif
```

The pipeline is held **per view instance**. Every `GPUPixelCameraView` and
`GPUPixelImageView` has its own graph, so there is no shared global engine state.

Graph when `GPUPIXEL_ENABLE_FACE_DETECTOR=1` (device builds):

```text
SourceRawData -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter -> SinkView
```

When the detector is off (simulator builds), the code still creates `_blusher`
and `_reshape`, but they pass through because there are no landmarks; only the
smoothing/whitening of `BeautyFaceFilter` takes effect.

## Threading Model

Native access is not serialized through a single executor. Instead:

- **Camera frames** run on the serial queue `com.gpupixel.camera` (configured via
  `setSampleBufferDelegate:queue:`).
- **Static-image reprocessing** is scheduled with
  `dispatch_async(dispatch_get_main_queue())` and coalesces multiple prop
  changes into a single render pass at the end of the run loop.
- **Parameter setters** may be called from any thread (the RN UIManager thread)
  and are guarded only by null checks such as `if (_beauty) ...`.

GPUPixel manages its own GL context and each view has an independent pipeline, so
there is no global lock. Thread safety still needs attention when a slider emits
many changes while the camera is actively processing frames (see the hardening
section in [ios-integration-guide.md](../integration/ios-integration-guide.md)).

## Camera Pipeline

Core `AVCaptureSession` configuration (`GPUPixelCameraView._setupCaptureSession`):

- `AVCaptureSessionPreset1280x720`
- Front camera: `AVCaptureDeviceTypeBuiltInWideAngleCamera`,
  `AVCaptureDevicePositionFront`
- Output format: `kCVPixelFormatType_32BGRA`
- `alwaysDiscardsLateVideoFrames = YES` (drops late frames while the pipeline is
  busy)
- Connection: `videoOrientation = Portrait`, `videoMirrored = YES`

Preview path:

1. `captureOutput:didOutputSampleBuffer:` receives a `CMSampleBufferRef`.
2. Locks the `CVImageBufferRef` and reads `baseAddress`, `width`, `height`,
   `stride`.
3. If a detector is present: `FaceDetector::Detect(BGRA, VIDEO)` produces
   landmarks pushed into `_blusher` and `_reshape`.
4. `SourceRawData::ProcessData(baseAddress, w, h, stride, GPUPIXEL_FRAME_TYPE_BGRA)`.
5. The pipeline renders into `SinkView`.
6. The buffer is unlocked.

There is no `glReadPixels` and no libyuv repacking: the camera already produces
BGRA and the shader converts it to RGB on the GPU.

## Rotation and Mirroring

AVFoundation handles rotation and mirroring:

```objc
conn.videoOrientation = AVCaptureVideoOrientationPortrait;
conn.videoMirrored = YES;  // selfie mirror
```

Frames arrive at the delegate already correctly oriented, so `SourceRawData` uses
the default `NoRotation`.

For static images, EXIF orientation is **baked in during decode** by flipping the
Y axis in a `CGBitmapContext` before `drawInRect` (see the image-decode section
in [api-bridge.md](api-bridge.md)).

## Face Detection Strategy

The current implementation detects **every frame on the full-resolution BGRA
buffer**:

```cpp
std::vector<float> landmarks = _faceDetector->Detect(
    baseAddress, width, height, stride,
    GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_BGRA);
```

There is no detection throttle and no downscaling, which makes this the first
candidate for FPS optimization (see
[ios-integration-guide.md](../integration/ios-integration-guide.md)). Static
images use `GPUPIXEL_MODE_FMT_PICTURE`.

Landmarks are normalized to `[0,1]` in input-image space and applied directly to
the output.

## Assets

Resources are not copied manually at runtime. CocoaPods copies `res/` and
`models/` into the **app bundle** at build time:

```ruby
s.resources = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models']
```

Native code resolves resources through the bundle:

```objc
NSString* resPath = [[NSBundle mainBundle] resourcePath];
GPUPixel::SetResourceRoot([resPath UTF8String]);
```

See [assets-and-textures.md](assets-and-textures.md).

## Simulator vs Device

| Capability | Simulator | Physical iPhone |
|---|---|---|
| Skin smoothing | Yes | Yes |
| Whitening | Yes | Yes |
| Face landmarks | No | Yes |
| Face slim / Eye enlarge / Blusher | No | Yes |
| Real-time camera | No | Yes |

Reason: `MNN.framework` and `libmars-face-kit.a` only ship a device
(`iphoneos`) slice. The simulator slice is built with
`GPUPIXEL_ENABLE_FACE_DETECTOR` turned off.

## Performance Notes

Current optimizations:

- Camera input stays in native BGRA; color conversion happens in the GPU shader.
- Both modes render straight into `SinkView` (`CAEAGLLayer`) with **no readback**.
- `alwaysDiscardsLateVideoFrames` drops late frames when the pipeline is busy.
- Static-image rendering **coalesces reprocessing** at the end of the run loop,
  avoiding redundant renders while dragging multiple sliders.
- Pipelines are per view, so there is no shared global GL state.

Known room for improvement: face detection runs **every frame at full
resolution**. Throttling and/or downscaling detection is the first candidate for
raising FPS.
