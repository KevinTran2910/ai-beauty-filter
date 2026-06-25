# 1. Giới thiệu sơ bộ bài toán

DAYO Beauty Filter (iOS) là một ứng dụng iOS làm đẹp khuôn mặt theo thời gian
thực trên luồng camera trực tiếp của iPhone. Ứng dụng lấy từng khung hình từ
camera trước, áp dụng một chuỗi bộ lọc làm đẹp (làm mịn da, làm trắng, thon mặt,
to mắt, má hồng) và hiển thị kết quả ngay lập tức lên màn hình với tốc độ khoảng
30 FPS. Ngoài chế độ camera, ứng dụng còn xử lý ảnh tĩnh chọn từ thư viện ảnh.

Điểm cốt lõi của giải pháp là toàn bộ phần xử lý ảnh nặng được đẩy xuống GPU
thông qua thư viện C++/OpenGL ES (GPUPixel), thay vì xử lý từng điểm ảnh trên
JavaScript. React Native chỉ đóng vai trò điều phối giao diện và tham số; một lớp
cầu nối ObjC++ mỏng quản lý camera (AVFoundation), giải mã ảnh và vòng đời view.

## Ba thách thức chính của bài toán làm đẹp realtime

- Tốc độ: mỗi giây khoảng 30 khung hình, mỗi khung 720p chứa hàng trăm nghìn điểm
  ảnh cần xử lý.
- Độ trễ: hình ảnh phải hiển thị gần như tức thì, không được giật hay trễ khung.
- Hiệu ứng bám khuôn mặt: thon mặt, to mắt và má hồng cần biết chính xác vị trí
  mắt, miệng, viền mặt trong từng khung hình.

Giải pháp: đẩy toàn bộ xử lý ảnh xuống GPU (GPUPixel) và dùng AI nhận diện điểm
mốc khuôn mặt — face landmarks (mars-face-kit) — để điều khiển các hiệu ứng bám
theo gương mặt.

## Phạm vi tính năng

- Camera realtime với đường preview native, dùng camera trước; tự bật khi view
  xuất hiện và tự tắt khi view bị gỡ — không cần JS gọi lệnh.
- Chọn ảnh từ thư viện và xử lý qua đường RGBA tĩnh (chạy được cả trên simulator).
- 5 thanh trượt điều chỉnh trực tiếp: làm mịn, làm trắng, thon mặt, to mắt, má hồng.
- Điều khiển hoàn toàn theo cơ chế prop-driven: đổi prop là render lại.
- Cả hai chế độ render thẳng ra `SinkView` trên màn hình, không đọc ngược ảnh về
  CPU (no readback).

> Lưu ý phạm vi: bản demo iOS chỉ hiển thị kết quả, không có chức năng chụp/lưu
> JPEG hay overlay thống kê hiệu năng. Camera chỉ chạy trên thiết bị thật.

# 2. Các tech stack sử dụng trong dự án

Dự án là kiến trúc lai ba lớp: React Native/TypeScript điều phối, lớp cầu nối
ObjC++ nối JS với engine, và lớp xử lý ảnh C++/OpenGL ES thực hiện phần tính toán
nặng. Bảng dưới liệt kê công nghệ theo từng lớp.

| Lớp | Công nghệ đang dùng |
| --- | --- |
| App / UI | React Native + TypeScript, `@react-native-community/slider`, `react-native-image-picker` |
| Cầu nối native | ObjC++ `RCTViewManager` (prop-driven), `requireNativeComponent` |
| Camera | `AVCaptureSession`, camera trước, định dạng BGRA (`kCVPixelFormatType_32BGRA`), preset `1280x720` |
| Pipeline ảnh | C++17, GPUPixel, OpenGL ES, đóng gói trong `BeautyFilter.xcframework` |
| Nhận diện mặt | `MNN.framework` + `libmars-face-kit.a` (chỉ slice device) |
| Resource | CocoaPods copy `res/` + `models/` vào app bundle (lúc build) |
| Build | CocoaPods + `bootstrap.sh` (sinh app React Native) |
| Thiết bị | iOS 15.1+, arm64 (device + arm64-simulator) |

## Cấu hình podspec (device/simulator)

iOS không build C++ engine bằng CMake — engine là prebuilt trong
`BeautyFilter.xcframework`. `BeautyFilterSDK.podspec` tách cấu hình theo SDK đang
build: face detector chỉ bật trên device.

