# AI Beauty Filter - React Native Demo (iOS)

This is a React Native demo app for the **AI Beauty Filter SDK** (GPUPixel). The
app lets users pick an image from the photo library, process it through a
GPU-based beauty pipeline, and adjust the result live with sliders. Supported
effects include skin smoothing, whitening, face slimming, eye enlargement, and
blush. A real-time **Camera** mode is also available on physical iPhones.

*You need to read the [docs/getting-started/usage-guide.md](docs/getting-started/usage-guide.md) first before you start.*

## Detailed Documentation

In addition to the quick guide in this README, the full documentation set lives
in `docs/` (start from [docs/README.md](docs/README.md)):

- [docs/getting-started/system-overview.md](docs/getting-started/system-overview.md):
  system architecture, source-code layout, image/camera flow, and how the native
  SDK is linked into the app.
- [docs/getting-started/usage-guide.md](docs/getting-started/usage-guide.md):
  bootstrap steps, simulator/device runs, component usage, and common
  troubleshooting.
- [docs/getting-started/build-guide.md](docs/getting-started/build-guide.md):
  toolchain, dependencies, the podspec, and the device/simulator slice split.
- [docs/reference/](docs/reference/): deep reference on the architecture, the
  RN ↔ ObjC++ bridge, the filter pipeline, face landmarks, assets, and how to
  extend filters.
- [docs/integration/ios-integration-guide.md](docs/integration/ios-integration-guide.md):
  deeper integration guidance for an iOS team, production packaging, native APIs,
  lifecycle, performance, and test plan.

## Directory Layout

```text
source_ios/                       # repository root; run ./bootstrap.sh here
├── bootstrap.sh                  # creates RN app, overlays demo, links SDK, runs pod install
├── BeautyFilterSDK.podspec       # local CocoaPod: native bridge + xcframework + resources
├── native-bridge/                # ObjC++ bridge compiled by the pod; RN <-> C++ engine
│   ├── GPUPixelImageView.{h,mm}        # static image processing view
│   ├── GPUPixelImageViewManager.{h,mm} # registers <GPUPixelImageView/>
│   ├── GPUPixelCameraView.{h,mm}       # real-time camera view
│   ├── GPUPixelViewManager.{h,mm}      # registers <GPUPixelCameraView/>
│   └── GPUPixelModule.{h,mm}           # native module for camera control
├── prebuilt-sdk/                 # prebuilt native SDK; required by the pod
│   └── ios/
│       ├── BeautyFilter.xcframework    # beauty engine, device + simulator slices
│       ├── MNN.framework               # AI face-detection engine, device slice only
│       ├── include/                    # gpupixel/* headers
│       ├── res/                        # lookup tables copied into the app bundle
│       └── models/                     # face-detection models
├── app-template/                 # files overlaid into the generated app
│   ├── App.tsx                   # demo UI: image picker, sliders, mode toggle
│   └── src/index.ts              # JS/TS wrappers for the native components
└── BeautyFilterDemo/             # generated RN app; can be recreated by bootstrap
```

All native code is delivered to the app through the local CocoaPod
`BeautyFilterSDK`, so the generated Xcode project does not need manual editing.
The podspec and Podfile use relative paths instead of machine-specific absolute
paths.

## Requirements

- macOS and Xcode.
- Node.js 18 or newer, npm.
- CocoaPods (`brew install cocoapods`).
- The prebuilt SDK under `prebuilt-sdk/ios/`.
- For full feature testing: a physical arm64 iPhone and an Apple ID/team for
  signing.

## Quick Start

Debug builds load JavaScript from **Metro**. Start Metro in a separate terminal
and keep it running:

```bash
cd source_ios/BeautyFilterDemo
npx react-native start
```

If the app reports `Could not connect to localhost:8081` or
`No script URL provided`, Metro is not running. Start Metro and reload the app.

### Option 1 - Simulator

```bash
cd source_ios/BeautyFilterDemo
npx react-native run-ios --simulator "iPhone 17"
```

Simulator builds support static-image smoothing and whitening. Face detection is
disabled on simulator.

### Option 2 - Physical iPhone

```bash
open source_ios/BeautyFilterDemo/ios/BeautyFilterDemo.xcworkspace
```

In Xcode:

