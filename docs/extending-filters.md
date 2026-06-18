# Extending Filters — Thêm Filter Mới

## Pattern Tổng Quát

Pipeline là directed graph kiểu `AddSink`. Thêm node mới = implement filter class C++ → wire vào graph trong `jni_bridge.cc` → expose setter qua JNI nếu cần.

---

## Ví Dụ: Thêm LipstickFilter

`mouth.png` đã có trong assets và `LipstickFilter` đã tồn tại trong source/CMake, nhưng Android pipeline chưa wire filter này trong `jni_bridge.cc`. Đây là cách kích hoạt hoặc tùy biến.

### Bước 1: Kiểm tra filter class C++

Các file hiện đã có:

- `include/gpupixel/filter/lipstick_filter.h`
- `src/filter/lipstick_filter.cc`

Nếu cần viết lại hoặc tạo filter tương tự, dùng pattern dưới đây.

**File:** `src/filter/lipstick_filter.h`

```cpp
#pragma once

#include "gpupixel/filter/face_makeup_filter.h"

namespace gpupixel {
class GPUPIXEL_API LipstickFilter : public FaceMakeupFilter {
 public:
  static std::shared_ptr<LipstickFilter> Create();
  bool Init() override;

 private:
  LipstickFilter();
};
}  // namespace gpupixel
```

**File:** `src/filter/lipstick_filter.cc`

```cpp
bool LipstickFilter::Init() {
  auto mouth = SourceImage::Create(Util::GetResourcePath("res/mouth.png"));
  SetImageTexture(mouth);
  SetTextureBounds(FrameBounds{502.5, 710, 262.5, 167.5});
  return FaceMakeupFilter::Init();
}
```

### Bước 2: Xác nhận CMakeLists.txt

```cmake
# src/CMakeLists.txt hiện đã include:
${CMAKE_CURRENT_SOURCE_DIR}/filter/lipstick_filter.cc
${PROJECT_SOURCE_DIR}/include/gpupixel/filter/lipstick_filter.h
```

### Bước 3: Wire vào pipeline trong `jni_bridge.cc`

```cpp
// src/android/jni/jni_bridge.cc

// Thêm global
static std::shared_ptr<LipstickFilter> g_lipstick;

// Trong nativeInit():
g_lipstick = LipstickFilter::Create();

// Wire vào graph (sau BlusherFilter, trước FaceReshapeFilter):
g_source->AddSink(g_blusher);
g_blusher->AddSink(g_lipstick);   // ← thêm dòng này
g_lipstick->AddSink(g_reshape);   // ← thay thế g_blusher->AddSink(g_reshape)
g_reshape->AddSink(g_beauty);
g_beauty->AddSink(g_sink);

// Trong nativeDetectFace() — push landmarks:
if (g_lipstick) g_lipstick->SetFaceLandmarks(landmarks);

// Trong nativeDestroy():
g_lipstick.reset();
```

### Bước 4: Expose Java setter

**Trong `BeautyFilterNative.java`:**

```java
private static native void nativeSetLipstickParams(float opacity);

public static void setLipstickParams(float opacity) {
    nativeSetLipstickParams(opacity);
}
```

**Trong `jni_bridge.cc`:**

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeSetLipstickParams(
        JNIEnv* env, jclass clazz, jfloat opacity) {
    if (g_lipstick) {
        g_lipstick->SetBlendLevel(opacity / 10.0f);
    }
}
```

### Bước 5: Java caller — serialize trên cameraExecutor

```java
// Trong MainActivity.java
lipstickSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
    @Override
    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        cameraExecutor.execute(() ->
            BeautyFilterNative.setLipstickParams((float) progress));
    }
    // ...
});
```

---

## Viết Filter GLSL Mới (từ đầu)

### Template Filter Class

```cpp
class MyCustomFilter : public Filter {
public:
    static std::shared_ptr<MyCustomFilter> Create() {
        auto f = std::make_shared<MyCustomFilter>();
        return f->Init() ? f : nullptr;
    }