| Cấu hình | Phạm vi | Ý nghĩa |
| --- | --- | --- |
| `GPUPIXEL_ENABLE_FACE_DETECTOR=1` | `iphoneos` | Bật khối `#ifdef` face detector trong bridge |
| `-framework MNN -framework CoreML -framework Metal` | `iphoneos` | Link backend MNN cho nhận diện mặt |
| `-l"mars-face-kit"` | `iphoneos` | Link thư viện static landmark |
| (không định nghĩa macro, không link MNN/mars) | simulator | Detector tắt — chỉ còn làm mịn/làm trắng |
| `CLANG_CXX_LANGUAGE_STANDARD=c++17`, `CLANG_CXX_LIBRARY=libc++` | mọi slice | Chuẩn biên dịch C++ |

# 3. Kiến trúc tổng quan các layer xử lý

Hệ thống chia thành ba lớp với ranh giới rõ ràng. React Native quản lý giao diện
và tham số; ObjC++ quản lý camera, giải mã ảnh và vòng đời view; lớp C++ làm phần
nặng về tính toán điểm ảnh và render trên GPU.

## Trách nhiệm từng lớp

Lớp React Native (TypeScript) — `App.tsx`, `src/index.ts`

- Toggle hai chế độ `image` / `camera`, image picker, 5 slider `0..10`.
- Khai báo hai native component bằng `requireNativeComponent`: `GPUPixelImageView`
  và `GPUPixelCameraView`.
- Truyền toàn bộ tham số xuống native qua props.

Lớp cầu nối ObjC++ — `native-bridge/*.mm`

- `RCTViewManager` export view + props; mỗi prop ánh xạ tới một setter trên view.
- Quản lý `AVCaptureSession` (camera), giải mã `UIImage` → RGBA, vòng đời view.
- Giữ pipeline GPUPixel cục bộ theo từng view bằng `std::shared_ptr`.

Lớp engine C++ — `BeautyFilter.xcframework`

- Filter graph GPUPixel, render OpenGL ES qua `SinkView` (CAEAGLLayer trên UIView).
- Wrapper face detector (MNN + mars-face-kit), chỉ có trên slice device.
- Là prebuilt binary; repo iOS không build lại phần C++.

## Sơ đồ luồng dữ liệu tổng thể

```
┌──────────────── React Native (TypeScript) ────────────────┐
│  App.tsx  →  props (smoothing, whitening, faceSlim, ...)   │
└───────────────────────────────┬───────────────────────────┘
                                 │  RCTViewManager (prop-driven)
┌────────────────────────────────▼──────────────────────────┐
│              ObjC++ Bridge (native-bridge/*.mm)            │
│   GPUPixelCameraView / GPUPixelImageView                  │
└────────────────────────────────┬──────────────────────────┘
                                 │  shared_ptr<...> (C++)
┌────────────────────────────────▼──────────────────────────┐
│          C++ / OpenGL ES — GPUPixel (xcframework)         │
│  SourceRawData → Blusher → Reshape → BeautyFace → SinkView│
└───────────────────────────────────────────────────────────┘
```

React Native lo: UI, slider, image picker, truyền tham số qua props. ObjC++ lo:
camera (AVFoundation), giải mã ảnh, vòng đời view. C++/GPU lo: chuyển màu, các bộ
lọc làm đẹp, biến dạng khuôn mặt và render.

## Cấu hình filter graph native

Khi bật face detector (build device — `GPUPIXEL_ENABLE_FACE_DETECTOR=1`):

```
SourceRawData → BlusherFilter → FaceReshapeFilter → BeautyFaceFilter → SinkView
```

Khi tắt face detector (build simulator), `BlusherFilter` và `FaceReshapeFilter`
vẫn nằm trong graph nhưng pass-through do thiếu landmarks; chỉ làm mịn/làm trắng
của `BeautyFaceFilter` có hiệu lực:

```
SourceRawData → (Blusher pass-through) → (Reshape pass-through) → BeautyFaceFilter → SinkView
```

## Hợp đồng đa luồng (Threading contract)

- Frame camera chạy trên một serial queue riêng `com.gpupixel.camera` (đặt qua
  `setSampleBufferDelegate:queue:`).
- Reprocess ảnh tĩnh được schedule qua `dispatch_async(main_queue)` và gộp nhiều
  thay đổi props thành đúng một lần render ở cuối runloop.
