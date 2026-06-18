# JNI API Reference

**Class:** `com.aibeauty.beautyfilter.BeautyFilterNative`  
**File:** `app/src/main/java/com/aibeauty/beautyfilter/BeautyFilterNative.java`  
**Native impl:** `src/android/jni/jni_bridge.cc`

Public API là static wrapper methods (`init`, `destroy`, ...), còn JNI symbols map tới các private native methods (`nativeInit`, `nativeDestroy`, ...). Class load hai shared library theo thứ tự:

```java
System.loadLibrary("mars-face-kit");   // prebuilt, dependency của libbeautyfilter khi face detector ON
System.loadLibrary("beautyfilter");    // pipeline chính
```

`mars-face-kit` được load trong `try/catch`; build không có face detector vẫn chạy nếu library này không có mặt.

> **Thread safety:** Tất cả calls phải được serialize trên một thread duy nhất. Trong `MainActivity`, đây là `cameraExecutor`. Không gọi từ UI thread hoặc callback thread khác.

---

## Lifecycle Methods

### `init(String resourceRoot) → int`

Khởi tạo pipeline. Phải gọi trước bất kỳ method nào khác.

```java
int result = BeautyFilterNative.init(context.getFilesDir().getAbsolutePath());
```

`resourceRoot` là thư mục chứa `res/` và `models/` (copy từ assets trước khi gọi).

**Return values:**

| Value | Ý nghĩa |
|---|---|
| `0` | Thành công |
| `1` | Đã init rồi (gọi lại là no-op an toàn) |
| `< 0` | Lỗi (xem logcat tag `BeautyFilter`) |

**Native side:** tạo `GPUPixelContext` (EGL context + GL thread), khởi tạo toàn bộ pipeline graph, load model files cho face detector, load LUT textures từ `resourceRoot/res/`.

---

### `destroy()`

Giải phóng toàn bộ GL resources, model, và EGL context.

```java
BeautyFilterNative.destroy();
```

Gọi trong `onDestroy()` hoặc khi không dùng camera nữa. Sau `destroy()`, phải gọi `init()` lại trước khi dùng tiếp.

---

## Processing Methods

### `processInto(ByteBuffer inRgba, int width, int height, ByteBuffer outRgba) → boolean`

Xử lý một frame qua toàn bộ filter pipeline.

```java
boolean ok = BeautyFilterNative.processInto(inBuffer, width, height, outBuffer);
```

**Requirements:**
- `inRgba`: `ByteBuffer.allocateDirect(width * height * 4)`, format RGBA_8888
- `outRgba`: `ByteBuffer.allocateDirect(width * height * 4)`, cùng kích thước
- Cả hai buffer phải là **direct** ByteBuffer (không phải heap buffer)
- Không được call từ 2 thread cùng lúc

**Return:** `true` nếu thành công. `false` nếu pipeline chưa init hoặc lỗi GL.

**Performance note:** Call này block cho đến khi `glReadPixels` hoàn thành trên GL thread (~3–8ms tùy thiết bị và resolution).

---

### `detectFace(ByteBuffer smallRgba, int width, int height)`

Chạy face detection + landmark extraction, cập nhật internal landmark state cho blusher và reshape filters.

```java
BeautyFilterNative.detectFace(smallBuffer, smallW, smallH);
```

**Requirements:**
- `smallRgba`: Direct ByteBuffer, RGBA_8888
- Nên downscale frame về max 320px (cạnh dài) trước khi gọi để tiết kiệm CPU
- Landmarks được normalize về [0,1] bởi mars-face-kit — tự động scale lên full resolution khi apply

**Gọi bao nhiêu:** `MainActivity` gọi mỗi 2 frame. Có thể điều chỉnh tỷ lệ này tùy performance budget.

**Khi không có mặt trong frame:** `FaceDetector::Detect()` trả về vector rỗng. `FaceReshapeFilter` cần ít nhất 106 điểm, còn `FaceMakeupFilter`/`BlusherFilter` cần ít nhất 111 điểm vì mesh index lên tới 110; nếu thiếu, `has_face_` set về `false` và filter pass-through. BeautyFaceFilter vẫn chạy bình thường.

---

## Parameter Setters

### `setBeautyParams(float smoothing, float whitening)`

```java
BeautyFilterNative.setBeautyParams(7.0f, 2.0f);
```

| Param | Range (UI) | Internal range | Mapping | Filter |
|---|---|---|---|---|
| `smoothing` | 0.0 – 10.0 | 0.0 – 1.0 | `x / 10.0` | BilateralFilter `blurAlpha` |
| `whitening` | 0.0 – 10.0 | 0.0 – 0.5 | `x / 20.0` | `whiten` uniform trong BeautyFaceUnitFilter |

**Whitening capped ở 0.5** để tránh over-whitening. Giá trị UI = 10 tương đương 50% blend với output LUT chain.

---

### `setReshapeParams(float faceSlim, float eyeEnlarge)`

```java
BeautyFilterNative.setReshapeParams(0.0f, 0.0f);
```

| Param | Range (UI) | Internal range | Mapping | Filter |
|---|---|---|---|---|
| `faceSlim` | 0.0 – 10.0 | 0.0 – 0.05 | `x / 200.0` | `thinFaceDelta` trong FaceReshapeFilter |
| `eyeEnlarge` | 0.0 – 10.0 | 0.0 – 0.10 | `x / 100.0` | `bigEyeDelta` trong FaceReshapeFilter |

**Lý do scale nhỏ:** Warp displacement được tính trong UV space [0,1]. 0.05 UV unit ở full HD là ~54px — đủ để thấy hiệu ứng mà không bị artifact.

> **Chỉ hoạt động khi có face detection.** Nếu `detectFace()` chưa được gọi hoặc không detect được mặt, reshape pass-through.

---

### `setMakeupParams(float blusher)`

```java
BeautyFilterNative.setMakeupParams(0.0f);
```

| Param | Range (UI) | Internal range | Mapping | Filter |
|---|---|---|---|---|
| `blusher` | 0.0 – 10.0 | 0.0 – 1.0 | `x / 10.0` | `blendLevel` alpha của BlusherFilter |

`blendLevel = 0` → blusher invisible, `blendLevel = 1` → blusher 100% opaque.

> **Chỉ hoạt động khi có face detection.**

---

## Default Values (trong MainActivity)

```java
// Native defaults set in nativeInit()
g_beauty->SetBlurAlpha(0.7f);
g_beauty->SetWhite(0.1f);

// Runtime values are read from seekSmoothing/seekWhitening/seekSlim/seekEye/seekBlush
// and pushed through setBeautyParams/setReshapeParams/setMakeupParams.
```

---

## Error Handling

Native code log qua Android `__android_log_print` với tag `BeautyFilter` (`INFO` và `ERROR`). Không throw exception về Java — check return value của `init()` và `processInto()`.

```bash
# Xem native logs
adb logcat -s BeautyFilter
```