1. Select the **BeautyFilterDemo** target and open **Signing & Capabilities**.
2. Select a development team.
3. Connect an iPhone, select it as the run destination, then press **Run**.
4. On first install, trust the developer profile on the iPhone if iOS requires it.

Device builds link `libmars-face-kit.a`, the PixPark face-landmark SDK, from
`prebuilt-sdk/ios/libmars-face-kit.a`. The podspec links it only for device
builds with `-l"mars-face-kit"` and `[sdk=iphoneos*]`. This library provides the
111-point landmarks used by face slimming, eye enlargement, and blush. Simulator
builds do not use it.

In the app:

1. Select the image tab, tap the image picker button, and choose a portrait image.
2. Adjust the sliders for smoothing, whitening, face slimming, eye enlargement,
   and blush. The preview updates immediately.
3. Switch to the camera tab to test real-time beauty filtering with the front
   camera on a physical iPhone.

## Simulator vs Physical Device

`BeautyFilter.xcframework` contains both device and simulator slices.

| Capability | Simulator arm64 | Physical iPhone arm64 |
| --- | --- | --- |
| Face detection (MNN + mars) | No | Yes |
| Skin smoothing / whitening | Yes | Yes |
| Face slimming / eye enlargement / blush | No, requires landmarks | Yes |
| Code signing required | No | Yes |

Reason: `MNN.framework` and `libmars-face-kit.a` currently provide device slices
only. The simulator slice is built with face detection disabled
(`GPUPIXEL_ENABLE_FACE_DETECTOR=OFF`) so simulator builds can still run static
image flows and non-landmark filters. Device builds enable
`GPUPIXEL_ENABLE_FACE_DETECTOR=1` and link MNN/mars only for `[sdk=iphoneos*]`;
CocoaPods selects the right configuration for each SDK.

## Moving to a New Machine

`BeautyFilterDemo/`, `node_modules`, and `Pods` are reproducible and do not need
to be copied. On a new machine:

1. Copy the `source_ios/` directory. You may omit `BeautyFilterDemo/` to keep the
   copy smaller.
2. Run `cd source_ios && ./bootstrap.sh`. The script recreates the app and links
   the local pod using relative paths.
3. Start Metro in a second terminal as described in the quick-start section.

`prebuilt-sdk/ios/` must be kept because it contains the binary SDK, resources,
models, and `libmars-face-kit.a`.

## What `bootstrap.sh` Does

1. Runs `npx @react-native-community/cli init BeautyFilterDemo`.
2. Runs `npm install` and adds `@react-native-community/slider` plus
   `react-native-image-picker`.
3. Copies `app-template/App.tsx` and `app-template/src/` into the app.
4. Adds `pod 'BeautyFilterSDK', :path => '../..'` to `ios/Podfile`.
5. Adds `NSPhotoLibraryUsageDescription` and `NSCameraUsageDescription` to
   `Info.plist`.
6. Runs `pod install`. The script passes `RCT_NEW_ARCH_ENABLED=0`, but newer
   React Native versions may still run New Architecture; the legacy
   `RCTViewManager` bridge works through interop.

## Technical Notes

- **Resources**: the podspec copies `prebuilt-sdk/ios/res` and
  `prebuilt-sdk/ios/models` into the app bundle. Native code calls
  `GPUPixel::SetResourceRoot([[NSBundle mainBundle] resourcePath])`, so
  `GetResourcePath("res/...")` and model lookups resolve correctly at runtime.
- **MNN.framework** is a static framework archive. It is linked for device builds
  and does not need to be embedded or signed separately.
- **Face detection libraries** are device-only. Device builds link
  `libmars-face-kit.a` and `MNN.framework`; simulator builds disable face
  detection and do not require them.
- **Pipeline**:
  `SourceRawData -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter -> SinkView`.
  Static-image mode decodes the input image to RGBA, runs `FaceDetector` in
  picture mode on device, and pushes one frame into the pipeline.
- **New Architecture**: the legacy `RCTViewManager` and `requireNativeComponent`
  bridge runs through React Native's interop layer.
- **Prebuilt SDK**: the SDK lives in `prebuilt-sdk/ios/` and is not rebuilt in
  this repository. If the C++ SDK source changes, rebuild it with the SDK's
  original toolchain and replace the contents under `prebuilt-sdk/ios/`.
