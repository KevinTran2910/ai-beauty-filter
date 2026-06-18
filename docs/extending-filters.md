# Extending Filters

Pipeline Android được wire trong `src/android/jni/jni_bridge.cc`. Pattern thêm filter mới:

1. Tạo hoặc bật class filter C++.
2. Đảm bảo `.cc` và header có trong `src/CMakeLists.txt`.
3. Tạo global shared pointer trong JNI bridge.
4. Wire filter vào graph trong `nativeInit`.
5. Push landmarks nếu filter cần face mesh.
6. Expose setter qua `BeautyFilterNative.java` nếu cần UI control.
7. Reset pointer trong `nativeDestroy`.

## Pipeline Hiện Tại

Với face detector ON:

```text
g_source -> g_blusher -> g_reshape -> g_beauty -> dynamic sink
```

Sink không nên attach cứng trong `nativeInit`. JNI bridge đang switch sink theo path:

- `EnsureRenderSinkAttached()` cho preview realtime (`SinkRender`).
- `EnsureRawSinkAttached()` cho capture/image mode (`SinkRawData`).

Vì vậy filter mới nên nằm trước terminal `g_beauty`, hoặc cần sửa `PipelineTerminal()` nếu terminal thay đổi.

## Ví Dụ: Bật LipstickFilter

`LipstickFilter` đã có sẵn:

```text
include/gpupixel/filter/lipstick_filter.h
src/filter/lipstick_filter.cc
src/res/mouth.png
app/src/main/assets/res/mouth.png
```

`src/CMakeLists.txt` cũng đã include `lipstick_filter.cc` và header, nên chỉ cần wire vào Android JNI pipeline.

### 1. Thêm State Trong JNI Bridge

```cpp
std::shared_ptr<LipstickFilter> g_lipstick;
```

Nếu `gpupixel/gpupixel.h` chưa export header này trong build hiện tại, include trực tiếp:

```cpp
#include "gpupixel/filter/lipstick_filter.h"
```

### 2. Tạo Filter Trong `nativeInit`

```cpp
g_lipstick = LipstickFilter::Create();
```

Nên validate cùng nhóm landmark filters:

```cpp
if (!g_source || !g_sink || !g_beauty || !g_reshape || !g_blusher || !g_lipstick) {
  return -1;
}
```

### 3. Wire Graph

Thay graph detector ON:

```cpp
g_source->AddSink(g_blusher);
g_blusher->AddSink(g_reshape);
g_reshape->AddSink(g_beauty);
```

bằng:

```cpp
g_source->AddSink(g_blusher);
g_blusher->AddSink(g_lipstick);
g_lipstick->AddSink(g_reshape);
g_reshape->AddSink(g_beauty);
```

Không thêm `g_beauty->AddSink(...)` ở đây; sink được attach động.

### 4. Push Landmarks

Trong `nativeDetectFace()`:

```cpp
if (g_blusher) g_blusher->SetFaceLandmarks(landmarks);
if (g_lipstick) g_lipstick->SetFaceLandmarks(landmarks);
if (g_reshape) g_reshape->SetFaceLandmarks(landmarks);
```

`LipstickFilter` kế thừa `FaceMakeupFilter`, nên cần landmark mesh đủ điểm như blusher.

### 5. Expose Setter

Trong `BeautyFilterNative.java`:

```java
private static native void nativeSetLipstickParams(float lipstick);

public static void setLipstickParams(float lipstick) {
    nativeSetLipstickParams(lipstick);
}
```

Trong `jni_bridge.cc`:

```cpp
JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeSetLipstickParams(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jfloat lipstick) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_lipstick) {
    return;
  }
  g_lipstick->SetBlendLevel(lipstick / 10.0f);
}
```

### 6. Reset Trong `nativeDestroy`

```cpp
g_lipstick.reset();
```

## Java UI Integration

Đọc slider value trên UI thread, nhưng push native call qua `cameraExecutor`:

```java
seekLipstick.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
    @Override
    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        cameraExecutor.execute(() -> {
            if (initialized) {
                BeautyFilterNative.setLipstickParams(progress);
            }
        });
    }

    @Override public void onStartTrackingTouch(SeekBar bar) {}
    @Override public void onStopTrackingTouch(SeekBar bar) {}
});
```

Nếu app đang ở image mode, gọi lại `reprocessImage()` sau setter để refresh still output.

## Viết Filter Mới

Filter đơn giản thường kế thừa `Filter`:

```cpp
class MyFilter : public Filter {
 public:
  static std::shared_ptr<MyFilter> Create();
  bool Init() override;
  void SetIntensity(float value);

 protected:
  void OnRenderWithTexture(GPUPixelFramebuffer* input) override;

 private:
  float intensity_ = 0.0f;
  GLint intensity_location_ = -1;
};
```

Các bước chính:

- `InitWithShaderString(vertex, fragment)`.
- Lấy uniform locations sau khi program init.
- Trong `OnRenderWithTexture`, set uniform rồi gọi base render.
- Nếu cần multi-pass hoặc multi-input, dùng `FilterGroup` như `BeautyFaceFilter`.

## Shader Compatibility

Android dùng OpenGL ES 3. Nếu filter còn phải chạy cross-platform, kiểm tra các nhánh shader hiện có trong source:

- GLES/OpenGL ES shader syntax.
- Desktop GL shader syntax.
- WebGL/WASM fallback nếu cần.

Giữ shader ở cùng pattern với các filter lân cận để tránh phá iOS/WASM build của GPUPixel.

## Checklist

- [ ] Thêm class/header nếu filter chưa có.
- [ ] Thêm source/header vào `src/CMakeLists.txt`.
- [ ] Tạo global pointer trong `jni_bridge.cc`.
- [ ] Tạo instance trong `nativeInit`.
- [ ] Wire graph trước terminal filter.
- [ ] Không attach sink cứng nếu dùng dynamic `SinkRender`/`SinkRawData`.
- [ ] Push landmarks trong `nativeDetectFace` nếu cần.
- [ ] Thêm setter Java + JNI nếu cần param runtime.
- [ ] Reset pointer trong `nativeDestroy`.
- [ ] Test preview realtime, capture, image mode.
- [ ] Test frame không có mặt; filter landmark-driven phải pass-through.
