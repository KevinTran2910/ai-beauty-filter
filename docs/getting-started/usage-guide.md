# Usage Guide

## Environment Requirements

- macOS and Xcode.
- Node.js and npm.
- CocoaPods.
- A physical iPhone if you need to test camera mode and full face-landmark
  effects.
- A complete `prebuilt-sdk/ios/` directory.

Quick check:

```bash
node --version
npm --version
pod --version
```

## Bootstrap the Demo App

From the repository root:

```bash
cd ios
./bootstrap.sh
```

The script creates or updates `BeautyFilterDemo/`, installs npm packages, copies
the JS template, links the local pod, and runs `pod install`.

If `BeautyFilterDemo/` already exists, the script skips React Native init and only
updates the overlay, dependencies, and pod setup.

## Start Metro

Debug builds need Metro to load JavaScript:

```bash
cd ios/BeautyFilterDemo
npx react-native start
```

Keep this terminal running while you run the app.

## Run on Simulator

```bash
cd ios/BeautyFilterDemo
npx react-native run-ios --simulator "iPhone 17"
```

The simulator is useful for testing:

- App boot.
- The image picker flow.
- Static-image rendering.
- Smoothing and whitening.
- The slider UI and React Native wrapper behavior.

The simulator is **not** suitable for testing:

- Camera.
- Face landmarks.
- Full face reshape, eye enlargement, or landmark-based blush.

## Run on a Physical iPhone

Open the workspace:

```bash
open ios/BeautyFilterDemo/ios/BeautyFilterDemo.xcworkspace
```

In Xcode:

1. Select the `BeautyFilterDemo` target.
2. Open `Signing & Capabilities`.
3. Select an Apple Team.
4. Connect an iPhone and choose it as the run destination.
5. Press Run.

If this is the first install from a personal developer account, the iPhone may
require you to trust the developer profile in Settings.

## Use the Demo App

### Image Mode

1. Select the Image tab.
2. Tap the image-picker button.
3. Pick a portrait image from the photo library.
4. Adjust the sliders for smoothing, whitening, face slimming, eye enlargement,
   and blush.

For static images, each `imageUri` or filter-parameter change schedules one
render pass at the end of the run loop to avoid unnecessary repeated rendering.

### Camera Mode

1. Run the app on a physical iPhone.
2. Select the Camera tab.
3. Grant camera permission when iOS asks.
4. Adjust the sliders to update the real-time effect.

The camera view starts automatically when it appears and stops automatically when
it is removed from the screen.

## Use the Native Components in React Native

The wrapper is available at `BeautyFilterDemo/src/index.ts` after bootstrap.

Static image processing:

```tsx
<GPUPixelImageView
  style={{ flex: 1 }}
  imageUri={imageUri}
  smoothing={6}
  whitening={4}
  faceSlim={3}
  eyeEnlarge={3}
  blusher={2}
/>
```

Camera:

```tsx
<GPUPixelCameraView
  style={{ flex: 1 }}
  smoothing={6}
  whitening={4}
  faceSlim={3}
  eyeEnlarge={3}
  blusher={2}
/>
```

All current filter parameters use a public `0-10` range.

## Common Issues

### `Could not connect to localhost:8081` or `No script URL provided`

Metro is not running or the app cannot connect to Metro.

```bash
cd ios/BeautyFilterDemo
npx react-native start
```

Then reload the app.

### `pod install` fails because CocoaPods is missing

```bash
brew install cocoapods
cd ios
./bootstrap.sh
```

### Camera does not appear on the simulator

This is expected. `GPUPixelCameraView` only runs camera mode on a physical iPhone.

### Face slimming, eye enlargement, or blush is not obvious on the simulator

Simulator builds do not enable face detection because the MNN/mars binaries are
device-only in this SDK package. Test landmark-dependent effects on a physical
iPhone.

### Device build fails while linking MNN or mars

Check that these files exist:

```text
prebuilt-sdk/ios/MNN.framework
prebuilt-sdk/ios/libmars-face-kit.a
```

If they are missing, restore the prebuilt SDK package.

## Moving to a New Machine

You can recreate the demo app from the source root:

```bash
cd ios
./bootstrap.sh
```

You do not need to copy `BeautyFilterDemo/node_modules` or
`BeautyFilterDemo/ios/Pods`. You must keep `prebuilt-sdk/ios/` because it contains
the native SDK binaries and resources.
