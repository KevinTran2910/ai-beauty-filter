# Filter Pipeline — Chi Tiết

Pipeline C++ là một directed graph kiểu `Source → Filter... → Sink`. Mỗi node nhận texture input từ node trước, xử lý bằng GLSL shader, output ra FBO mới.

## Pipeline Graph Đầy Đủ

```
SourceRawData (RGBA ByteBuffer → GL texture)
      │
      ▼
BlusherFilter
  └─ FaceMakeupFilter (base class)
       ├─ Program 1: render base frame to FBO
       └─ Program 2: draw blusher.png over landmark mesh
      │
      ▼
FaceReshapeFilter
  └─ GLSL vertex warp: 9 curveWarp + 2 enlargeEye
      │
      ▼
BeautyFaceFilter (FilterGroup)
  ├─ BilateralFilter
  │    └─ BilateralMonoFilter (H pass) → BilateralMonoFilter (V pass)
  ├─ BoxHighPassFilter
  └─ BeautyFaceUnitFilter  ◄── nhận 3 textures: original, bilateral, highpass
      │
      ▼
SinkRawData (glReadPixels → output ByteBuffer)
```

---

## SourceRawData

**File:** `src/source/source_raw_data.cc`

Nhận RGBA hoặc I420 pixel data từ Java qua JNI, upload lên GL texture.

```cpp
// Hai mode upload:
source->ProcessData(buffer, width, height, stride, RGBA);  // direct RGBA
source->ProcessData(buffer, width, height, stride, I420);  // YUV, convert trong shader
```

Không allocate texture mới mỗi frame — reuse texture nếu kích thước không đổi (`glTexSubImage2D`).

---

## BlusherFilter

**File:** `src/filter/blusher_filter.cc`  
**Base:** `FaceMakeupFilter`  
**Requires:** face landmarks

Overlay `blusher.png` lên vùng má theo landmark. Texture region mặc định:

```cpp
// {left, top, width, height} trong texture coordinates
SetTextureBounds({395, 520, 489, 209});
```

Khi `has_face_ = false`, filter pass-through frame không thay đổi.

**Blend mode:** multiply (mode 15) đang hardcoded trong `FaceMakeupFilter::DoRender()`. Shader có code cho normal/multiply/overlay/hard-light, nhưng chưa expose setter.

---

## FaceReshapeFilter

**File:** `src/filter/face_reshape_filter.cc`  
**Requires:** face landmarks (106 điểm)

### Hai phép biến đổi

**1. thinFace — gầy mặt**

9 `curveWarp` operations, mỗi cái kéo 1 điểm viền mặt về hướng chin-center.

```
Chin-center = trung bình của points 44, 45, 46, 49
Contour points bị kéo: indices khoảng 0–8 (viền trái) và 24–32 (viền phải)
Magnitude: thinFaceDelta (0.0 – 0.05 trong internal units)
```

`curveWarp` là smooth falloff — pixel càng xa điểm anchor càng ít bị ảnh hưởng. Không cắt hoặc stretch cứng.

**2. bigEye — to mắt**

2 `enlargeEye` operations cho mắt trái và phải.

```
Mắt trái:  center = point 74, radius anchor = point 72
Mắt phải:  center = point 77, radius anchor = point 75
Magnitude: bigEyeDelta (0.0 – 0.10 trong internal units)
```

`enlargeEye` expand vùng tròn quanh eye center, scale UV coordinates ra ngoài → hiệu ứng phóng to.

### Shader variants

Có 2 variants GLSL trong file:
- **Standard** (GLES3): dùng dynamic array indexing
- **WebGL fallback**: manually unrolled loops (không dùng trên Android, nhưng cần giữ cho cross-platform build)

---

## BeautyFaceFilter (FilterGroup)

**File:** `src/filter/beauty_face_filter.cc`

Là một `FilterGroup` chứa 3 filter node kết nối multi-input:

