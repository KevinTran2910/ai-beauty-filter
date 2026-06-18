# Build Guide

## Yêu Cầu Môi Trường

| Tool | Version | Ghi chú |
|---|---|---|
| Android Studio | Hedgehog 2023.1.1+ | Hoặc dùng command line |
| Android SDK | API 35 (compileSdk) | |
| Android NDK | r25c hoặc r26 | Xem `local.properties` cho path |
| CMake | 3.22.1 | Cài qua SDK Manager |
| Java | 17 | Source/target compatibility |
| Gradle | 8.x (AGP 8.6.0) | Wrapper đã có sẵn |
| ABI | arm64-v8a, armeabi-v7a | **Chỉ hai ABI này** |

> **mars-face-kit constraint:** Thư viện prebuilt chỉ có cho `arm64-v8a` và `armeabi-v7a`. Không thêm `x86` hoặc `x86_64` vào `abiFilters` — build sẽ fail khi link.

---

## Setup Local

### 1. Cài NDK và CMake

Trong Android Studio: **SDK Manager → SDK Tools → NDK (Side by side) + CMake 3.22.1**

Hoặc command line:
```bash
sdkmanager "ndk;25.2.9519653"
sdkmanager "cmake;3.22.1"
```

### 2. Tạo `local.properties`

```properties
sdk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653
```

File này **không commit** (có trong `.gitignore`). Mỗi dev tạo riêng.

### 3. Sync Gradle

```bash
./gradlew app:assembleDebug
```

Lần đầu sẽ download dependencies và build CMake — mất 5–10 phút.

---

## CMake Build Flags

Được define trong `app/build.gradle`:

```groovy
externalNativeBuild {
    cmake {
        arguments "-DGPUPIXEL_BUILD_SHARED_LIBS=ON",
                  "-DGPUPIXEL_ENABLE_FACE_DETECTOR=ON",
                  "-DGPUPIXEL_BUILD_DESKTOP_DEMO=OFF",
                  "-DANDROID_STL=c++_static"
    }
}
```

| Flag | Value | Ý nghĩa |
|---|---|---|
| `GPUPIXEL_BUILD_SHARED_LIBS` | `ON` | Build `libbeautyfilter.so` (shared), không phải static |
| `GPUPIXEL_ENABLE_FACE_DETECTOR` | `ON` | Bật face detector, blusher, reshape. Tắt → chỉ beauty smoothing |
| `GPUPIXEL_BUILD_DESKTOP_DEMO` | `OFF` | Không build desktop demo targets |
| `ANDROID_STL` | `c++_static` | Static link C++ STL để tránh ABI issues |

### Tắt Face Detector (khi debug hoặc cần performance)

```groovy
arguments "-DGPUPIXEL_ENABLE_FACE_DETECTOR=OFF"
```

Khi OFF: Pipeline chỉ còn `SourceRawData → BeautyFaceFilter → SinkRawData`. BlusherFilter và FaceReshapeFilter không được compile vào.

---

## Cấu Trúc Output

```
app/build/intermediates/cmake/debug/obj/
  arm64-v8a/
    libbeautyfilter.so    ← pipeline + JNI bridge
  armeabi-v7a/
    libbeautyfilter.so

# Prebuilt được copy từ:
third_party/mars-face-kit/libs/android/
  arm64-v8a/libmars-face-kit.so
  armeabi-v7a/libmars-face-kit.so
```

---

## Android 15+ Compatibility

```groovy
packagingOptions {
    jniLibs.useLegacyPackaging = false
}
```

**Lý do:** Android 15 và Android 16 yêu cầu shared libraries phải được **16KB page-aligned** khi package vào APK. `useLegacyPackaging = false` giữ `.so` files không bị compress trong APK, cho phép mmap trực tiếp với alignment chính xác.

Nếu set `true`, app sẽ crash khi chạy trên thiết bị Android 16 với kernel 16KB page size (dòng máy mới).

---

## Troubleshooting

### Lỗi: `No implementation found for native method`

```
java.lang.UnsatisfiedLinkError: No implementation found for 
com.aibeauty.beautyfilter.BeautyFilterNative.nativeInit
```

**Nguyên nhân:** `libbeautyfilter.so` chưa được build hoặc ABI không match.  
**Fix:** Xóa `app/build/`, sync lại, chạy `assembleDebug`. Nếu log ghi thiếu symbol cho `nativeInit`/`nativeProcessInto`, kiểm tra tên method private trong `BeautyFilterNative.java` có match symbol `Java_com_aibeauty_beautyfilter_BeautyFilterNative_native*` trong `jni_bridge.cc`.

### Lỗi: `libmars-face-kit.so not found`

**Nguyên nhân:** `jniLibs.srcDirs` không trỏ đúng path prebuilt.  
**Fix:** Kiểm tra `app/build.gradle`:
```groovy
sourceSets.main.jniLibs.srcDirs = ['../../third_party/mars-face-kit/libs/android']
```

### Lỗi CMake: `mars-face-kit` headers không tìm thấy

**Fix:** Kiểm tra `third_party/mars-face-kit/include/` có đầy đủ headers chưa. Nếu thiếu, cần lấy từ package gốc.

### Build thành công nhưng app crash ngay khi mở

1. Xem logcat: `adb logcat -s BeautyFilter,AndroidRuntime`
2. Thường do camera permission bị deny hoặc `init()` fail do không copy được assets
3. Kiểm tra `getFilesDir()` có write permission không (thường do SELinux trên một số device)

### Hiệu năng thấp (FPS < 15)

1. Tắt face detector nếu không cần reshape/blusher
2. Giảm resolution từ 640×480 xuống 480×360
3. Tăng detection skip rate từ 2 lên 3–4
4. Profile bằng Android GPU Inspector để tìm GL bottleneck

---

## Build Release

```groovy
// app/build.gradle — thêm signing config
signingConfigs {
    release {
        keyAlias '...'
        keyPassword '...'
        storeFile file('release.jks')
        storePassword '...'
    }
}
buildTypes {
    release {
        minifyEnabled true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        signingConfig signingConfigs.release
    }
}
```

Proguard rules hiện tại đã có keep rule cho JNI class:

```proguard
-keep class com.aibeauty.beautyfilter.BeautyFilterNative { *; }
```
