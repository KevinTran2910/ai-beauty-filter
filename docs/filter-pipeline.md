# Filter Pipeline

Pipeline native là directed graph kiểu:

```text
Source -> Filter -> Filter -> ... -> Sink
```

Mỗi filter nhận texture từ node trước, render shader ra framebuffer mới, rồi đẩy sang sink tiếp theo.

## Graph Chính

Khi `GPUPIXEL_ENABLE_FACE_DETECTOR=ON`:

```text
SourceRawData
  -> BlusherFilter
  -> FaceReshapeFilter
  -> BeautyFaceFilter
  -> SinkRender hoặc SinkRawData
```

Khi detector OFF:

```text
SourceRawData
  -> BeautyFaceFilter
  -> SinkRender hoặc SinkRawData
```

Terminal filter luôn là `BeautyFaceFilter`. JNI bridge attach một trong hai sink tùy path:

- `SinkRender`: preview realtime lên `SurfaceView`.
- `SinkRawData`: capture/image mode, readback RGBA về Java direct buffer.

## SourceRawData

**File:** `src/source/source_raw_data.cc`

Nhận frame từ JNI theo 2 format:

```cpp
GPUPIXEL_FRAME_TYPE_YUVI420
GPUPIXEL_FRAME_TYPE_RGBA
```

YUV path:

```text
CameraX YUV_420_888
-> libyuv Android420ToI420
-> SourceRawData I420 upload
-> shader YUV to RGB
-> rotation/mirror bằng texture coordinates
```

RGBA path:

```text
Direct ByteBuffer RGBA
-> SourceRawData RGBA upload
-> optional rotation mode, app hiện dùng NoRotation
```

`SourceRawData` reuse GL texture/framebuffer khi kích thước không đổi.

## BlusherFilter

**File:** `src/filter/blusher_filter.cc`  
**Base:** `FaceMakeupFilter`  
**Requires:** landmarks hợp lệ

Filter overlay `res/blusher.png` lên vùng má theo triangle mesh trong `FaceMakeupFilter`.

Config hiện tại:

```cpp
SetTextureBounds({395, 520, 489, 209});
SetBlendLevel(blusher / 10.0f);
```

Nếu không có face hoặc landmarks không đủ, filter pass-through.

## FaceReshapeFilter

**File:** `src/filter/face_reshape_filter.cc`  
**Requires:** khoảng 106 landmark points

Hai hiệu ứng chính:

- Face slim: nhiều phép `curveWarp` kéo vùng contour vào trong.
- Eye enlarge: `enlargeEye` quanh 2 tâm mắt.

JNI mapping:

```cpp
SetFaceSlimLevel(face_slim / 200.0f);
SetEyeZoomLevel(eye_enlarge / 100.0f);
```

Warp tính trong UV space `[0,1]`, nên internal level nhỏ nhưng vẫn tạo displacement thấy được ở full-res.

## BeautyFaceFilter

**File:** `src/filter/beauty_face_filter.cc`
**Type:** `FilterGroup`

Internal graph:

```text
input
  -> BilateralFilter -----------\
  -> BoxHighPassFilter ---------+-> BeautyFaceUnitFilter -> output
  -> original ------------------/
```

`BeautyFaceUnitFilter` nhận 3 textures:

- original frame
- bilateral blurred frame
- high-pass/detail map

### BilateralFilter

**File:** `src/filter/bilateral_filter.cc`

Hai pass horizontal/vertical, blur có giữ edge theo color distance. Không cần landmarks.

### BoxHighPassFilter

Tạo detail/variance map từ:

```text
original - boxblur(original)
```

Map này giúp shader quyết định vùng nào nên smooth.

### BeautyFaceUnitFilter

**File:** `src/filter/beauty_face_unit_filter.cc`

Làm 2 nhóm xử lý:

1. Skin smoothing:
   - edge detection để giữ cạnh
   - skin heuristic theo RGB
   - blend original với bilateral output
   - giữ/sharpen chi tiết từ high-pass

2. Whitening:
   - `lookup_gray.png`
   - `lookup_origin.png`
   - `lookup_skin.png`
   - `lookup_light.png` bound vào uniform `lookUpCustom`
   - blend theo uniform `whiten`

Native defaults trong `nativeInit`:

```cpp
g_beauty->SetBlurAlpha(0.7f);
g_beauty->SetWhite(0.1f);
```

Runtime values đến từ sliders qua JNI setters.

## SinkRender

**File:** `src/sink/sink_render.cc`

Preview sink render terminal texture ra EGL window surface lấy từ `ANativeWindow`. Fill mode đang dùng:

```cpp
SinkRender::PreserveAspectRatioAndFill
```

Path này tránh copy result về Java, nên là path chính cho realtime preview.

## SinkRawData

**File:** `src/sink/sink_raw_data.cc`

Readback texture về CPU bằng:

```cpp
glReadPixels(..., GL_RGBA, GL_UNSIGNED_BYTE, rgba_buffer_);
```

JNI copy buffer này vào direct `ByteBuffer` caller truyền vào. Dùng cho:

- `processInto` với picked image/fallback RGBA.
- `processIntoYuv` với capture camera.

Không dùng cho preview realtime trừ khi app đổi sang readback path.

## Face Detector

**Files:** `src/face_detector/face_detector.cc`, `third_party/mars-face-kit`

JNI gọi:

```cpp
g_face_detector->Detect(
    pixels, width, height, width * 4,
    GPUPIXEL_MODE_FMT_VIDEO,
    GPUPIXEL_FRAME_TYPE_RGBA);
```

Landmarks trả về được push vào `BlusherFilter` và `FaceReshapeFilter`. `BeautyFaceFilter` không cần landmarks.

## Adding Filters

Xem [extending-filters.md](extending-filters.md). Điểm cần nhớ với pipeline hiện tại: nếu filter mới cần xuất hiện cả preview lẫn capture, wire nó trước terminal `BeautyFaceFilter` hoặc cập nhật `PipelineTerminal()`/sink attach logic trong `jni_bridge.cc`.