```
Input frame
  ├──► BilateralFilter ──────────────────────────► tex1 ─┐
  ├──► BoxHighPassFilter ────────────────────────► tex2 ─┤
  └──────────────────────────────────────────────► tex0 ─┤
                                                         ▼
                                              BeautyFaceUnitFilter
                                                         │
                                                         ▼
                                                   Output frame
```

`BeautyFaceUnitFilter` nhận **3 texture inputs** qua `glUniform1i`:
- `tex0`: original frame
- `tex1`: bilateral-blurred frame  
- `tex2`: high-pass variance map (từ BoxHighPassFilter)

### BilateralFilter

**File:** `src/filter/bilateral_filter.cc`

Hai-pass (horizontal + vertical) 9-tap Gaussian kernel với color-distance weighting.

```glsl
// Kernel weight = spatial_weight * color_weight
// color_weight = exp(-colorDistance² / distanceNormalizationFactor²)
```

`distanceNormalizationFactor` (default 8.0) kiểm soát "edge sharpness":
- Thấp → filter dừng lại mạnh ở edge
- Cao → filter blur qua cả edge

Bilateral filter **không** yêu cầu face landmark — nó blur toàn frame.

### BoxHighPassFilter

Tính variance map: `highpass = original - boxblur(original)`. Dùng để detect texture/detail vùng da. Output được dùng trong `BeautyFaceUnitFilter` để quyết định vùng nào cần smooth.

### BeautyFaceUnitFilter — Core Shader

**File:** `src/filter/beauty_face_unit_filter.cc`

Đây là shader phức tạp nhất trong pipeline. Hai pass trong 1 fragment shader:

#### Pass 1: Skin Smoothing

```glsl
// 1. Sobel edge detection trên tex0
float edge = sobelEdge(tex0, uv);
float edgeFactor = 1.0 - edge;  // bảo toàn edge

// 2. Skin detection mask (heuristic dựa trên red channel)
float p = isSkin(tex0.r, tex0.g, tex0.b);

// 3. Variance-based blend (từ highpass map)
float kMin = varianceBlend(tex2);

// 4. Mix original + bilateral, add sharpening detail
float smoothed = mix(tex0, tex1, edgeFactor * p * kMin);
smoothed += (tex0 - tex1) * sharpen * edgeFactor;
```

**Quan trọng:** Skin detection là heuristic RGB, **không dùng landmark**. Hoạt động trên mọi vùng da trong frame, không chỉ mặt.

#### Pass 2: Whitening (LUT Chain)

```glsl
// Bước 1: lookup_gray.png (1D strip LUT)
vec3 step1 = texture(lookupGray, vec2(color.r, 0.5)).rgb;

// Bước 2: lookup_origin.png (4×4×16 3D LUT)
vec3 step2 = sampleLUT3D(lookupOrigin, step1);

// Bước 3: lookup_skin.png (4×4×16 3D LUT)
vec3 step3 = sampleLUT3D(lookupSkin, step2);

// Bước 4: lookup_light.png bound vào uniform lookUpCustom
vec3 step4 = sampleLUT3D(lookupCustom, step3);

// Blend với original theo uniform 'whiten'
result = mix(smoothed, step4, whiten);
```

Thay bất kỳ LUT file nào đang được bind để thay đổi tone màu. Xem [assets-textures.md](assets-textures.md) để biết format và lưu ý `lookup_light.png` hiện là file bound vào `lookUpCustom`.

---

## SinkRawData

**File:** `src/sink/sink_raw_data.cc`

Đọc kết quả từ GL framebuffer về CPU memory:

```cpp
glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba_buffer_);
```

Buffer được pre-allocated, Java đọc qua `GetRgbaBuffer()` trả về pointer vào `rgba_buffer_`. Direct ByteBuffer phía Java trỏ vào cùng vùng nhớ này — không copy.

Có thể convert sang I420 (YUV) qua libyuv `ARGBToI420` nếu cần encode video.

---

## Thêm Filter vào Pipeline

Xem [extending-filters.md](extending-filters.md) để biết pattern thêm filter node mới.
