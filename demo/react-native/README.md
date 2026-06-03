# AI Beauty Filter — React Native demo (iOS)

Ứng dụng React Native demo cho **AI Beauty Filter SDK** (GPUPixel). Chọn một ảnh
từ thư viện, chạy qua pipeline làm đẹp trên GPU (làm mịn da, trắng da, thon mặt,
to mắt, má hồng — có nhận diện khuôn mặt) và xem kết quả thay đổi tức thì khi kéo
các thanh trượt. Có sẵn chế độ **Camera** real-time (chạy trên thiết bị thật).

## Cấu trúc thư mục

```
demo/react-native/
├── bootstrap.sh                # sinh app RN + link SDK + pod install (chạy cái này)
├── BeautyFilterSDK.podspec     # local pod: bridge native + xcframework + resources
├── sdk-ios/                    # mã native ObjC++ (được pod biên dịch)
│   ├── GPUPixelImageView.{h,mm}        # view xử lý ảnh tĩnh
│   ├── GPUPixelImageViewManager.{h,mm} # đăng ký <GPUPixelImageView/>
│   ├── GPUPixelCameraView.{h,mm}       # view camera real-time
│   ├── GPUPixelViewManager.{h,mm}      # đăng ký <GPUPixelCameraView/>
│   └── GPUPixelModule.{h,mm}           # native module điều khiển camera
├── app-template/               # file được overlay vào app sau khi sinh
│   ├── App.tsx                 # màn hình demo (image picker + sliders + toggle)
│   └── src/index.ts            # JS/TS wrappers cho 2 component native
└── BeautyFilterDemo/           # (sinh ra bởi bootstrap, đã .gitignore)
```

Mọi mã native được giao cho app **qua CocoaPod** (`BeautyFilterSDK`), nên project
Xcode sinh tự động không cần chỉnh tay.

## Hỗ trợ Simulator vs Thiết bị thật

`BeautyFilter.xcframework` chứa 2 slice trong cùng 1 framework:

| | Simulator (arm64) | iPhone thật (arm64) |
|---|---|---|
| Nhận diện khuôn mặt (MNN + mars) | ❌ tắt | ✅ bật |
| Làm mịn da / Trắng da | ✅ | ✅ |
| Thon mặt / To mắt / Má hồng | ❌ (cần landmark) | ✅ |
| Cần ký (signing) | ❌ | ✅ (Apple ID) |

Lý do: `MNN.framework` và `libmars-face-kit.a` chỉ có **lát device** (không có lát
simulator). Vì vậy **slice simulator được build với face detection TẮT**
(`GPUPIXEL_ENABLE_FACE_DETECTOR=OFF`) để chạy độc lập trên Simulator — đủ để test
luồng app + làm mịn/trắng da bằng ảnh đầu vào. Slice device giữ đầy đủ tính năng.
CocoaPods tự chọn đúng slice theo SDK đang build.

## Yêu cầu

- macOS + Xcode (đã thử với Xcode 26.5)
- Node.js ≥ 18, npm
- CocoaPods (`brew install cocoapods`)
- Để chạy đầy đủ tính năng: một iPhone thật (arm64) + Apple ID để ký
- Đã build SDK: `output/ios/BeautyFilter.xcframework` tồn tại
  (nếu chưa hoặc vừa sửa source: chạy `./script/build_ios.sh` ở gốc repo)

## Chạy nhanh

```bash
cd demo/react-native
./bootstrap.sh                 # sinh app, cài deps, link pod, pod install
```

### Cách 1 — Simulator (nhanh, không cần iPhone/signing)

```bash
cd BeautyFilterDemo
npx react-native run-ios --simulator "iPhone 17"
```

Chạy được làm mịn + trắng da bằng ảnh đầu vào (face detection tắt trên simulator).

### Cách 2 — iPhone thật (đầy đủ tính năng)

Mở bằng Xcode:

```bash
open BeautyFilterDemo/ios/BeautyFilterDemo.xcworkspace
```

Trong Xcode:
1. Chọn target **BeautyFilterDemo** → tab **Signing & Capabilities** → chọn **Team**
   của bạn (để ký lên thiết bị).
2. Cắm iPhone, chọn nó làm run destination, bấm **Run** (▶).
3. Lần đầu cần **Trust** developer trên iPhone: Settings → General → VPN & Device
   Management.

Hoặc dùng CLI (cần đã cấu hình signing):

```bash
cd BeautyFilterDemo
npx react-native run-ios --device "Tên-iPhone-của-bạn"
```

Trong app:
1. Để ở tab **Ảnh**, bấm **Chọn ảnh** và chọn một ảnh chân dung.
2. Kéo các thanh trượt (Làm mịn / Trắng da / Thon mặt / To mắt / Má hồng) — ảnh
   render lại theo thời gian thực.
3. Chuyển tab **Camera** để thử beauty filter real-time qua camera trước.

## bootstrap.sh làm gì

1. `npx @react-native-community/cli init BeautyFilterDemo` — sinh app RN.
2. `npm install` + thêm `@react-native-community/slider`, `react-native-image-picker`.
3. Copy `app-template/App.tsx` và `app-template/src/` vào app.
4. Thêm `pod 'BeautyFilterSDK', :path => '../../../..'` vào `ios/Podfile`
   (podspec nằm ở gốc repo).
5. Thêm quyền `NSPhotoLibraryUsageDescription` + `NSCameraUsageDescription` vào Info.plist.
6. `pod install` (bootstrap có truyền `RCT_NEW_ARCH_ENABLED=0` nhưng RN 0.82+ bỏ
   qua — app chạy New Architecture; bridge `RCTViewManager` hoạt động qua interop).

Script idempotent: chạy lại sẽ chỉ cập nhật overlay + pod, không sinh lại app.

## Ghi chú kỹ thuật

- **Resources**: podspec copy `output/ios/res` và `output/ios/models` vào bundle app.
  Native gọi `GPUPixel::SetResourceRoot([[NSBundle mainBundle] resourcePath])` nên
  `GetResourcePath("res/…")` và `("models")` phân giải đúng khi chạy.
- **MNN.framework** là static framework (ar archive) → được link, không cần embed/sign.
- **Face detection libs (device-only)**: cho slice **device**, pod link thêm
  `libmars-face-kit.a` + framework `MNN`, qua key `[sdk=iphoneos*]` trong podspec.
  Slice **simulator** build với `GPUPIXEL_ENABLE_FACE_DETECTOR=OFF` nên không cần
  chúng. `CoreML`/`Metal` là system framework (có ở cả 2 SDK).
- **Sửa SDK** (`src/`): `src/CMakeLists.txt` trước đây snapshot danh sách nguồn trước
  khi append `sink_view.mm`/`objc_view.mm` → `SinkView` (render ra UIView) bị thiếu.
  Đã sửa; `objc_view.mm` cũng được vá macro `GL_CALL` (đã bị gỡ khỏi codebase). Và
  `script/build_ios.sh` giờ build slice simulator với face detection TẮT. Sau khi
  sửa source cần chạy lại `./script/build_ios.sh`.
- **Pipeline**: `SourceRawData → BlusherFilter → FaceReshapeFilter → BeautyFaceFilter → SinkView`,
  giống `demo/ios/ios_beauty.mm`. Mỗi lần đổi ảnh/tham số, view decode ảnh sang RGBA,
  (trên device) chạy `FaceDetector` (chế độ PICTURE) rồi đẩy 1 frame vào pipeline;
  `SinkView` render lên view.
- **New Architecture**: bridge legacy `RCTViewManager` + `requireNativeComponent`
  chạy qua interop layer của New Arch (mặc định ở RN 0.85).