- Các setter tham số có thể gọi từ thread bất kỳ (RN UIManager thread); chỉ được
  bảo vệ bằng null-check như `if (_beauty) ...`.
- Mỗi view giữ pipeline riêng nên không có khóa toàn cục kiểu global. Cần lưu ý
  thread safety khi slider bắn nhiều thay đổi trong lúc camera đang xử lý frame.

# 4. Luồng xử lý cho một khung hình

Có hai đường xử lý dùng chung một pipeline GPU. Khác với một số nền tảng có chức
năng chụp/lưu, demo iOS không đọc ngược kết quả về CPU — cả hai đường đều render
thẳng ra `SinkView` trên màn hình.

## Đường Preview (realtime)

- `AVCaptureSession` trả frame BGRA từ camera trước (đã được AVFoundation xoay và
  lật sẵn).
- `captureOutput:didOutputSampleBuffer:` nhận `CMSampleBufferRef` trên serial
  queue; lock `CVImageBufferRef`, lấy `baseAddress`, `width`, `height`, `stride`.
- Trên device: chạy `FaceDetector::Detect(BGRA, VIDEO)` để cập nhật landmarks,
  rồi push vào `BlusherFilter` và `FaceReshapeFilter`.
- Đẩy con trỏ pixel BGRA thẳng vào `SourceRawData::ProcessData(..., BGRA)`; shader
  chuyển BGRA→RGB trên GPU.
- Filter graph render rồi xuất thẳng ra `SinkView`. Không có `glReadPixels` trong
  đường này.

> Điểm mấu chốt: đường preview không readback về JS/CPU mỗi khung, nên rất nhanh.

## Xoay và lật ảnh

iOS để AVFoundation lo việc xoay/lật cho camera, và bake EXIF khi giải mã ảnh tĩnh.

| Nguồn | Cách xử lý hướng |
| --- | --- |
| Camera | `connection.videoOrientation = Portrait`, `connection.videoMirrored = YES` (selfie mirror); frame tới delegate đã đúng chiều → `SourceRawData` dùng `NoRotation` |
| Ảnh tĩnh | Bake hướng EXIF lúc giải mã: lật trục Y trong `CGBitmapContext` (`TranslateCTM(0,h)` + `ScaleCTM(1,-1)`) trước khi `drawInRect` |

## So sánh hai đường xử lý

| Tiêu chí | Camera (live) | Ảnh tĩnh |
| --- | --- | --- |
| View | `GPUPixelCameraView` | `GPUPixelImageView` |
| Đầu vào | BGRA từ `AVCaptureSession` | RGBA từ giải mã `UIImage` |
| Mode nhận diện | `VIDEO` | `PICTURE` |
| Kích hoạt | Tự bật khi view xuất hiện | Reprocess khi đổi props |
| Đầu ra | `SinkView` (live) | `SinkView` (1 lần / runloop) |
| Readback về CPU | Không | Không |

# 5. Chuỗi bộ lọc (Filter pipeline)

Pipeline native là một directed graph dạng `Source → Filter → … → Sink`. Mỗi
filter nhận texture từ node trước, render shader ra framebuffer mới, rồi đẩy sang
node tiếp theo. Bộ lọc cuối (terminal) luôn là `BeautyFaceFilter`, theo sau là
`SinkView`. Graph được wire giống hệt nhau trong cả `GPUPixelImageView.mm` và
`GPUPixelCameraView.mm`.

## Sơ đồ chuỗi bộ lọc

```
SourceRawData (BGRA/RGBA → RGB)
   → BlusherFilter        (má hồng, theo landmark)
   → FaceReshapeFilter    (thon mặt + to mắt, warp mesh theo landmark)
   → BeautyFaceFilter     (làm mịn da + làm trắng)
   → SinkView (render lên màn hình)
```

| Hiệu ứng | Bộ lọc | Cách hoạt động |
| --- | --- | --- |
| Làm mịn da | `BeautyFaceFilter` | Bilateral blur giữ cạnh + high-pass detail, blend theo vùng da. Không cần landmark. |
| Làm trắng | `BeautyFaceUnitFilter` (LUT) | Chuỗi bảng tra màu `lookup_gray/origin/skin/light.png`, blend theo uniform `whiten`. |
| Thon mặt | `FaceReshapeFilter` | Nhiều phép `curveWarp` kéo vùng contour vào trong. Cần landmark. |
| To mắt | `FaceReshapeFilter` | `enlargeEye` phóng vùng quanh 2 tâm mắt. Cần landmark. |
| Má hồng | `BlusherFilter` | Phủ texture `blusher.png` theo triangle mesh. Cần landmark. |

