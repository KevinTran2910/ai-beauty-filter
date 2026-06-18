# Build Guide

## Toolchain

| Tool | Version/source | Ghi chú |
|---|---|---|
| Android Gradle Plugin | `8.6.0` | Khai báo ở root `build.gradle` |
| Gradle wrapper | `8.7` | `gradle/wrapper/gradle-wrapper.properties` |
| Java | 17 | `sourceCompatibility` và `targetCompatibility` |
| Android SDK | compile/target `35` | `app/build.gradle` |
| Min SDK | `24` | Android 7.0+ |
| CMake | `3.22.1` | Config trong `externalNativeBuild` |
| C++ | C++11 | Root `CMakeLists.txt` |
| NDK ABI | `arm64-v8a`, `armeabi-v7a` | Do `mars-face-kit` chỉ có 2 ABI này |

## Dependencies

Java/Android:

```groovy
implementation 'androidx.appcompat:appcompat:1.7.0'
implementation 'androidx.camera:camera-core:1.3.4'
implementation 'androidx.camera:camera-camera2:1.3.4'
implementation 'androidx.camera:camera-lifecycle:1.3.4'
```

Native:

- GPUPixel source trong repo, target output là `libbeautyfilter.so`.
- OpenGL ES 3, EGL, `android`, `jnigraphics`, `log` từ Android NDK.
- `libyuv` build từ `third_party/libyuv`.
- `stb` header-only từ `third_party/stb`.
- `mars-face-kit` prebuilt shared library từ `third_party/mars-face-kit/libs/android/<abi>/libmars-face-kit.so`.

## Local Setup

Tạo `local.properties` theo máy local:

```properties
sdk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653
```

`local.properties` không commit.

Build debug:

```bash
./gradlew.bat app:assembleDebug
```

Trên macOS/Linux:

```bash
./gradlew app:assembleDebug
```

## Gradle/CMake Config

`app/build.gradle` trỏ CMake về root:

```groovy
externalNativeBuild {
    cmake {
        path '../CMakeLists.txt'
        version '3.22.1'
    }
}
```

Native build arguments:

```groovy
arguments '-DGPUPIXEL_BUILD_SHARED_LIBS=ON',
          '-DGPUPIXEL_ENABLE_FACE_DETECTOR=ON',
          '-DGPUPIXEL_BUILD_DESKTOP_DEMO=OFF',
          '-DANDROID_STL=c++_static'
targets 'beautyfilter'
```

Ý nghĩa:

| Flag | Value | Ý nghĩa |
|---|---|---|
| `GPUPIXEL_BUILD_SHARED_LIBS` | `ON` | Build shared library `libbeautyfilter.so` |
| `GPUPIXEL_ENABLE_FACE_DETECTOR` | `ON` | Link `mars-face-kit`, bật landmark filters |
| `GPUPIXEL_BUILD_DESKTOP_DEMO` | `OFF` | Không build desktop demo target |
| `ANDROID_STL` | `c++_static` | Static link C++ STL |

## ABI Constraint

`app/build.gradle` chỉ build:

```groovy
ndk {
    abiFilters 'arm64-v8a', 'armeabi-v7a'
}
```

Không thêm `x86` hoặc `x86_64` nếu vẫn bật face detector, vì `mars-face-kit` không có prebuilt `.so` cho các ABI đó. CMake sẽ fail khi không tìm thấy:

```text
third_party/mars-face-kit/libs/android/<ABI>/libmars-face-kit.so
```

## Native Output

Build tạo:

```text
app/build/intermediates/cmake/debug/obj/
  arm64-v8a/libbeautyfilter.so
  armeabi-v7a/libbeautyfilter.so
```

APK cũng package prebuilt:

```text
third_party/mars-face-kit/libs/android/
  arm64-v8a/libmars-face-kit.so
  armeabi-v7a/libmars-face-kit.so
```

## Android 15/16 Native Lib Alignment

Gradle config:

```groovy
packagingOptions {
    jniLibs {
        useLegacyPackaging false
    }
}
```

CMake Android target cũng set:

```cmake
target_link_options(beautyfilter PRIVATE "-Wl,-z,max-page-size=16384")
```

Mục tiêu là đảm bảo native libraries phù hợp thiết bị dùng 16KB page size.

## Permissions Và Features

Manifest dùng:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-feature
    android:name="android.hardware.camera.any"
    android:required="false" />
```

API 29+ lưu ảnh qua `MediaStore`, không cần `WRITE_EXTERNAL_STORAGE`.

## Troubleshooting

### `No implementation found for native method`

Nguyên nhân thường là `libbeautyfilter.so` chưa build, ABI runtime không khớp, hoặc method JNI trong `BeautyFilterNative.java` không khớp symbol trong `jni_bridge.cc`.

Kiểm tra:

```bash
./gradlew.bat app:assembleDebug
adb logcat -s BeautyFilter AndroidRuntime
```

### `libmars-face-kit.so not found`

Kiểm tra `jniLibs` path:

```groovy
jniLibs.srcDirs += ['../../third_party/mars-face-kit/libs/android']
```

Và đảm bảo thiết bị/emulator dùng `arm64-v8a` hoặc `armeabi-v7a`.

### CMake không tìm thấy mars-face-kit prebuilt

Nếu đang build ABI khác, bỏ ABI đó khỏi `abiFilters` hoặc tắt:

```groovy
'-DGPUPIXEL_ENABLE_FACE_DETECTOR=OFF'
```

Khi OFF, reshape/blusher không có detector để hoạt động.

### Preview đen hoặc không render

Kiểm tra:

- `SurfaceView` đã gọi `surfaceCreated/surfaceChanged`.
- `BeautyFilterNative.setOutputSurface(...)` trả `true`.
- Camera permission đã granted.
- Logcat tag `BeautyFilterDemo` và `BeautyFilter`.

### FPS thấp

Các điểm nên kiểm tra:

- Resolution CameraX đang là `1920x1080`; giảm xuống 1280x720 hoặc thấp hơn nếu cần.
- Tăng `DETECT_EVERY` từ 2 lên 3 hoặc 4.
- Tắt face detector khi chỉ benchmark smoothing/whitening.
- Xem overlay `Native`, `Detect`, `Interval`, `CPU app`.
