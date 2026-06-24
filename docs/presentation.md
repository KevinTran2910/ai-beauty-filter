# DAYO Beauty Filter — Tài liệu trình diễn

> Slide-style. Mỗi mục `## ` là 1 slide. Phần *Nói gì* là gợi ý thuyết trình.

---

## Slide 1 — Mở đầu

**DAYO Beauty Filter** — App Android làm đẹp camera **thời gian thực**.

- Camera → lọc da, làm trắng, thon mặt, to mắt, má hồng → hiển thị ngay trên màn hình.
- Chạy mượt ở **HD, ~30 FPS** trên GPU.
- Kiến trúc lai: **Java (CameraX)** điều phối + **C++/OpenGL ES** xử lý ảnh.

*Nói gì:* "Đây là app làm đẹp realtime, điểm nhấn là pipeline ảnh chạy trên GPU bằng C++ chứ không xử lý từng pixel trên Java — nhờ vậy đạt được tốc độ thời gian thực."

---

## Slide 2 — Bài toán

Làm đẹp realtime khó ở 3 chỗ:

1. **Tốc độ** — mỗi giây ~30 khung hình, mỗi khung Full HD có ~2 triệu pixel cần xử lý.
2. **Độ trễ** — ảnh phải hiện gần như tức thì, không được giật.
3. **Hiệu ứng theo khuôn mặt** — thon mặt / to mắt / má hồng cần biết mắt, miệng, viền mặt nằm đâu.

→ Giải pháp: đẩy toàn bộ xử lý ảnh xuống **GPU**, và dùng **AI nhận diện điểm mốc khuôn mặt** (face landmarks).

---

## Slide 3 — Tech Stack

| Lớp | Công nghệ |
|---|---|
| App / UI | Java, AppCompat, single `MainActivity` |
| Camera | CameraX `1.3.4`, định dạng `YUV_420_888` |
| Cầu nối | JNI + direct `ByteBuffer` (zero-copy) |
| Xử lý ảnh | C++11, **GPUPixel** `1.3.0`, **OpenGL ES 3**, EGL |
| Màu YUV→RGB | `libyuv` repack + chuyển màu trong **shader GPU** |
| Nhận diện mặt | **mars-face-kit** (prebuilt `.so` + model AI) |
| Build | Gradle `8.7`, AGP `8.6.0`, CMake `3.22.1`, NDK |
| Thiết bị | minSdk 24, ABI `arm64-v8a` / `armeabi-v7a` |

---

## Slide 4 — Kiến trúc tổng thể

```
┌─────────────────────── Java (CameraX) ───────────────────────┐
│  Camera  →  ImageProxy (YUV_420_888)  →  MainActivity.analyze │
└───────────────────────────────┬──────────────────────────────┘
                                 │  JNI (ByteBuffer, zero-copy)
┌────────────────────────────────▼─────────────────────────────┐
│                    C++ / OpenGL ES (GPUPixel)                 │
│   SourceRawData → [Blusher → Reshape → BeautyFace] → Sink     │
│                         (toàn bộ chạy trên GPU)               │
└────────────────────────────────┬─────────────────────────────┘
                                 │
                    Surface (hiển thị)  /  Bitmap (chụp ảnh)
```

- **Java** lo: camera, xoay/lật ảnh, throttle nhận diện, UI slider.
- **C++/GPU** lo: chuyển màu, các bộ lọc làm đẹp, biến dạng khuôn mặt, render.

*Nói gì:* "Ranh giới rõ ràng: Java quản lý vòng đời và luồng dữ liệu, C++ làm phần nặng về tính toán pixel."

---

## Slide 5 — Luồng 1 khung hình (Preview)

1. CameraX trả frame **YUV_420_888** (định dạng gốc của camera — không tốn chi phí chuyển màu).
2. Cứ mỗi **2 khung**, chạy nhận diện mặt trên ảnh thu nhỏ (≤320px) → cập nhật landmarks.
3. Đẩy 3 mặt phẳng Y/U/V qua JNI thẳng lên **GPU texture**.
4. GPU: chuyển YUV→RGB trong shader, **xoay + lật** đúng chiều, chạy chuỗi filter.
5. Render thẳng ra `SurfaceView` — **không copy ảnh ngược về Java mỗi frame**.

→ Điểm mấu chốt: đường preview **không readback**, nên rất nhanh.

---

## Slide 6 — Chuỗi bộ lọc (Filter Pipeline)

```
SourceRawData (YUV→RGB)
   → BlusherFilter        (má hồng, theo landmark)
   → FaceReshapeFilter    (thon mặt + to mắt, warp mesh theo landmark)
   → BeautyFaceFilter     (làm mịn da + làm trắng)
   → Sink (màn hình hoặc bitmap)
```

