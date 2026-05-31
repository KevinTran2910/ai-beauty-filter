# Luồng Code — ai-beauty-filter

## Tổng quan kiến trúc

```
[Camera / pixel thô]
        │
        ▼
  SourceRawData          ← Tải dữ liệu từ CPU lên GPU (pixel → OpenGL texture)
        │
        ▼
  BlusherFilter          ← Pass GPU: đổ má hồng dựa trên landmark khuôn mặt
        │
        ▼
  FaceReshapeFilter      ← Pass GPU: biến dạng khuôn mặt (thon mặt + to mắt)
        │
        ▼
  BeautyFaceFilter       ← Nhóm pass GPU: lọc song phương + high-pass + làm trắng
        │
        ▼
  SinkView / SinkRawData ← Kết quả cuối: GPU → màn hình hoặc đọc lại về CPU
```

Tất cả các node dùng chung đối tượng `GPUPixelFramebuffer` để truyền dữ liệu.
Mỗi node đọc từ một framebuffer và ghi vào một framebuffer khác.
Pipeline là một đồ thị có hướng; `AddSink()` dùng để nối các cạnh.

---

## 1. Khởi động / Khởi tạo

### Điểm vào — iOS C API (`demo/ios/ios_beauty.mm`)

```
GPUPixelIOS_Init(void* view, const char* resPath)
  ├── GPUPixel::SetResourceRoot(resPath)          → lưu vào Util::resource_root_path_
  ├── SourceRawData::Create()                     → cấp phát GL texture trên luồng GPU
  ├── SinkView::Create(view)                      → nhúng ObjcView (CAEAGLLayer) vào UIView
  ├── BeautyFaceFilter::Create()                  → xây dựng nhóm filter bilateral + high-pass
  ├── FaceReshapeFilter::Create()                 → biên dịch GLSL shader biến dạng khuôn mặt
  ├── BlusherFilter::Create()                     → biên dịch GLSL shader lookup má hồng
  ├── FaceDetector::Create()  [nếu được bật]      → khởi tạo mars_vision::MarsFaceLandmarker
  │     └── tải face_det.mars_model + face_align.mars_model từ resPath/models/
  └── Nối pipeline:
        source → blusher → reshape → beauty → sinkView
```

### Điểm vào — React Native (`demo/react-native/ios/GPUPixelCameraView.mm`)

```
[GPUPixelCameraView startCamera]
  ├── _setupGPUPixelPipeline       → nối filter giống hệt phần trên
  └── _setupCaptureSession         → AVCaptureSession, camera sau, 30fps
        └── AVCaptureVideoDataOutput → delegate: self
              (captureOutput:didOutputSampleBuffer:)
```

### Điểm vào — WASM (`demo/wasm_html/wasm_app.cc`)

```
GPUPixel_Initialize(canvas_id)
GPUPixel_CreatePipeline()           → tạo các filter, nối đồ thị
GPUPixel_AddFilter(name, params)
GPUPixel_ProcessImage(ptr, w, h)
```

---

## 2. GPU Context (`src/core/gpupixel_context.cc`)

Singleton `GPUPixelContext` sở hữu OpenGL (ES) context và một dispatch queue nối tiếp
riêng biệt. **Tất cả lệnh GL phải chạy trên luồng này.** Context cung cấp:

- `SyncRunWithContext(lambda)` — chạy một block trên luồng GL, chặn luồng gọi đến khi xong
- `GetFramebufferFactory()` — pool các `GPUPixelFramebuffer` tái sử dụng được
- `GetEglContext()` — `EAGLContext*` (iOS) / WebGL context (WASM)
- `PresentBufferForDisplay()` — đẩy renderbuffer lên màn hình

Trên iOS, `iOSHelper` lắng nghe `UIApplicationWillResignActiveNotification`
để tạm dừng render khi ứng dụng chuyển xuống nền.

---

## 3. Luồng xử lý mỗi frame (iOS)

```
GPUPixelIOS_ProcessFrame(rgbaData, width, height)
  │
  ├── [Luồng CPU] FaceDetector::Detect(rgbaData, w, h)      [nếu được bật]
  │     ├── đóng gói pixel vào mars_vision::MarsImage
  │     ├── gọi MarsFaceLandmarker::Detect()                → chạy mạng neural MNN
  │     └── trả về vector<float> gồm 111 cặp (x, y), đã chuẩn hoá theo width/height
  │
  ├── blusher->SetFaceLandmarks(landmarks)
  ├── reshape->SetFaceLandmarks(landmarks)
  │
  └── source->ProcessData(rgbaData, w, h, stride, RGBA)
        └── SyncRunWithContext:
              ├── GenerateTextureWithPixels()
              │     ├── glTexImage2D → tải byte RGBA lên GL_TEXTURE_2D
              │     ├── bind framebuffer, chạy identity shader, glDrawArrays
              │     └── Deactivate framebuffer
              └── Source::DoRender(true)
                    └── với mỗi sink: sink->SetInputFramebuffer(fb) + sink->Render()
```

