# iOS Integration Guide

This guide is for an iOS team that wants to integrate the SDK more deeply instead
of only running the current React Native demo.

## Current State

The SDK is exposed to the app through the local CocoaPod `BeautyFilterSDK`.

Current integration shape:

- The React Native app uses `requireNativeComponent`.
- The native bridge is written in ObjC++.
- The GPUPixel engine is packaged in `BeautyFilter.xcframework`.
- Device face detection uses `MNN.framework` and `libmars-face-kit.a`.
- Runtime resources are copied into the app bundle by CocoaPods.

This is appropriate for a demo or proof of concept. For production integration,
the iOS team should standardize the API surface, lifecycle handling, error
handling, and packaging.

## Integration Path 1: Keep React Native and Improve the Bridge

This path fits teams whose main app remains React Native.

Recommended work:

- Extract the JS wrapper into a dedicated package, for example
  `@company/beauty-filter-react-native`.
- Define a stable public API for `GPUPixelImageView` and `GPUPixelCameraView`.
- Add native-to-JS event callbacks:
  - `onReady`
  - `onError`
  - `onCameraPermissionDenied`
  - `onFaceDetected`
  - `onFrameStats`
- Standardize parameter ranges and presets:
  - `natural`
  - `beauty`
  - `makeup`
  - `custom`
- Remove or deprecate the older imperative native module if the team continues
  with a prop-driven API.

Reason to prefer the prop-driven API: with React Native New Architecture,
imperative lookup through `RCTUIManager viewRegistry` can be less reliable than
passing props directly into the native view.

## Integration Path 2: Create a Native iOS SDK Layer

This path fits teams that want to use the SDK directly from Swift/ObjC without a
React Native dependency.

Recommended split:

```text
BeautyFilterCore
├── GPUPixel engine wrapper
├── Resource/model loading
├── Face detector lifecycle
├── Image processing API
└── Camera processing API

BeautyFilterReactNative
├── RCTViewManager
├── requireNativeComponent wrapper
└── React Native event/prop mapping
```

Suggested native API:

```objc
@interface BeautyFilterConfig : NSObject
@property(nonatomic) float smoothing;
@property(nonatomic) float whitening;
@property(nonatomic) float faceSlim;
@property(nonatomic) float eyeEnlarge;
@property(nonatomic) float blusher;
@end

@interface BeautyFilterImageProcessor : NSObject
- (UIImage *)processImage:(UIImage *)image config:(BeautyFilterConfig *)config error:(NSError **)error;
@end

@interface BeautyFilterCameraView : UIView
- (void)startWithConfig:(BeautyFilterConfig *)config;
- (void)stop;
- (void)updateConfig:(BeautyFilterConfig *)config;
@end
```

For Swift apps, expose a Swift-friendly wrapper:

```swift
let config = BeautyFilterConfig(
    smoothing: 0.6,
    whitening: 0.2,
    faceSlim: 0.03,
    eyeEnlarge: 0.03,
    blusher: 0.2
)

cameraView.start(config: config)
```

## Production Packaging

Recommended options:

- Use a private CocoaPods spec repo if the organization already uses CocoaPods.
- Or package as a Swift Package if the binary/resource layout is standardized for
  SPM.
- Version the SDK with semantic versioning: `MAJOR.MINOR.PATCH`.
- Replace placeholder `homepage`, `source`, and `author` metadata in the
  production podspec.
- Maintain a changelog for each binary SDK update.

Clarify these points before production use:

- Whether the SDK can be redistributed inside customer apps.
- Licenses for GPUPixel, MNN, and mars-face-kit.
- Binary size impact.
- Minimum iOS version.
- Supported device architectures.
- Expected simulator support level.

## Resource and Model Management

The current pod copies `res/` and `models/` into the main bundle. For production,
consider:

- Namespacing resources to avoid collisions with the host app.
- Validating required resources at initialization and reporting clear errors.
- Adding checksums or versions for model files.
- Supporting resource loading from a framework bundle if the SDK is packaged
  separately.

A safer production layout:

```text
BeautyFilterResources.bundle
├── res/
└── models/
```

The native core should accept an explicit `NSBundle` or resource root instead of
assuming the main bundle.

## Camera Lifecycle Hardening

The demo currently starts and stops camera capture based on `didMoveToWindow`.
Production integration should add:

- Pause/resume APIs for app background and foreground transitions.
- Handling for `AVCaptureSession` interruptions.
- Permission-denied reporting through callbacks.
- Optional front/back camera selection if the product needs it.
- Explicit GPU/native resource cleanup when the view disappears.
- State reporting: `starting`, `running`, `stopped`, `failed`.

## Performance and Stability

Areas the iOS team should review:

- Thread safety when filter parameters update while camera frames are being
  processed.
- Coalescing parameter updates for camera mode when sliders emit many changes.
- Reducing allocations inside `captureOutput`.
- Benchmarking FPS on older target devices.
- Memory peaks when decoding large images.
- Input image size limits or pre-processing downscale.
- Structured logging that can be disabled in release builds.
- Error objects instead of `NSLog`-only reporting.

Metrics to measure:

- Pipeline initialization time.
- Face detection time per frame.
- Camera FPS with beauty enabled.
- Memory peak for 12MP/24MP images.
- App size increase from the SDK.

## Parameter API Standardization

The demo uses a public UI range of `0-10`, then scales values down for the engine.

Production API should define:

- Public range: `0-1`, `0-10`, or `0-100`.
- Default values for each use case.
- Native-side min/max clamping.
- Preset versioning, so old presets do not silently change behavior when the SDK
  is updated.

Current mapping:

| Public prop | Demo range | Engine value |
| --- | ---: | --- |
| `smoothing` | 0-10 | `value / 10` |
| `whitening` | 0-10 | `value / 20` |
| `faceSlim` | 0-10 | `value / 200` |
| `eyeEnlarge` | 0-10 | `value / 100` |
| `blusher` | 0-10 | `value / 10` |

## Test Plan for an iOS Team

Recommended minimum test groups:

- Simulator build succeeds.
- Device build succeeds.
- App launch does not crash without camera permission.
- Image processor handles images with different EXIF orientations.
- Very large images do not exceed the accepted memory threshold.
- Camera can start and stop repeatedly without leaking sessions or views.
- App background/foreground while camera is running.
- Permission denied/restricted states.
- Device has no front camera or the camera is already in use.
- Visual regression checks with a baseline image set.

## Production Handoff Checklist

- Production podspec has correct metadata, license, and version.
- Binary SDK is stripped or symbol-managed according to release requirements.
- Resources and models are packaged with a dedicated namespace.
- Public API documentation exists for both iOS and React Native.
- Error handling exists beyond `NSLog`.
- A native iOS sample app exists if the iOS team does not use React Native.
- Benchmarks are available for target devices.
- Binary SDK update policy and changelog are defined.
- Troubleshooting guide covers framework/static-library linking issues.

## Suggested Upgrade Roadmap

1. Onboarding: keep the current demo and add build/docs checklists.
2. Stabilization: define the public API, clamp params, add event callbacks and
   error handling.
3. Native SDK layer: create `BeautyFilterCore` independent from React Native.
4. Packaging: create a production CocoaPod/SPM package and resource bundle.
5. Production hardening: add benchmark, memory/FPS tests, lifecycle tests, and
   visual regression checks.
