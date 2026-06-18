# BeautyFilterAndroidDemo

Android demo app tích hợp bộ lọc làm đẹp thời gian thực, sử dụng OpenGL ES 3 pipeline viết bằng C++ và face landmark detection qua thư viện mars-face-kit.

## Tech Stack

| Layer | Công nghệ |
|---|---|
| Camera | CameraX 1.3.4 (ImageAnalysis, RGBA_8888) |
| Render | OpenGL ES 3 / EGL (cross-platform GPUPixel v1.3.0) |
| Face Detection | mars-face-kit (prebuilt `.so`, 106-point landmark) |
| JNI Bridge | Direct `ByteBuffer` (zero-copy) |
| Build | AGP 8.6.0, CMake 3.22.1, NDK (arm64-v8a + armeabi-v7a) |
| Min SDK | 24 (Android 7.0) |

## Tính năng

- **Làm mịn** (Skin Smoothing) — Bilateral filter + variance-based skin mask + Sobel edge preservation
- **Làm trắng** (Whitening) — 3-step LUT chain color grading
- **Thon mặt** (Face Slim) — Landmark-driven mesh warp
- **To mắt** (Eye Enlarge) — Landmark-driven eye expansion
- **Má hồng** (Blusher) — Texture overlay blend theo landmark

## Cấu trúc dự án

```
android/
  app/src/main/
    java/com/aibeauty/beautyfilter/
      MainActivity.java       # Toàn bộ UI + camera logic
      BeautyFilterNative.java # JNI wrapper
    assets/
      res/                    # LUT textures + makeup textures
      models/                 # mars-face-kit model files
    res/layout/activity_main.xml
  src/                        # C++ source (GPUPixel core + filters + JNI)
  include/                    # Public C++ headers
  third_party/                # libyuv, mars-face-kit (prebuilt), stb
```

## Tài liệu

| Doc | Nội dung |
|---|---|
| [Architecture](docs/architecture.md) | Data flow, threading model, sơ đồ pipeline |
| [Filter Pipeline](docs/filter-pipeline.md) | Chi tiết từng filter node C++ |
| [JNI API](docs/api-jni.md) | API reference + bảng parameter scaling |
| [Face Landmarks](docs/face-landmarks.md) | Hệ tọa độ 106 điểm, indices quan trọng |
| [Assets & Textures](docs/assets-textures.md) | LUT files, makeup textures, cách thay thế |
| [Build Guide](docs/build.md) | Setup môi trường, build flags, troubleshooting |
| [Extending Filters](docs/extending-filters.md) | Hướng dẫn thêm filter mới vào pipeline |

## Quick Start

```bash
# Mở bằng Android Studio, sync Gradle, chạy trên thiết bị arm64 hoặc armeabi-v7a
# Xem docs/build.md để setup NDK và CMake
```