### React Native — luồng mỗi frame (qua camera)

```
captureOutput:didOutputSampleBuffer:
  ├── CVImageBufferRef → lock base address → uint8_t* rgba
  ├── FaceDetector::Detect(...)
  ├── SetFaceLandmarks cho blusher + reshape
  └── source->ProcessData(...)
```

---

## 4. Bên trong các Filter

### `BlusherFilter` (`src/filter/blusher_filter.cc`)

- Đọc các face landmark đã lưu
- Tải mảng float landmark lên GLSL dưới dạng uniform array
- GLSL shader lấy mẫu texture `blusher.png` và pha trộn tại vị trí má do landmark xác định
- Lookup table quyết định màu sắc má hồng

### `FaceReshapeFilter` (`src/filter/face_reshape_filter.cc`)

- Hai GLSL uniform: `thinFaceDelta` (thon mặt) và `bigEyeDelta` (to mắt)
- Shader cài đặt hàm biến dạng `curveWarp()` và `enlargeEye()`
- Đọc uniform array `facePoints[106 * 2]`
- `thinFace()`: đẩy đường hàm vào trong bằng 9 cặp landmark
- `enlargeEye()`: phóng to vùng mắt xung quanh landmark con ngươi
- Đầu ra là texture bị biến dạng — không xử lý da

### `BeautyFaceFilter` (`src/filter/beauty_face_filter.cc`) — một `FilterGroup`

Đồ thị con bên trong:

```
đầu vào ──► BilateralFilter ──► BeautyFaceUnitFilter ──► đầu ra
đầu vào ──► BoxHighPassFilter ──►        ↑
```

- **BilateralFilter**: làm mờ giữ cạnh (làm mịn da). Điều chỉnh bằng `SetBlurAlpha()`.
- **BoxHighPassFilter**: lấy ảnh gốc trừ ảnh mờ để trích xuất chi tiết mịn.
- **BeautyFaceUnitFilter**: tổng hợp lại —
  `kết_quả = ảnh_mờ + highpass × độ_chi_tiết + độ_trắng`.
  `SetWhite()` điều chỉnh độ sáng cộng thêm.

### `SourceRawData` (`src/source/source_raw_data.cc`)

Hỗ trợ ba định dạng pixel đầu vào qua `ProcessData()`:

| Hằng số định dạng | Cách tải lên GL |
|-------------------|-----------------|
| `GPUPIXEL_FRAME_TYPE_RGBA` | `glTexImage2D(GL_RGBA)` |
| `GPUPIXEL_FRAME_TYPE_BGRA` | `glTexImage2D(GL_BGRA)` — chỉ iOS/macOS |
| `GPUPIXEL_FRAME_TYPE_YUVI420` | 3 texture luminance riêng (Y, U, V); shader chuyển sang RGB |

Cấp phát / tái sử dụng `GPUPixelFramebuffer` khớp với kích thước đầu vào,
sau đó gọi `Source::DoRender()` để kích hoạt render dây chuyền xuống phía dưới.

---

## 5. Đầu ra render

### `SinkView` (iOS) — `src/sink/sink_view.mm` + `src/sink/objc_view.mm`

```
SinkView::SetInputFramebuffer(fb)     → chuyển cho ObjcView
SinkView::Render()
  └── [ObjcView DoRender]             → SyncRunWithContext:
        ├── glBindFramebuffer(displayFramebuffer)
        ├── glBindTexture(inputFramebuffer->GetTexture())
        ├── glDrawArrays(GL_TRIANGLE_STRIP)   ← vẽ quad toàn màn hình
        └── glBindRenderbuffer + PresentBufferForDisplay()
              └── [EAGLContext presentRenderbuffer]  → màn hình
```

`ObjcView` là subclass của `UIView` với `layerClass = CAEAGLLayer`.
Khi layout thay đổi sẽ gọi `destroyDisplayFramebuffer` + `createDisplayFramebuffer`
để resize renderbuffer.

### `SinkRawData` (`src/sink/sink_raw_data.cc`)

Dùng trên WASM để đọc pixel ngược về CPU:
`glReadPixels()` → buffer uint8_t → callback JavaScript.

---

## 6. Bên trong Face Detection (`src/face_detector/face_detector.cc`)