    bool Init() override {
        // Vertex shader: standard pass-through
        // Fragment shader: custom logic
        if (!InitWithShaderString(kVertexShader, kFragmentShader)) return false;
        // Lấy uniform locations
        intensity_location_ = glGetUniformLocation(program_->GetProgram(), "intensity");
        return true;
    }

    void SetIntensity(float v) { intensity_ = v; }

protected:
    void OnRenderWithTexture(GPUPixelFramebuffer* input) override {
        glUniform1f(intensity_location_, intensity_);
        // Filter::OnRenderWithTexture handle draw call
        Filter::OnRenderWithTexture(input);
    }

private:
    float intensity_ = 1.0f;
    GLint intensity_location_ = -1;

    static constexpr const char* kVertexShader = R"(
        attribute vec4 position;
        attribute vec2 inputTextureCoordinate;
        varying vec2 textureCoordinate;
        void main() {
            gl_Position = position;
            textureCoordinate = inputTextureCoordinate;
        }
    )";

    static constexpr const char* kFragmentShader = R"(
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform sampler2D inputImageTexture;
        uniform float intensity;
        void main() {
            vec4 color = texture2D(inputImageTexture, textureCoordinate);
            // Custom logic here
            gl_FragColor = mix(color, vec4(1.0 - color.rgb, color.a), intensity);
        }
    )";
};
```

### GLES3 vs Desktop GL Shader Variants

Nếu filter cần chạy cross-platform (WASM, Mac), cần viết 2 variants:

```cpp
// GLES3 (Android):
"#version 300 es\n"
"precision highp float;\n"
"in vec2 textureCoordinate;\n"
"out vec4 fragColor;\n"
"uniform sampler2D inputImageTexture;\n"
"void main() { fragColor = texture(inputImageTexture, textureCoordinate); }\n"

// Desktop GL (Mac/Linux):
"#version 330\n"
"in vec2 textureCoordinate;\n"
"out vec4 fragColor;\n"
"uniform sampler2D inputImageTexture;\n"
"void main() { fragColor = texture(inputImageTexture, textureCoordinate); }\n"
```

Xem `GPUPixelContext::IsOpenGLES()` để branch tại runtime, hoặc dùng preprocessor define từ CMake.

---

## FilterGroup — Kết Hợp Nhiều Filter

Khi filter cần xử lý qua nhiều pass hoặc cần nhiều texture inputs, dùng `FilterGroup`:

```cpp
class MyMultiPassFilter : public FilterGroup {
public:
    bool Init() override {
        pass1_ = SomeFilter::Create();
        pass2_ = AnotherFilter::Create();

        // Wire internal graph
        AddFilter(pass1_);
        AddFilter(pass2_);
        pass1_->AddSink(pass2_);

        // Set input/output của group
        SetTerminalFilter(pass2_);
        return true;
    }
private:
    std::shared_ptr<SomeFilter> pass1_;
    std::shared_ptr<AnotherFilter> pass2_;
};
```

Xem `beauty_face_filter.cc` cho ví dụ multi-input (3 textures vào 1 shader).

---

## Checklist Khi Thêm Filter Mới

- [ ] Implement class kế thừa `Filter` hoặc `FilterGroup`
- [ ] Thêm `.cc` vào CMake sources list
- [ ] Wire graph trong `nativeInit()` bằng `AddSink`
- [ ] Reset trong `nativeDestroy()`
- [ ] Nếu dùng landmark: gọi `SetFaceLandmarks()` trong `nativeDetectFace()`
- [ ] Nếu expose param: thêm native method trong `BeautyFilterNative.java` + `jni_bridge.cc`
- [ ] Gọi setter từ `cameraExecutor`, không phải UI thread
- [ ] Test với face detection ON và OFF
- [ ] Test khi không có mặt trong frame (filter phải pass-through)