## Bên trong BeautyFaceFilter (FilterGroup)

`BeautyFaceFilter` là một `FilterGroup` nội bộ. Nó tạo song song một nhánh làm mờ
giữ cạnh và một nhánh bản đồ chi tiết, rồi trộn lại trong `BeautyFaceUnitFilter`.

```
input
  ├─ BilateralFilter ────────────┐
  ├─ BoxHighPassFilter ──────────┼─→ BeautyFaceUnitFilter → output
  └─ original ───────────────────┘
```

- `BilateralFilter`: hai pass ngang/dọc, làm mờ giữ cạnh theo khoảng cách màu.
- `BoxHighPassFilter`: tạo bản đồ chi tiết/variance từ `original − boxblur(original)`
  để quyết định vùng nào nên làm mịn.
- `BeautyFaceUnitFilter`: nhận 3 texture (gốc, đã bilateral, high-pass), làm mịn da
  theo heuristic da/cạnh và làm trắng bằng LUT. Bridge ánh xạ
  `SetBlurAlpha(smoothing/10)` và `SetWhite(whitening/20)`.

## Vai trò của face landmarks

mars-face-kit nhận frame và trả về vector keypoints chuẩn hóa `[0,1]` (gốc tọa độ
góc trên-trái). Bridge ObjC++ gọi detector trực tiếp trong từng view rồi push
landmarks vào `BlusherFilter` và `FaceReshapeFilter`; `BeautyFaceFilter` không cần
landmark.

- `FaceReshapeFilter` cần tối thiểu khoảng 106 điểm; `FaceMakeupFilter` (cơ sở của
  Blusher) dùng mesh 111 điểm.
- Nếu không phát hiện được mặt hoặc landmark không đủ, các filter này pass-through
  — chỉ còn làm mịn + làm trắng vẫn hoạt động.
- Toàn bộ phần detect nằm trong `#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR`, nên
  simulator (không define macro) không có landmark.

# 6. Các phần đã tối ưu cho hiệu năng

Mục tiêu là đạt HD ~30 FPS trên iPhone. Các tối ưu chính tập trung vào việc loại
bỏ sao chép bộ nhớ thừa, đưa công việc nặng lên GPU và render thẳng ra màn hình.

| Kỹ thuật tối ưu | Mô tả |
| --- | --- |
| Không readback | Cả camera lẫn ảnh đều render thẳng ra `SinkView` (CAEAGLLayer), không `glReadPixels`. |
| BGRA thẳng lên GPU | Camera ra sẵn BGRA; chuyển màu BGRA→RGB trong shader, tránh bước CPU. |
| Xoay/lật bằng AVFoundation | Dùng `videoOrientation` + `videoMirrored`, không xoay ảnh bằng CPU. |
| Bỏ khung trễ | `alwaysDiscardsLateVideoFrames = YES` loại bỏ frame trễ khi pipeline đang bận. |
| Coalesce reprocess (ảnh tĩnh) | Gộp nhiều thay đổi slider thành 1 lần render ở cuối runloop. |
| Pipeline per-view | Mỗi view có graph riêng, không tranh chấp global GL state. |

> Phát hiện quan trọng: hiện tại face detect chạy mỗi khung trên frame full-res,
> chưa throttle và chưa downscale. Đây là ứng viên tối ưu FPS đầu tiên (ví dụ:
> detect cách khung và thu nhỏ ảnh trước khi detect).

# 7. Khả năng tích hợp cho bộ phận phát triển app iOS

Năng lực được expose qua hai native component React Native — `GPUPixelImageView`
và `GPUPixelCameraView` — theo cơ chế prop-driven. Đội phát triển app chỉ cần
render view và truyền props; không cần biết chi tiết C++/OpenGL bên dưới. Ngoài ra
còn một native module cũ `GPUPixelModule` (imperative) nhưng không khuyến nghị
dùng với New Architecture.

## Bề mặt bridge

