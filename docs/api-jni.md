# JNI API Reference

**Java class:** `app/src/main/java/com/aibeauty/beautyfilter/BeautyFilterNative.java`
**Native impl:** `src/android/jni/jni_bridge.cc`
**Library:** `libbeautyfilter.so`

`BeautyFilterNative` là wrapper static. Public Java methods gọi private native methods có symbol dạng:

```text
Java_com_aibeauty_beautyfilter_BeautyFilterNative_native*
```

## Library Loading

```java
try {
    System.loadLibrary("mars-face-kit");
} catch (UnsatisfiedLinkError ignored) {
}
System.loadLibrary("beautyfilter");
```

`mars-face-kit` là dependency khi build với `GPUPIXEL_ENABLE_FACE_DETECTOR=ON`. Load trong `try/catch` để build không có detector vẫn chạy được.

## Threading Contract

Serialize mọi call native trên một thread. Trong app hiện tại là:

```java
Executors.newSingleThreadExecutor()
```

Lý do:

- Native giữ global pipeline state.
- JNI bridge có mutex, nhưng Java vẫn cần giữ thứ tự init, detect, render, setter, destroy.
- GL context/surface switching cần sequence ổn định.

Không gọi các method processing trực tiếp từ UI thread.

## Lifecycle

### `init(String resourceRoot) -> int`

Khởi tạo resource root, filter graph, face detector và sink.

```java
int rc = BeautyFilterNative.init(getFilesDir().getAbsolutePath());
```

`resourceRoot` phải chứa:

```text
res/
  lookup_gray.png
  lookup_origin.png
  lookup_skin.png
  lookup_light.png
  lookup_custom.png
  blusher.png
  mouth.png
models/
  face_det.mars_model
  face_align.mars_model
```

Return values:

| Value | Ý nghĩa |
|---|---|
| `0` | Init thành công |
| `1` | Đã init trước đó |
| `< 0` | Lỗi tạo pipeline |

### `destroy()`

Giải phóng pipeline globals, render sink, raw sink, face detector và window surface.

## Surface Preview API

### `setOutputSurface(Surface surface, int width, int height) -> boolean`

Attach native renderer vào `SurfaceView`.

Native flow:

```text
ANativeWindow_fromSurface
-> GPUPixelContext::SetWindowSurface
-> update render size
```

### `resizeOutputSurface(int width, int height)`

Cập nhật viewport/render size khi `SurfaceHolder.surfaceChanged` chạy.

### `clearOutputSurface()`

Detach `SinkRender` khỏi terminal filter và clear EGL window surface. Được gọi khi surface bị destroy hoặc chuyển sang image mode.

## Processing API

### `processPreviewYuv(...) -> boolean`

Realtime camera preview path.

```java
BeautyFilterNative.processPreviewYuv(
    y, u, v,
    width, height,
    yStride, uStride, vStride,
    uvPixelStride,
    rotationMode);
```

Input là 3 direct buffers từ `ImageProxy.PlaneProxy` của `YUV_420_888`.

Native xử lý:

1. Lấy address bằng `GetDirectBufferAddress`.
2. Repack Android `YUV_420_888` sang tight I420 bằng `libyuv::Android420ToI420`.
3. Attach `SinkRender`.
4. Switch sang EGL window surface.
5. `SourceRawData::ProcessData(..., GPUPIXEL_FRAME_TYPE_YUVI420)`.
6. Present buffer ra `SurfaceView`.

Không có `glReadPixels` trong path này.

### `processIntoYuv(...) -> boolean`

Capture path từ camera YUV sang RGBA output buffer.

```java
BeautyFilterNative.processIntoYuv(
    y, u, v,
    width, height,
    yStride, uStride, vStride,
    uvPixelStride,
    rotationMode,
    outRgba);
```

Khác `processPreviewYuv` ở sink:

- dùng pbuffer/off-screen surface
- attach `SinkRawData`
- copy kết quả RGBA vào `outRgba`

`outRgba` phải là direct `ByteBuffer` đủ cho kích thước frame sau rotation:

```text
rotatedWidth * rotatedHeight * 4
```

### `processInto(ByteBuffer inRgba, int width, int height, ByteBuffer outRgba) -> boolean`

Path RGBA dùng cho picked image hoặc fallback still processing.

Requirements:

- `inRgba` direct buffer, format RGBA, size `width * height * 4`.
- `outRgba` direct buffer, size tương tự.
- Caller đã đưa frame về orientation mong muốn.

Native set `NoRotation`, process qua graph rồi readback bằng `SinkRawData`.

### `detectFace(ByteBuffer smallRgba, int width, int height)`

Chạy face detector và update landmarks cho:

```cpp
g_blusher->SetFaceLandmarks(landmarks);
g_reshape->SetFaceLandmarks(landmarks);
```

Input nên là RGBA frame nhỏ, đã upright/mirrored giống preview output. App hiện tại tạo từ Y plane:

- longest side max `320px`
- detect mỗi `DETECT_EVERY = 2` frame
- direct `ByteBuffer`

Khi build tắt detector, method là no-op.

## Parameter Setters

### `setBeautyParams(float smoothing, float whitening)`

| Param | UI range | Native mapping | Target |
|---|---:|---:|---|
| `smoothing` | `0..10` | `smoothing / 10.0` | `BeautyFaceFilter::SetBlurAlpha` |
| `whitening` | `0..10` | `whitening / 20.0` nếu `> 0` | `BeautyFaceFilter::SetWhite` |

Lưu ý: với code hiện tại, nếu `whitening <= 0`, JNI không gọi `SetWhite(0)`. Slider về 0 sau khi từng tăng có thể không reset whitening về 0 nếu không sửa native condition.

### `setReshapeParams(float faceSlim, float eyeEnlarge)`

| Param | UI range | Native mapping | Target |
|---|---:|---:|---|
| `faceSlim` | `0..10` | `faceSlim / 200.0` | `FaceReshapeFilter::SetFaceSlimLevel` |
| `eyeEnlarge` | `0..10` | `eyeEnlarge / 100.0` | `FaceReshapeFilter::SetEyeZoomLevel` |

Chỉ có hiệu ứng khi có landmarks hợp lệ.

### `setMakeupParams(float blusher)`

| Param | UI range | Native mapping | Target |
|---|---:|---:|---|
| `blusher` | `0..10` | `blusher / 10.0` | `BlusherFilter::SetBlendLevel` |

Chỉ có hiệu ứng khi có landmarks hợp lệ.

## Perf Stats

### `getPerfStats() -> float[]`

Native trả 4 giá trị:

```text
[0] last preview native time in ms
[1] last readback native time in ms
[2] surface width
[3] surface height
```

`MainActivity.PerfStats` dùng các giá trị này để hiển thị overlay.

## Error Handling

Native không throw Java exception cho lỗi thường gặp. Các method processing trả `false`; lifecycle trả code. Xem log:

```bash
adb logcat -s BeautyFilter BeautyFilterDemo AndroidRuntime
```
