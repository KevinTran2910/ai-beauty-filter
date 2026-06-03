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

## ⚠️ Chỉ chạy trên thiết bị thật (iPhone arm64)

SDK dùng `MNN.framework` và `libmars-face-kit.a` cho nhận diện khuôn mặt — cả hai
**chỉ có lát device (arm64), không có lát simulator**. Do đó app **không build/chạy
trên iOS Simulator** được; phải dùng iPhone thật.

## Yêu cầu

- macOS + Xcode (đã thử với Xcode 26.5)
- Một iPhone thật (arm64) + tài khoản Apple để ký (signing)
- Node.js ≥ 18, npm
- CocoaPods (`brew install cocoapods`)
- Đã build SDK: `output/ios/BeautyFilter.xcframework` tồn tại
  (nếu chưa hoặc vừa sửa source: chạy `./script/build_ios.sh` ở gốc repo)

## Chạy nhanh

```bash
cd demo/react-native
./bootstrap.sh                 # sinh app, cài deps, link pod, pod install
```

Sau đó mở bằng Xcode và chạy lên iPhone:

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
4. Thêm `pod 'BeautyFilterSDK', :path => '../..'` vào `ios/Podfile`.
5. Thêm quyền `NSPhotoLibraryUsageDescription` + `NSCameraUsageDescription` vào Info.plist.
6. `RCT_NEW_ARCH_ENABLED=0 pod install` (demo dùng kiến trúc cũ cho ổn định với
   `RCTViewManager`).

Script idempotent: chạy lại sẽ chỉ cập nhật overlay + pod, không sinh lại app.

## Ghi chú kỹ thuật

- **Resources**: podspec copy `output/ios/res` và `output/ios/models` vào bundle app.
  Native gọi `GPUPixel::SetResourceRoot([[NSBundle mainBundle] resourcePath])` nên
  `GetResourcePath("res/…")` và `("models")` phân giải đúng khi chạy.
- **MNN.framework** là static framework (ar archive) → được link, không cần embed/sign.
- **Face detection libs**: pod link thêm `third_party/mars-face-kit/libs/ios/libmars-face-kit.a`
  + framework `CoreML`, `Metal` (MNN backend).
- **Sửa SDK**: `src/CMakeLists.txt` trước đây snapshot danh sách nguồn trước khi append
  `sink_view.mm`/`objc_view.mm` → `SinkView` (render ra UIView) bị thiếu trong thư viện.
  Đã sửa để build đúng; `objc_view.mm` cũng được vá macro `GL_CALL` (đã bị gỡ khỏi codebase).
  Sau khi sửa cần chạy lại `./script/build_ios.sh`.
- **Pipeline**: `SourceRawData → BlusherFilter → FaceReshapeFilter → BeautyFaceFilter → SinkView`,
  giống `demo/ios/ios_beauty.mm`. Mỗi lần đổi ảnh/tham số, view decode ảnh sang RGBA,
  chạy `FaceDetector` (chế độ PICTURE) rồi đẩy 1 frame vào pipeline; `SinkView` render lên view.
- **New Architecture**: nếu muốn bật Fabric, cần map component qua interop layer; demo
  này tắt New Arch cho đơn giản.
