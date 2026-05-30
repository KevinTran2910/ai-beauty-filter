# AI Beauty Filter — Tài liệu Kiến trúc

> Dành cho người mới tiếp cận dự án. Tài liệu này giải thích **tại sao** mỗi công nghệ được chọn,
> **chúng hoạt động thế nào**, và **cách chúng kết nối với nhau**.

---

## Mục lục

1. [Tổng quan hệ thống](#1-tổng-quan-hệ-thống)
2. [Các công nghệ sử dụng](#2-các-công-nghệ-sử-dụng)
3. [Kiến trúc từng lớp](#3-kiến-trúc-từng-lớp)
4. [Luồng dữ liệu](#4-luồng-dữ-liệu)
5. [Build hệ thống](#5-build-hệ-thống)
6. [Tích hợp React Native](#6-tích-hợp-react-native)
7. [Kiểm thử với AWS Device Farm](#7-kiểm-thử-với-aws-device-farm)
8. [Roadmap](#8-roadmap)

---

## 1. Tổng quan hệ thống

```
┌─────────────────────────────────────────────────────────────────┐
│                     Ứng dụng React Native                       │
│                                                                 │
│  JS/TypeScript  →  Native Module  →  C++ Library (GPUPixel)    │
│                                                                 │
│  Slider UI      →  ObjC bridge    →  GPU Filter Pipeline       │
│  Camera View    →  AVCapture      →  OpenGL ES render          │
└─────────────────────────────────────────────────────────────────┘
```

**Ý tưởng cốt lõi:** Xử lý video real-time trên GPU (cực nhanh) bằng C++,
còn giao diện người dùng viết bằng React Native (dễ phát triển, cross-platform).

---

## 2. Các công nghệ sử dụng

### 2.1 C++ (Ngôn ngữ lõi)

**C++ là gì?**
Ngôn ngữ lập trình bậc thấp, chạy rất nhanh, kiểm soát trực tiếp bộ nhớ và phần cứng.

**Tại sao dùng C++ cho beauty filter?**
- Xử lý video 30fps cần tốc độ cực cao — C++ cho phép điều đó
- Gọi thẳng vào OpenGL ES (GPU) mà không qua lớp trung gian
- Code một lần, chạy được trên iOS, Android, Web (WASM), Desktop

**Trong dự án này:**
- Toàn bộ thư mục `src/` là C++
- Mỗi filter (làm mịn, trắng da, chỉnh mặt...) là một class C++

---

### 2.2 OpenGL ES (Render GPU)

**OpenGL ES là gì?**
API để điều khiển GPU trực tiếp trên thiết bị di động (ES = Embedded Systems).
Giống như một "ngôn ngữ" để nói chuyện với card đồ họa.

**Tại sao cần GPU?**
- Mỗi frame 1080p = 2 triệu pixel
- Mỗi pixel cần tính toán nhiều lần (blur, màu sắc, reshape...)
- CPU xử lý tuần tự → quá chậm cho real-time
- GPU xử lý song song (hàng nghìn lõi) → đủ nhanh cho 30fps

**Cách GPUPixel dùng OpenGL ES:**
```
Ảnh đầu vào → Upload lên GPU (texture) → Chạy shader (chương trình GPU)
→ Kết quả trên GPU → Hiển thị lên màn hình
```

**Shader là gì?**
Chương trình nhỏ chạy trực tiếp trên GPU, xử lý từng pixel một cách song song.
Ví dụ: shader làm mịn da lấy mỗi pixel và tính trung bình với các pixel xung quanh.

---

### 2.3 CMake (Hệ thống build)

**CMake là gì?**
Công cụ tạo ra file build (Makefile, Xcode project...) từ một file mô tả chung (`CMakeLists.txt`).

**Tại sao cần CMake?**
- Mỗi nền tảng có hệ thống build khác nhau: iOS dùng Xcode, Linux dùng Make, Web dùng Emscripten
- CMake là lớp trừu tượng ở giữa: viết một lần, generate cho mọi nền tảng

**Luồng CMake:**
```
CMakeLists.txt  →  cmake  →  Xcode Project / Makefile  →  build  →  .a / .framework
```

**Trong dự án:**
- `CMakeLists.txt` (gốc): cấu hình chung, detect platform
- `src/CMakeLists.txt`: build thư viện `libbeautyfilter`
- `demo/CMakeLists.txt`: build app demo
- `third_party/CMakeLists.txt`: build các thư viện phụ thuộc

---

### 2.4 Emscripten & WebAssembly (WASM)

**WebAssembly (WASM) là gì?**
Định dạng bytecode chạy được trong trình duyệt với tốc độ gần native.
Không phải JavaScript — là mã nhị phân do trình duyệt thực thi trực tiếp.

**Emscripten là gì?**
Compiler chuyển code C++ thành WebAssembly.
```
C++ code  →  emcc (Emscripten compiler)  →  app.wasm + app.js
```

**Tại sao WASM hữu ích cho dự án này?**
- Test filter logic ngay trên trình duyệt mà không cần iPhone hay Mac
- Cùng một C++ code chạy trên Web lẫn iOS — đảm bảo kết quả nhất quán
- Hiện tại đây là bản build **đang hoạt động** của dự án

**Artifacts sau build WASM:**
```
build/wasm/demo/
  app.wasm    ← code C++ đã compile thành bytecode
  app.js      ← wrapper JavaScript, load và giao tiếp với .wasm
  app.data    ← resources (model AI, texture filter)
```

---

### 2.5 ncnn (Neural Network)

**ncnn là gì?**
Thư viện inference neural network của Tencent, tối ưu cho thiết bị di động.

**Dùng để làm gì trong dự án?**
Phát hiện khuôn mặt và 106 điểm landmark (mắt, mũi, miệng, viền mặt...).
Kết quả landmark này dùng cho:
- `FaceReshapeFilter`: biết vị trí khuôn mặt để slim/to mắt đúng chỗ
- `BlusherFilter`: biết vị trí má để tô màu đúng

**Hiện trạng:**
- Bản WASM (`third_party/ncnn/lib/libncnn.a`): đã có, đang dùng
- Bản iOS: **chưa có** — cần build riêng từ source ncnn cho arm64

---

### 2.6 React Native (UI Framework)

**React Native là gì?**
Framework JavaScript/TypeScript của Meta để xây dựng app mobile.
Dùng cú pháp React (giống web) nhưng render ra UI native thật sự (không phải WebView).

**Tại sao client chọn React Native?**
- Chia sẻ code JS giữa iOS và Android
- Phát triển nhanh hơn so với viết native thuần
- Team có thể là web developer, dễ onboard

**Vấn đề: React Native và C++ không nói chuyện trực tiếp được.**
Giải pháp: **Native Module** — lớp cầu nối.

---

### 2.7 Native Module (Cầu nối ObjC ↔ React Native)

**Native Module là gì?**
Một class Objective-C/Swift được đăng ký với React Native,
cho phép JavaScript gọi code native.

**Trong dự án này có 2 loại:**

| Loại | File | Mục đích |
|------|------|---------|
| View Manager | `GPUPixelViewManager.mm` | Đăng ký `GPUPixelCameraView` như một component JSX |
| Bridge Module | `GPUPixelModule.mm` | Expose methods (`startCamera`, `setBeautyParams`...) lên JS |

**Luồng gọi:**
```
JS: cameraRef.current.setBeautyParams({ smoothing: 7, whitening: 5 })
    ↓
NativeModules.GPUPixelModule.setBeautyParams(viewTag, 7, 5)
    ↓
[GPUPixelCameraView setSmoothing:7]; [GPUPixelCameraView setWhitening:5];
    ↓
_beauty->SetBlurAlpha(0.7f); _beauty->SetWhite(0.25f);
```

---

### 2.8 AVFoundation (Camera iOS)

**AVFoundation là gì?**
Framework của Apple để làm việc với audio/video: camera, microphone, file media.

**Dùng ở đâu trong dự án?**
Trong `GPUPixelCameraView.mm`:
- `AVCaptureSession`: quản lý toàn bộ pipeline camera
- `AVCaptureDeviceInput`: kết nối camera trước (front camera)
- `AVCaptureVideoDataOutput`: nhận frame BGRA raw mỗi 1/30 giây

**Tại sao BGRA mà không phải RGBA?**
iOS camera output mặc định là BGRA (Blue-Green-Red-Alpha).
GPUPixel hỗ trợ cả hai format — dùng `GPUPIXEL_FRAME_TYPE_BGRA`.

---

## 3. Kiến trúc từng lớp

```
┌──────────────────────────────────────────────────────┐
│  LAYER 4: React Native UI (JavaScript/TypeScript)    │
│  - Slider components                                  │
│  - GPUPixelCameraView component                      │
│  - State management (params)                         │
└──────────────────┬───────────────────────────────────┘
                   │  Native Module bridge
┌──────────────────▼───────────────────────────────────┐
│  LAYER 3: Native Module (Objective-C)                │
│  - GPUPixelModule.mm    → nhận lệnh từ JS            │
│  - GPUPixelViewManager  → đăng ký native view        │
│  - GPUPixelCameraView   → AVCapture + GPUPixel       │
└──────────────────┬───────────────────────────────────┘
                   │  C function calls
┌──────────────────▼───────────────────────────────────┐
│  LAYER 2: C++ Library (GPUPixel / libbeautyfilter)   │
│  - Filter pipeline (BeautyFace, Reshape, Blusher)    │
│  - Face detection (ncnn + landmark model)            │
│  - Source / Sink management                          │
└──────────────────┬───────────────────────────────────┘
                   │  OpenGL ES calls
┌──────────────────▼───────────────────────────────────┐
│  LAYER 1: GPU (OpenGL ES / Metal)                    │
│  - Texture upload/download                           │
│  - Shader execution (GLSL)                           │
│  - Framebuffer rendering                             │
└──────────────────────────────────────────────────────┘
```

---

## 4. Luồng dữ liệu

```
[Camera phần cứng]
      │ 30 fps, BGRA frame
      ▼
[AVCaptureVideoDataOutput]    ← iOS framework
      │ CMSampleBuffer
      ▼
[GPUPixelCameraView delegate]  ← ObjC code của chúng ta
      │ uint8_t* BGRA data
      ▼
[FaceDetector::Detect()]       ← ncnn inference (khi bật)
      │ vector<float> landmarks (106 điểm × 2 tọa độ)
      ▼
[SourceRawData::ProcessData()] ← bắt đầu pipeline GPU
      │ Upload lên GPU texture
      ▼
[BlusherFilter]    → dùng landmarks để vẽ má hồng
      ▼
[FaceReshapeFilter] → dùng landmarks để warp khuôn mặt
      ▼
[BeautyFaceFilter]  → làm mịn da (bilateral blur) + trắng da
      ▼
[SinkView::Render()] → draw texture lên UIView bằng OpenGL ES
      │
      ▼
[Màn hình iPhone]
```

---

## 5. Build hệ thống

### 5.1 Build WASM (Test ngay — không cần Mac)

```bash
# Cài Emscripten
git clone https://github.com/emscripten-core/emsdk.git
cd emsdk && ./emsdk install latest && ./emsdk activate latest
source ./emsdk_env.sh

# Build
cd /path/to/ai-beauty-filter
./script/build_wasm.sh

# Chạy demo
cd output/bin && python3 serve.py
# Mở http://localhost:8080
```

### 5.2 Build iOS (Cần macOS + Xcode)

```bash
# Cài Xcode từ App Store, sau đó:
./script/build_ios.sh

# Output:
# output/ios/lib/libbeautyfilter.a   ← thư viện tĩnh
# output/ios/include/                ← headers
```

### 5.3 Cấu trúc output sau build

```
output/
  bin/              ← WASM demo (app.js, app.wasm, app.data, index.html)
  ios/
    lib/
      libbeautyfilter.a   ← thêm vào Xcode project
    include/
      gpupixel/           ← headers cho C++ API
```

---

## 6. Tích hợp React Native

### 6.1 Cấu trúc files

```
demo/react-native/
  ios/
    GPUPixelCameraView.h/.mm    ← Native View (camera + filter)
    GPUPixelViewManager.h/.mm   ← Đăng ký view với React Native
    GPUPixelModule.h/.mm        ← Native Module (JS ↔ native bridge)
  src/
    index.ts                    ← TypeScript API cho JS
```

### 6.2 Thêm vào Xcode project

1. Copy `output/ios/lib/libbeautyfilter.a` vào thư mục `ios/` của RN project
2. Copy `output/ios/include/` vào `ios/include/`
3. Copy `src/res/` (textures) và `third_party/custom-face-landmark/models/` vào app bundle
4. Thêm 4 files `.h/.mm` từ `demo/react-native/ios/` vào Xcode project
5. Thêm frameworks: `OpenGLES.framework`, `AVFoundation.framework`, `UIKit.framework`

### 6.3 Dùng trong React Native app

```tsx
import React, { useRef, useEffect } from 'react';
import { StyleSheet, View, Slider } from 'react-native';
import { GPUPixelCameraView, GPUPixelCameraRef } from './src';

export default function BeautyScreen() {
  const cameraRef = useRef<GPUPixelCameraRef>(null);

  useEffect(() => {
    // Bắt camera khi vào màn hình
    cameraRef.current?.startCamera();
    return () => {
      // Dừng khi rời màn hình
      cameraRef.current?.stopCamera();
    };
  }, []);

  return (
    <View style={styles.container}>
      {/* Native camera view với beauty filter */}
      <GPUPixelCameraView
        ref={cameraRef}
        style={StyleSheet.absoluteFill}
      />

      {/* Slider điều chỉnh params */}
      <Slider
        minimumValue={0} maximumValue={10}
        onValueChange={(v) =>
          cameraRef.current?.setBeautyParams({ smoothing: v, whitening: 5 })
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
});
```

---

## 7. Kiểm thử với AWS Device Farm

AWS Device Farm cho phép chạy app trên **thiết bị iOS thật** trên cloud,
không cần có iPhone hay Mac.

### 7.1 Chuẩn bị

**Bước 1: Build .ipa bằng EAS (không cần Mac)**
```bash
# Cài Expo EAS
npm install -g eas-cli
npx expo login

# Cấu hình build
eas build:configure

# Build .ipa trên cloud của Expo (miễn phí có giới hạn)
eas build --platform ios --profile preview
```
Sau khi build xong, download file `.ipa` từ dashboard của Expo.

**Bước 2: Tạo project trên AWS Device Farm**
```
AWS Console → Device Farm → Mobile Device Testing → Projects → Create project
Tên: "BeautyFilter-iOS-Test"
```

### 7.2 Chạy manual testing (xem app chạy thật)

```
1. Device Farm → Tạo project
2. Upload App → chọn file .ipa
3. Create Run → "Built-in: Explorer" (không cần viết test script)
4. Chọn thiết bị: iPhone 14, iPhone 13, iPhone SE...
5. Submit → Chờ 10-15 phút
6. Xem video recording + screenshots + logs
```

### 7.3 Chạy automated testing với Appium

Appium là framework test automation cho mobile, viết bằng Python/JavaScript.

```python
# test_beauty_filter.py
from appium import webdriver
from appium.webdriver.common.appiumby import AppiumBy
import time

desired_caps = {
    "platformName": "iOS",
    "deviceName": "iPhone 14",
    "app": "BeautyFilter.ipa",  # upload lên S3 trước
    "automationName": "XCUITest",
}

driver = webdriver.Remote("http://localhost:4723/wd/hub", desired_caps)

# Tìm slider smoothing và kéo
smoothing_slider = driver.find_element(AppiumBy.ACCESSIBILITY_ID, "smoothing-slider")
driver.execute_script("mobile: dragFromToForDuration", {
    "fromX": 50, "fromY": 500,
    "toX": 300,  "toY": 500,
    "duration": 1.0
})

# Chụp screenshot để verify
driver.save_screenshot("test_smoothing.png")

# Kiểm tra app không crash
assert driver.find_element(AppiumBy.ACCESSIBILITY_ID, "camera-view").is_displayed()

time.sleep(3)
driver.quit()
```

**Upload và chạy trên Device Farm:**
```bash
# Upload app lên S3
aws s3 cp BeautyFilter.ipa s3://your-bucket/beauty-filter/

# Tạo test run qua AWS CLI
aws devicefarm create-upload \
  --project-arn "arn:aws:devicefarm:us-west-2:YOUR_ACCOUNT:project:YOUR_PROJECT_ID" \
  --name "BeautyFilter.ipa" \
  --type IOS_APP

aws devicefarm schedule-run \
  --project-arn "arn:aws:devicefarm:us-west-2:..." \
  --app-arn "arn:aws:devicefarm:..." \
  --device-pool-arn "arn:aws:devicefarm:..." \
  --name "Beauty Filter Smoke Test" \
  --test '{"type":"APPIUM_PYTHON","testPackageArn":"..."}'
```

### 7.4 Chi phí AWS Device Farm

| Loại | Giá |
|------|-----|
| Pay-per-use (device minute) | $0.17/phút |
| Test 5 phút trên 3 thiết bị | ~$2.55 |
| Unlimited testing (tháng) | $250/tháng |

> Khuyến nghị: Dùng pay-per-use khi test không thường xuyên.

---

## 8. Roadmap

### Giai đoạn 1 — Đang ở đây ✅
- [x] C++ filter library (WASM build hoạt động)
- [x] iOS CMake configuration
- [x] iOS entry point (`ios_beauty.mm`)
- [x] React Native Native Module
- [x] TypeScript API

### Giai đoạn 2 — Tiếp theo
- [ ] Build `libbeautyfilter.a` cho iOS arm64 trên Mac (hoặc CI)
- [ ] Tích hợp vào React Native project thật
- [ ] Test smoke trên AWS Device Farm
- [ ] Camera permission handling trong React Native

### Giai đoạn 3 — Nâng cao
- [ ] Build ncnn cho iOS → bật face detection
- [ ] Hỗ trợ Android (thêm `PLAT_ANDROID` branch trong CMake)
- [ ] CI/CD: GitHub Actions → EAS Build → AWS Device Farm tự động

---

*Cập nhật lần cuối: 2026-05-30*
