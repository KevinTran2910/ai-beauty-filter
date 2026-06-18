# BeautyFilterAndroidDemo

Android demo app cho beauty filter thời gian thực. App dùng CameraX ở Java để lấy frame, đưa dữ liệu qua JNI, rồi xử lý bằng pipeline C++/OpenGL ES dựa trên GPUPixel. Face landmarks dùng `mars-face-kit` prebuilt để điều khiển các hiệu ứng thon mặt, to mắt và má hồng.

## Tech Stack

| Layer | Công nghệ đang dùng |
|---|---|
| App | Java, AppCompat `1.7.0`, single `MainActivity` |
| Camera | CameraX `1.3.4` (`camera-core`, `camera-camera2`, `camera-lifecycle`) |
| Camera format | `ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888` |
| Preview | `SurfaceView` + native `ANativeWindow` render qua `SinkRender` |
| Native bridge | JNI static methods, direct `ByteBuffer` cho zero-copy buffer access |
| Image pipeline | C++11, GPUPixel `1.3.0`, OpenGL ES 3, EGL |
| YUV handling | `libyuv::Android420ToI420`, rồi YUV to RGB trong shader |
| Face detection | `mars-face-kit` prebuilt `.so`, model files trong assets |
| Build | Gradle wrapper `8.7`, Android Gradle Plugin `8.6.0`, CMake `3.22.1` |
| Android SDK | `compileSdk 35`, `targetSdk 35`, `minSdk 24` |
| ABI | `arm64-v8a`, `armeabi-v7a` |

## Tính năng

- Camera realtime với native preview path không readback về Java mỗi frame.
- Chọn ảnh từ gallery và xử lý qua path RGBA off-screen.
- Capture frame hiện tại và lưu JPEG vào gallery.
- Chuyển camera trước/sau.
- Overlay thống kê FPS, CPU, memory và timing native.
- 5 slider runtime: làm mịn, làm trắng, thon mặt, to mắt, má hồng.

## Filter Features

- **Skin smoothing**: `BeautyFaceFilter` gồm bilateral blur, high-pass detail map và shader blend theo skin/edge heuristics.
- **Whitening**: LUT chain trong `BeautyFaceUnitFilter` với `lookup_gray`, `lookup_origin`, `lookup_skin`, `lookup_light`.
- **Face slim**: `FaceReshapeFilter` warp mesh dựa trên landmarks.
- **Eye enlarge**: `FaceReshapeFilter` phóng vùng mắt theo landmarks.
- **Blusher**: `BlusherFilter` overlay `blusher.png` theo landmark mesh.

## Cấu Trúc Dự Án

```text
android/
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      java/com/aibeauty/beautyfilter/
        MainActivity.java
        BeautyFilterNative.java
      assets/
        res/        # LUT + makeup textures
        models/     # mars-face-kit model files
      res/layout/activity_main.xml
  src/
    android/jni/    # JNI bridge
    core/           # EGL, GL context, framebuffer, shader program
    source/         # SourceRawData RGBA/I420 upload
    filter/         # GPUPixel filters
    sink/           # SinkRawData, SinkRender
    face_detector/  # mars-face-kit wrapper
  include/          # Public GPUPixel headers
  third_party/
    libyuv/
    mars-face-kit/
    stb/
  docs/
```

## Tài Liệu

| Doc | Nội dung |
|---|---|
| [Architecture](docs/architecture.md) | App/native data flow, threading, preview/capture paths |
| [Filter Pipeline](docs/filter-pipeline.md) | Filter graph, source/sink modes, beauty/reshape/makeup nodes |
| [JNI API](docs/api-jni.md) | Java API, native methods, parameter scaling |
| [Face Landmarks](docs/face-landmarks.md) | Landmark coordinate system và indices đang dùng |
| [Assets & Textures](docs/assets-textures.md) | LUT, makeup textures, model files, copy flow |
| [Build Guide](docs/build.md) | Toolchain, Gradle/CMake flags, ABI constraints |
| [Extending Filters](docs/extending-filters.md) | Pattern thêm filter mới vào native pipeline |

## Quick Start

```bash
./gradlew.bat app:assembleDebug
```

Yêu cầu Android SDK/NDK/CMake đã được cấu hình trong `local.properties`. Xem [docs/build.md](docs/build.md) để biết chi tiết môi trường và lỗi thường gặp.
