# beautyfilter-sdk (Expo local module)

Module cục bộ đóng gói BeautyFilter SDK (GPUPixel + MNN) cho app Expo.

## Thành phần

- `app.plugin.js` — Expo config plugin: chèn `pod 'BeautyFilterSDK'` vào Podfile
  và thêm quyền ảnh/camera vào Info.plist khi `expo prebuild`.
- `BeautyFilterSDK.podspec` — CocoaPod gom native bridge + xcframework + resources.
- `src/index.ts` — JS wrapper: `GPUPixelImageView`, `GPUPixelCameraView`,
  `FilterParamProps`.
- `native-bridge/`, `prebuilt-sdk/` — **không kèm trong git**; được `../../setup.sh`
  copy từ `ios/` vào đây khi dựng app (xem `expo_ios/.gitignore`).

## Dùng trong code

```tsx
import { GPUPixelImageView, GPUPixelCameraView } from 'beautyfilter-sdk';

<GPUPixelImageView imageUri={uri} smoothing={6} whitening={4} style={{ flex: 1 }} />
```

Tham số filter (`smoothing`, `whitening`, `faceSlim`, `eyeEnlarge`, `blusher`)
nằm trong khoảng 0–10, truyền qua props.

Xem hướng dẫn đầy đủ ở `../../README.md`.