| Thành phần | File | Vai trò |
| --- | --- | --- |
| `GPUPixelImageView` | `GPUPixelImageView.mm` | View xử lý ảnh tĩnh |
| `GPUPixelImageViewManager` | `GPUPixelImageViewManager.mm` | Export view + props ra RN |
| `GPUPixelCameraView` | `GPUPixelCameraView.mm` | View camera realtime |
| `GPUPixelViewManager` | `GPUPixelViewManager.mm` | Export view + props ra RN |
| `GPUPixelModule` | `GPUPixelModule.mm` | Native module imperative (legacy) qua `viewRegistry` |

## Các setter tham số (scale UI → native)

5 slider được ánh xạ về tham số native theo bảng dưới. Các hiệu ứng
reshape/blusher chỉ có tác dụng khi có landmark hợp lệ (device).

| Tham số | Range UI | Ánh xạ native | Đích |
| --- | --- | --- | --- |
| `smoothing` | 0..10 | `smoothing / 10.0` | `BeautyFaceFilter::SetBlurAlpha` |
| `whitening` | 0..10 | `whitening / 20.0` | `BeautyFaceFilter::SetWhite` |
| `faceSlim` | 0..10 | `faceSlim / 200.0` | `FaceReshapeFilter::SetFaceSlimLevel` |
| `eyeEnlarge` | 0..10 | `eyeEnlarge / 100.0` | `FaceReshapeFilter::SetEyeZoomLevel` |
| `blusher` | 0..10 | `blusher / 10.0` | `BlusherFilter::SetBlendLevel` |

## Các bước tích hợp điển hình

- Chạy `./bootstrap.sh` để sinh app React Native, link local pod `BeautyFilterSDK`
  và `pod install`.
- Render `GPUPixelImageView` (truyền `imageUri` + 5 tham số) cho chế độ ảnh, hoặc
  `GPUPixelCameraView` (5 tham số) cho chế độ camera.
- Dùng `react-native-image-picker` (`launchImageLibrary`) để lấy `imageUri` cho
  chế độ ảnh.
- Không cần gọi start/stop camera: `GPUPixelCameraView` tự bật/tắt theo vòng đời
  view (`didMoveToWindow`, `layoutSubviews`).
- Build device cần link `MNN.framework` + `libmars-face-kit.a` và bật
  `GPUPIXEL_ENABLE_FACE_DETECTOR=1` (podspec đã cấu hình theo slice).
- Thêm `NSCameraUsageDescription` và `NSPhotoLibraryUsageDescription` vào
  `Info.plist` (bootstrap đã thêm sẵn).

## Lưu ý quan trọng khi tích hợp

- Ưu tiên prop-driven: với New Architecture, tra view qua `RCTUIManager
  viewRegistry` (trong `GPUPixelModule`) kém ổn định và có thể trả nil — nên
  truyền tham số qua props thay vì gọi imperative.
- Resource root: native gọi `SetResourceRoot([[NSBundle mainBundle] resourcePath])`;
  CocoaPods copy `res/` và `models/` vào app bundle lúc build (không nén PNG), nên
  hai thư mục này phải ở gốc bundle.
- Nhận diện mặt chỉ chạy trên device: MNN/mars chỉ có slice `iphoneos`; trên
  simulator các hiệu ứng cần landmark (thon mặt/to mắt/má hồng) không hoạt động.
- `SinkView` cần bounds hợp lệ trước khi tạo pipeline — bridge đợi
  `layoutSubviews`/`didMoveToWindow` rồi mới khởi tạo (`!CGRectIsEmpty(self.bounds)`).
- Reset whitening: iOS luôn gọi `SetWhite(value/20.0)`, nên kéo slider whitening về
  0 sẽ reset đúng cách.
- Quyền camera: `startCamera` tự xin quyền (`AVCaptureDevice
  requestAccessForMediaType:`); khi bị từ chối, bridge log qua `NSLog` với prefix
  `[GPUPixel]`.

## Khả năng dùng chung đa nền tảng

- Lõi C++/GPUPixel và cách scale tham số (`smoothing/10`, `whitening/20`,
  `faceSlim/200`, `eyeEnlarge/100`, `blusher/10`) dùng chung giữa các nền tảng.
- Bridge ObjC++ (`GPUPixelCameraView.mm`) phản chiếu đúng C API của engine — một
  lõi xử lý, nhiều nền tảng.
- Thêm filter mới: wire trước terminal `BeautyFaceFilter` trong cả hai file bridge
  (xem `reference/extending-filters.md`).
