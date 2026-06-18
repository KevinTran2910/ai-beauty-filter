# Architecture

## Tổng Quan

App có 2 lớp chính:

- Java Android app: UI, CameraX, permission, image picker, capture, copy assets.
- Native C++ library `libbeautyfilter.so`: GPUPixel filter graph, EGL/OpenGL ES 3 rendering, JNI, face detector wrapper.

Luồng camera realtime hiện tại ưu tiên preview native trực tiếp để tránh `glReadPixels` mỗi frame.

```text
CameraX ImageAnalysis (YUV_420_888)
  -> MainActivity.analyze() trên cameraExecutor
  -> detect mỗi 2 frame từ Y plane downscale max 320px
  -> BeautyFilterNative.processPreviewYuv(...)
  -> JNI repack Android420ToI420 bằng libyuv
  -> SourceRawData upload I420
  -> shader YUV to RGB + rotation/mirror
  -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter
  -> SinkRender
  -> ANativeWindow / SurfaceView
```

Capture và image mode dùng path off-screen có CPU readback:

```text
Camera capture:
  YUV_420_888 -> processIntoYuv(...) -> SinkRawData -> direct ByteBuffer -> Bitmap -> JPEG

Picked image:
  Bitmap ARGB_8888 -> direct RGBA ByteBuffer -> processInto(...) -> direct ByteBuffer -> Bitmap
```

## Java Layer

`MainActivity.java` chịu trách nhiệm:

- Request `CAMERA` permission.
- Bind CameraX `ImageAnalysis`.
- Chọn camera trước/sau.
- Tạo `SurfaceView` preview và attach surface xuống native bằng `setOutputSurface`.
- Copy `assets/res` và `assets/models` vào `getFilesDir()` trước khi gọi `init`.
- Điều khiển 5 slider: smoothing, whitening, face slim, eye enlarge, blusher.
- Chạy detection throttle mỗi `DETECT_EVERY = 2` frame.
- Lưu ảnh capture qua `MediaStore` trên API 29+.
- Hiển thị performance overlay.

UI hiện là Android View/XML, không dùng Compose.

## Native Layer

`BeautyFilterNative.java` load:

```java
System.loadLibrary("mars-face-kit");
System.loadLibrary("beautyfilter");
```

`mars-face-kit` được load trong `try/catch` để build không bật detector vẫn chạy được.

JNI implementation nằm ở `src/android/jni/jni_bridge.cc`. Native giữ global pipeline state:

```text
SourceRawData
BlusherFilter
FaceReshapeFilter
BeautyFaceFilter
SinkRawData
SinkRender
FaceDetector
```

Khi `GPUPIXEL_ENABLE_FACE_DETECTOR=ON`, graph là:

```text
SourceRawData -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter -> Sink
```

Khi detector OFF, graph rút gọn:

```text
SourceRawData -> BeautyFaceFilter -> Sink
```

`Sink` được chuyển động theo use case:

- Preview realtime attach `SinkRender`, dùng window surface.
- Capture/image mode attach `SinkRawData`, dùng pbuffer surface và readback.

## Threading Contract

Java side serialize toàn bộ native access qua:

```java
private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
```

Các việc chạy trên executor này:

- `init`
- parameter setters
- `detectFace`
- `processPreviewYuv`
- `processIntoYuv`
- `processInto`
- `destroy`

Native side vẫn dùng lock nội bộ trong `jni_bridge.cc` và GL work đi qua `GPUPixelContext`. Không gọi `BeautyFilterNative.*` trực tiếp từ UI thread cho các path có đụng native state.

## Camera Pipeline

CameraX config chính:

- `ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888`
- `STRATEGY_KEEP_ONLY_LATEST`
- target resolution `1920x1080` với fallback closest higher then lower
- Camera2Interop set `CONTROL_AE_TARGET_FPS_RANGE` theo range cao nhất camera hỗ trợ

Preview path:

1. `ImageProxy` nhận từ CameraX.
2. Nếu tới lượt detect, Java đọc Y plane, downscale thành grayscale bitmap nhỏ, rotate/mirror đúng hướng preview, copy thành direct RGBA buffer.
3. `detectFace` update landmarks trong native.
4. Java truyền 3 YUV planes, stride và `rotationMode` xuống `processPreviewYuv`.
5. Native dùng `libyuv::Android420ToI420` để repack thành I420 tight buffer.
6. `SourceRawData` upload I420, shader convert YUV to RGB và áp dụng rotation/mirror.
7. Filter graph render ra `SinkRender`.

## Rotation Và Mirror

Java tính `rotationMode` theo `ImageProxy.getImageInfo().getRotationDegrees()` và camera trước/sau. Các mode là ordinal của `gpupixel::RotationMode`:

```text
0 NoRotation
1 RotateLeft
2 RotateRight
3 FlipVertical
4 FlipHorizontal
5 RotateRightFlipVertical
6 RotateRightFlipHorizontal
7 Rotate180
```

Selfie mirror được bake vào `rotationMode`, nên native set `g_render->SetMirror(false)`.

## Face Detection Strategy

Detector không chạy trên frame full-res. Java tạo ảnh detection nhỏ:

- longest side tối đa `320px`
- lấy từ Y plane để giảm chi phí
- rotate/mirror giống preview output
- gửi RGBA direct buffer vào `detectFace`

Landmarks được normalize `[0,1]` trong không gian ảnh detection. Vì ảnh detection đã cùng orientation với frame render, landmarks apply được cho full-res output.

## Assets

Android assets không được native đọc trực tiếp trong APK. `MainActivity.initNative()` copy:

```text
app/src/main/assets/res    -> getFilesDir()/res
app/src/main/assets/models -> getFilesDir()/models
```

Sau đó gọi:

```java
BeautyFilterNative.init(getFilesDir().getAbsolutePath());
```

Native dùng `GPUPixel::SetResourceRoot(path)` và load texture/model từ filesystem.

## Performance Notes

Các điểm tối ưu đang có:

- Camera input YUV native format, tránh CameraX RGBA conversion.
- Preview render trực tiếp ra `SurfaceView`, không readback mỗi frame.
- `STRATEGY_KEEP_ONLY_LATEST` bỏ frame cũ khi pipeline bận.
- Detection chạy mỗi 2 frame, input nhỏ max 320px.
- Direct `ByteBuffer` để JNI lấy address trực tiếp.
- FBO/texture reuse trong GPUPixel.
- `libyuv` chỉ repack YUV planes sang I420 tight buffer, color conversion chính ở shader.