| Hiệu ứng | Bộ lọc | Cách hoạt động |
|---|---|---|
| Làm mịn da | `BeautyFaceFilter` | bilateral blur + high-pass, blend theo vùng da |
| Làm trắng | LUT (`BeautyFaceUnitFilter`) | bảng tra màu `lookup_*.png` |
| Thon mặt | `FaceReshapeFilter` | warp lưới điểm theo landmark |
| To mắt | `FaceReshapeFilter` | phóng to vùng mắt theo landmark |
| Má hồng | `BlusherFilter` | phủ texture `blusher.png` theo mesh |

*Nói gì:* "3 trong 5 hiệu ứng cần điểm mốc khuôn mặt — nếu không thấy mặt thì chỉ làm mịn + làm trắng vẫn chạy."

---

## Slide 7 — Nhận diện khuôn mặt (Face Landmarks)

- Dùng **mars-face-kit** (AI prebuilt) → trả về bộ **điểm mốc** chuẩn hóa [0,1].
- Điều khiển: viền mặt (thon mặt), mắt (to mắt), má (má hồng).
- **Tối ưu:** nhận diện là bước **nặng nhất**, nên:
  - Chỉ chạy **mỗi 2 khung** (`DETECT_EVERY = 2`).
  - Chạy trên ảnh **thu nhỏ ≤ 320px** (grayscale từ mặt phẳng Y).
  - Landmarks được **giữ lại** giữa các lần nhận diện → mặt vẫn bám mượt.

*Nói gì:* "Mẹo: tách tần suất nhận diện ra khỏi tần suất render. Render 30fps nhưng chỉ detect 15 lần/giây vẫn nhìn liền mạch."

---

## Slide 8 — Tối ưu hiệu năng (điểm nhấn kỹ thuật)

1. **Zero-copy JNI** — Java và C++ dùng chung `direct ByteBuffer`, không copy bộ nhớ.
2. **Không cấp phát mỗi khung** — buffer staging được tái dùng.
3. **YUV thẳng lên GPU** — bỏ bước chuyển YUV→RGBA trên CPU (vốn là nút thắt cổ chai).
4. **Xoay/lật trên GPU** — không xoay bitmap bằng CPU.
5. **Preview không readback** — render thẳng ra surface.
6. **Đơn luồng GL** — mọi lệnh native chạy trên 1 executor, khớp với GL worker thread → an toàn, không cần khóa phức tạp.

> **Phát hiện quan trọng:** FPS bị giới hạn bởi **tốc độ camera giao frame / chuyển màu**, không phải bởi pipeline render. 1080p ≈ 20fps, 720p ≈ 27fps.

---

## Slide 9 — Tính năng app

- 📷 Camera realtime, đổi camera trước/sau.
- 🖼️ Chọn ảnh từ thư viện → lọc qua đường RGBA off-screen.
- 💾 Chụp khung hiện tại, lưu JPEG vào thư viện.
- 🎚️ **5 slider chỉnh trực tiếp:** làm mịn, làm trắng, thon mặt, to mắt, má hồng.
- 📊 Overlay thống kê realtime: FPS, CPU, RAM, thời gian xử lý native.

---

## Slide 10 — Hai đường xử lý

| | **Preview** (live) | **Capture / Ảnh** |
|---|---|---|
| Đầu vào | YUV từ camera | YUV camera / Bitmap gallery |
| Hàm native | `processPreviewYuv` | `processIntoYuv` / `processInto` |
| Đầu ra | render thẳng ra Surface | đọc RGBA về Bitmap |
| Readback về Java | **Không** (nhanh) | Có (để lưu file) |

*Nói gì:* "Cùng một pipeline GPU, chỉ khác cái đích: preview ra màn hình, capture đọc ngược về để lưu."

---

## Slide 11 — Đa nền tảng

- Pipeline và cách scale tham số **giống hệt iOS và WASM demo**.
- JNI bridge (`jni_bridge.cc`) phản chiếu đúng C API của iOS (`ios_beauty.mm`).
- → Một lõi C++/GPUPixel, nhiều nền tảng: **Android / iOS / Web**.

---

## Slide 12 — Demo gợi ý

1. Mở app → camera trước, thấy mặt được làm đẹp mặc định.
2. Kéo slider **làm mịn / làm trắng** → da thay đổi ngay.
3. Kéo **thon mặt / to mắt** → thấy biến dạng theo khuôn mặt.
4. Kéo **má hồng** → texture phủ lên má.
5. Chỉ vào overlay FPS để chứng minh chạy realtime.
6. Đổi camera sau / chọn 1 ảnh từ gallery / chụp 1 tấm lưu lại.

---

## Slide 13 — Tổng kết

- App làm đẹp realtime, kiến trúc **Java điều phối + C++/OpenGL xử lý**.
- Đạt **HD ~30 FPS** nhờ: zero-copy, YUV-lên-thẳng-GPU, không readback ở preview, throttle nhận diện.
- 5 hiệu ứng, 3 trong số đó **bám theo khuôn mặt** bằng AI landmarks.
- Lõi dùng chung với iOS/Web → dễ mở rộng đa nền tảng.

**Tài liệu chi tiết:** `docs/architecture.md`, `docs/filter-pipeline.md`, `docs/api-jni.md`, `docs/face-landmarks.md`, `docs/build.md`.
