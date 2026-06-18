# Architecture Overview

## Data Flow Tổng Quan

```
┌─────────────────────────────────────────────────────────┐
│                    UI Thread (Java)                      │
│  MainActivity                                            │
│  ┌──────────┐  ┌─────────┐  ┌──────────┐  ┌─────────┐  │
│  │ SeekBars │  │ Buttons │  │ TextureV │  │ Status  │  │
│  └──────────┘  └─────────┘  └──────────┘  └─────────┘  │
└──────────────────────────┬──────────────────────────────┘
                           │ post()
┌──────────────────────────▼──────────────────────────────┐
│              cameraExecutor (single thread)              │
│                                                          │
│  1. buildUpright(frame)   ← rotation + selfie mirror     │
│  2. runDetect()           ← every 2nd frame, 320px max   │
│  3. processFrame()        ← BeautyFilterNative calls     │
│  4. deliver()             ← get output ByteBuffer        │
│  5. drawToTexture()       ← blit to TextureView          │
└──────────────────────────┬──────────────────────────────┘
                           │ JNI (Direct ByteBuffer)
┌──────────────────────────▼──────────────────────────────┐
│              C++ Native (libbeautyfilter.so)             │
│                                                          │
│  BeautyFilterNative static methods                       │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ init / destroy / setBeautyParams / setReshapeParams │ │
│  │ setMakeupParams / detectFace / processInto          │ │
│  └──────────────────────────┬────────────────────────┘ │
│                             │                            │
│  ┌──────────────────────────▼────────────────────────┐  │
│  │              Filter Pipeline (OpenGL ES 3)        │  │
│  │                                                    │  │
│  │  SourceRawData                                     │  │
│  │       │                                            │  │
│  │       ▼                                            │  │
│  │  BlusherFilter  ◄── face landmarks (106 pts)       │  │
│  │       │                                            │  │
│  │       ▼                                            │  │
│  │  FaceReshapeFilter ◄── face landmarks (106 pts)    │  │
│  │       │                                            │  │
│  │       ▼                                            │  │
│  │  BeautyFaceFilter  (3 internal nodes)              │  │
│  │    BilateralFilter ─────────────────┐              │  │
│  │    BoxHighPassFilter ───────────────┤              │  │
│  │    BeautyFaceUnitFilter ◄───────────┘              │  │
│  │       │                                            │  │
│  │       ▼                                            │  │
│  │  SinkRawData → glReadPixels → ByteBuffer out       │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │  GPUPixelContext (singleton)                      │    │
│  │  EGL: Display + Config + Surface + Context        │    │
│  │  DispatchQueue (1 GL worker thread)               │    │
│  │  FramebufferFactory (FBO pool)                    │    │
│  └──────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

## Threading Model

### Contract bất biến (KHÔNG được vi phạm)

```
Java side:  tất cả native calls phải đi qua cameraExecutor (Executors.newSingleThreadExecutor())
C++ side:   tất cả GL calls đều chạy trên GPUPixelContext.DispatchQueue (1 thread)
```

Hai thread này hoạt động song song nhưng không cùng lúc truy cập native state. Java post lên `cameraExecutor`, executor call JNI, JNI dispatch lên GL thread qua `SyncRunWithContext()` (blocking). Kết quả là:

- Không cần mutex phía Java
- `processInto` block cho đến khi GL hoàn thành
- Không bao giờ gọi `BeautyFilterNative.*` từ UI thread hoặc CameraX callback thread trực tiếp

### CameraX Integration

```java
imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
    // Đây đã chạy trên cameraExecutor — safe để gọi native
    processFrame(imageProxy);
    imageProxy.close();
});
```

`OUTPUT_IMAGE_FORMAT_RGBA_8888` được chọn để tránh YUV conversion overhead trên Java side. Conversion YUV→RGB có thể làm trong shader nếu cần (SourceRawData hỗ trợ cả hai).

## Face Detection Strategy

```
Frame N:   detect (downscale đến max 320px, gọi detectFace())
Frame N+1: skip detect, dùng landmarks từ frame N
Frame N+2: detect lại
...
```

Landmarks được normalize về [0,1] bởi mars-face-kit. Filter C++ nhận float array 212 phần tử (106 điểm × 2 tọa độ). Khi không có mặt (`has_face_ = false`), BlusherFilter và FaceReshapeFilter tự động no-op — BeautyFaceFilter vẫn chạy vì không cần landmark.

## Bộ nhớ và Performance

| Kỹ thuật | Mục đích |
|---|---|
| `ByteBuffer.allocateDirect()` | Zero-copy JNI — không copy heap Java ↔ native |
| `FBO pool` (FramebufferFactory) | Tái dụng GL framebuffer, tránh alloc/free per-frame |
| Detection mỗi 2 frame | Giảm 50% CPU cost của face detector |
| Downscale 320px cho detect | mars-face-kit chạy nhanh hơn; landmark sau đó apply lên full-res |
| `STRATEGY_KEEP_ONLY_LATEST` | ImageAnalysis drop frame cũ khi pipeline bận |

## Cấu trúc file native

```
src/
  android/jni/
    jni_bridge.cc       # 7 native methods, global pipeline state
    jni_helpers.h       # JStringToStdString, Android logging macros
  core/
    gpupixel_context.*  # EGL context + DispatchQueue singleton
    gpupixel_framebuffer.*       # FBO + texture wrapper
    gpupixel_framebuffer_factory.* # FBO pool
    gpupixel_program.*  # GLSL program wrapper
  source/
    source_raw_data.cc  # RGBA/I420 input → GL texture upload
  sink/
    sink_raw_data.cc    # glReadPixels → ByteBuffer / I420
  filter/
    beauty_face_filter.*       # FilterGroup: Bilateral+BoxHP+Unit
    beauty_face_unit_filter.*  # Core smoothing+whitening shader
    bilateral_filter.*         # Bilateral mono filter (H+V passes)
    face_reshape_filter.*      # Landmark-driven mesh warp
    blusher_filter.*           # Extends FaceMakeupFilter
    face_makeup_filter.*       # Base: landmark texture overlay
  face_detector/
    face_detector.*            # mars-face-kit wrapper
  utils/
    dispatch_queue.*    # Single-thread executor for GL work
    util.*              # Resource path mgmt, logging
```