### Luồng iOS (mars_vision + MNN)

```
FaceDetector::FaceDetector()
  ├── Util::GetResourcePath("models")  → resPath + "/models"
  ├── MarsFaceLandmarker::Create()     → landmarker chạy trên MNN
  └── Init({model_path, RunningMode::VIDEO})
        ├── tải face_det.mars_model    → detector bounding box khuôn mặt
        └── tải face_align.mars_model  → bộ hồi quy 111 điểm landmark

FaceDetector::Detect(pixels, w, h, stride, fmt, type)
  ├── điền struct MarsImage
  ├── MarsFaceLandmarker::Detect(image, results)
  │     └── MNN chạy face_det → cắt ROI → face_align → 111 key_points
  └── trả về vector<float>[222]:  [x0/w, y0/h, x1/w, y1/h, ... × 111]
```

### Luồng WASM (`src/face_detector/custom_face_detector.cc`)

Dùng ncnn + thư viện custom-face-landmark (mô hình yoloface + landmark106).
Cùng định dạng đầu ra: các cặp (x, y) đã chuẩn hoá.

---

## 7. Quản lý tài nguyên

```
Util::SetResourceRoot(path)            → đặt resource_root_path_
Util::GetResourcePath("models")
  ├── iOS: nếu root đã đặt → root + "/models"
  │         ngược lại      → NSBundle resourcePath + "/models"
  └── WASM / Linux: root + "/models"  (hoặc chỉ "models" nếu root rỗng)
```

Texture của filter (`blusher.png`, `mouth.png`, `lookup_*.png`) được tải
lúc khởi tạo filter. Mô hình face detection chỉ được tải khi `FaceDetector`
được khởi tạo. Cả hai đều được giải quyết qua `Util::GetResourcePath`.

---

## 8. Đầu ra build theo nền tảng

| Nền tảng | Điểm vào chính | Thư viện đầu ra | Artifact cuối |
|----------|----------------|-----------------|---------------|
| **WASM** | `wasm_app.cc` (C exports) | `libbeautyfilter.a` | `app.wasm` + `app.js` + `app.data` |
| **iOS** | `ios_beauty.mm` C API hoặc `GPUPixelCameraView.mm` RN bridge | `libbeautyfilter.a` (3 slice) | `BeautyFilter.xcframework` |
| **Linux** | `demo/` ứng dụng desktop | `libbeautyfilter.a` | binary desktop |

Các C export của WASM (`_GPUPixel_Initialize` đến `_GPUPixel_Destroy`) là các wrapper
mỏng bao quanh cùng các đối tượng C++ `SourceRawData` / filter / `SinkRawData`
chạy trên mọi nền tảng.

---

## 9. Sơ đồ thư mục

```
src/
├── core/           GL context, pool framebuffer, biên dịch shader program
├── filter/         ~40 filter (tất cả kế thừa từ Filter/FilterGroup)
├── source/         SourceRawData, SourceImage — nhập frame, tải CPU→GPU
├── sink/           SinkRawData, SinkRender, SinkView (iOS) — GPU→màn hình hoặc đọc lại
├── face_detector/  FaceDetector (iOS: mars_vision; WASM: ncnn tuỳ chỉnh)
├── utils/          Util (đường dẫn tài nguyên, log), DispatchQueue, MathToolbox
└── res/            Texture nhúng sẵn (PNG được bake vào thư viện lúc build)

include/gpupixel/   Header API công khai (người dùng chỉ include các file này)

third_party/
├── mars-face-kit/  iOS prebuilt: libmars-face-kit.a + header mars_vision + file .mars_model
├── mnn/            iOS prebuilt: MNN.framework (inference mạng neural)
├── custom-face-landmark/  WASM prebuilt: liblnm_face_landmark.a + mô hình ncnn
├── ncnn/           WASM prebuilt: libncnn.a
├── libyuv/         Chuyển đổi YUV↔RGB (build từ source, mọi nền tảng)
├── glad/           Loader OpenGL (chỉ WASM)
└── stb/            Tải/lưu ảnh header-only

demo/
├── ios/            ios_beauty.h/.mm — điểm vào C API iOS độc lập
├── react-native/   GPUPixelCameraView, GPUPixelModule — bridge React Native
└── wasm_html/      wasm_app.cc + index.html — demo trình duyệt

script/
├── build_ios.sh    Build BeautyFilter.xcframework (3 slice + lipo + xcframework)
└── build_wasm.sh   Build app.wasm qua Emscripten

cmake/
└── ios.toolchain.cmake   CMake toolchain đầy đủ cho iOS/tvOS/watchOS/visionOS
```
