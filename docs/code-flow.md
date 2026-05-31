# Code Flow — ai-beauty-filter

## Architecture overview

```
[Camera / raw pixels]
        │
        ▼
  SourceRawData          ← CPU → GPU upload (pixels → OpenGL texture)
        │
        ▼
  BlusherFilter          ← GPU pass: landmark-aware blush overlay
        │
        ▼
  FaceReshapeFilter      ← GPU pass: landmark-aware face warp (slim + eye)
        │
        ▼
  BeautyFaceFilter       ← GPU pass group: bilateral blur + high-pass + whiten
        │
        ▼
  SinkView / SinkRawData ← final GPU → screen or CPU readback
```

All nodes share `GPUPixelFramebuffer` objects as the data transport. Each node reads
from one framebuffer and writes to another. The pipeline is a directed graph;
`AddSink()` wires edges.

---

## 1. Startup / initialization

### Entry point — iOS C API (`demo/ios/ios_beauty.mm`)

```
GPUPixelIOS_Init(void* view, const char* resPath)
  ├── GPUPixel::SetResourceRoot(resPath)          → stores in Util::resource_root_path_
  ├── SourceRawData::Create()                     → allocs GL textures on GPU context thread
  ├── SinkView::Create(view)                      → embeds ObjcView (CAEAGLLayer) in the UIView
  ├── BeautyFaceFilter::Create()                  → builds bilateral + high-pass filter group
  ├── FaceReshapeFilter::Create()                 → compiles warp GLSL shader
  ├── BlusherFilter::Create()                     → compiles blush lookup shader
  ├── FaceDetector::Create()  [if enabled]        → init mars_vision::MarsFaceLandmarker
  │     └── loads face_det.mars_model + face_align.mars_model from resPath/models/
  └── Wire pipeline:
        source → blusher → reshape → beauty → sinkView
```

### Entry point — React Native (`demo/react-native/ios/GPUPixelCameraView.mm`)

```
[GPUPixelCameraView startCamera]
  ├── _setupGPUPixelPipeline       → same filter wiring as above
  └── _setupCaptureSession         → AVCaptureSession, back camera, 30fps
        └── AVCaptureVideoDataOutput → delegate: self
              (captureOutput:didOutputSampleBuffer:)
```

### Entry point — WASM (`demo/wasm_html/wasm_app.cc`)

```
GPUPixel_Initialize(canvas_id)
GPUPixel_CreatePipeline()           → creates filters, wires graph
GPUPixel_AddFilter(name, params)
GPUPixel_ProcessImage(ptr, w, h)
```

---

## 2. GPU context (`src/core/gpupixel_context.cc`)

A singleton `GPUPixelContext` owns the OpenGL (ES) context and a dedicated serial
dispatch queue. **All GL calls must happen on this thread.** The context provides:

- `SyncRunWithContext(lambda)` — run a block on the GL thread, block caller until done
- `GetFramebufferFactory()` — pool of reusable `GPUPixelFramebuffer` objects
- `GetEglContext()` — the `EAGLContext*` (iOS) / WebGL context (WASM)
- `PresentBufferForDisplay()` — flushes the renderbuffer to screen

On iOS, `iOSHelper` observes `UIApplicationWillResignActiveNotification` to pause
rendering when the app backgrounds.

---

## 3. Per-frame flow (iOS)

```
GPUPixelIOS_ProcessFrame(rgbaData, width, height)
  │
  ├── [CPU thread] FaceDetector::Detect(rgbaData, w, h)      [if enabled]
  │     ├── wraps pixels in mars_vision::MarsImage
  │     ├── calls MarsFaceLandmarker::Detect()               → runs MNN neural net
  │     └── returns vector<float> of 111 (x,y) pairs, normalised by width/height
  │
  ├── blusher->SetFaceLandmarks(landmarks)
  ├── reshape->SetFaceLandmarks(landmarks)
  │
  └── source->ProcessData(rgbaData, w, h, stride, RGBA)
        └── SyncRunWithContext:
              ├── GenerateTextureWithPixels()
              │     ├── glTexImage2D → upload RGBA bytes to GL_TEXTURE_2D
              │     ├── bind framebuffer, run identity shader, glDrawArrays
              │     └── Deactivate framebuffer
              └── Source::DoRender(true)
                    └── for each sink: sink->SetInputFramebuffer(fb) + sink->Render()
```

### React Native per-frame (camera path)

```
captureOutput:didOutputSampleBuffer:
  ├── CVImageBufferRef → lock base address → uint8_t* rgba
  ├── FaceDetector::Detect(...)
  ├── SetFaceLandmarks on blusher + reshape
  └── source->ProcessData(...)
```

---

## 4. Filter internals

### `BlusherFilter` (`src/filter/blusher_filter.cc`)

- Reads stored face landmarks
- Uploads landmark floats as a GLSL uniform array
- GLSL shader samples `blusher.png` and blends it at landmark-driven cheek positions
- Lookup table sets blush colour

### `FaceReshapeFilter` (`src/filter/face_reshape_filter.cc`)

- Two GLSL uniforms: `thinFaceDelta` and `bigEyeDelta`
- Shader implements `curveWarp()` and `enlargeEye()` distortion functions
- Reads `facePoints[106 * 2]` uniform array
- `thinFace()` pushes the jawline inward using 9 landmark pairs
- `enlargeEye()` expands the eye region around pupil landmarks
- Output is a distorted texture — no skin processing

### `BeautyFaceFilter` (`src/filter/beauty_face_filter.cc`) — a `FilterGroup`

Internal sub-graph:

```
input ──► BilateralFilter ──► BeautyFaceUnitFilter ──► output
input ──► BoxHighPassFilter ──►        ↑
```

- **BilateralFilter**: edge-preserving blur (smoothing). Controlled by `SetBlurAlpha()`.
- **BoxHighPassFilter**: subtracts blurred image from original to extract fine detail.
- **BeautyFaceUnitFilter**: recombines —
  `result = blurred + highpass * detail_strength + whitening`.
  `SetWhite()` controls the additive brightness.

### `SourceRawData` (`src/source/source_raw_data.cc`)

Supports three input pixel formats via `ProcessData()`:

| Format constant | GL upload |
|-----------------|-----------|
| `GPUPIXEL_FRAME_TYPE_RGBA` | `glTexImage2D(GL_RGBA)` |
| `GPUPIXEL_FRAME_TYPE_BGRA` | `glTexImage2D(GL_BGRA)` — iOS/macOS only |
| `GPUPIXEL_FRAME_TYPE_YUVI420` | 3 separate luminance textures (Y, U, V); shader converts to RGB |

Allocates / reuses a `GPUPixelFramebuffer` matching the input dimensions, then calls
`Source::DoRender()` which walks the `sinks_` list and triggers downstream rendering.

---

## 5. Rendering output

### `SinkView` (iOS) — `src/sink/sink_view.mm` + `src/sink/objc_view.mm`

```
SinkView::SetInputFramebuffer(fb)     → passes to ObjcView
SinkView::Render()
  └── [ObjcView DoRender]             → SyncRunWithContext:
        ├── glBindFramebuffer(displayFramebuffer)
        ├── glBindTexture(inputFramebuffer->GetTexture())
        ├── glDrawArrays(GL_TRIANGLE_STRIP)   ← full-screen quad
        └── glBindRenderbuffer + PresentBufferForDisplay()
              └── [EAGLContext presentRenderbuffer]  → screen
```

`ObjcView` is a `UIView` subclass with `layerClass = CAEAGLLayer`. Layout changes
trigger `destroyDisplayFramebuffer` + `createDisplayFramebuffer` to resize the
renderbuffer.

### `SinkRawData` (`src/sink/sink_raw_data.cc`)

Used on WASM to read pixels back to CPU memory:
`glReadPixels()` → uint8_t buffer → JavaScript callback.

---

## 6. Face detection internals (`src/face_detector/face_detector.cc`)

### iOS path (mars_vision + MNN)

```
FaceDetector::FaceDetector()
  ├── Util::GetResourcePath("models")  → resPath + "/models"
  ├── MarsFaceLandmarker::Create()     → MNN-backed landmarker
  └── Init({model_path, RunningMode::VIDEO})
        ├── loads face_det.mars_model   → face bounding-box detector
        └── loads face_align.mars_model → 111-point landmark regressor

FaceDetector::Detect(pixels, w, h, stride, fmt, type)
  ├── fill MarsImage struct
  ├── MarsFaceLandmarker::Detect(image, results)
  │     └── MNN runs face_det → crops ROI → face_align → 111 key_points
  └── returns vector<float>[222]:  [x0/w, y0/h, x1/w, y1/h, ... × 111]
```

### WASM path (`src/face_detector/custom_face_detector.cc`)

Uses ncnn + custom-face-landmark library (yoloface + landmark106 models).
Same output format: normalised (x, y) pairs.

---

## 7. Resource management

```
Util::SetResourceRoot(path)            → sets resource_root_path_
Util::GetResourcePath("models")
  ├── iOS: if root set → root + "/models"
  │         else      → NSBundle resourcePath + "/models"
  └── WASM / Linux: root + "/models"  (or just "models" if root is empty)
```

Filter textures (`blusher.png`, `mouth.png`, `lookup_*.png`) are loaded at filter
creation time. Face detection models are loaded only when `FaceDetector` is
constructed. Both are resolved through `Util::GetResourcePath`.

---

## 8. Build outputs per platform

| Platform | Primary entry point | Library output | Final artifact |
|----------|---------------------|----------------|----------------|
| **WASM** | `wasm_app.cc` (C exports) | `libbeautyfilter.a` | `app.wasm` + `app.js` + `app.data` |
| **iOS** | `ios_beauty.mm` C API or `GPUPixelCameraView.mm` RN bridge | `libbeautyfilter.a` (3 slices) | `BeautyFilter.xcframework` |
| **Linux** | `demo/` desktop app | `libbeautyfilter.a` | desktop binary |

The WASM C exports (`_GPUPixel_Initialize` through `_GPUPixel_Destroy`) are thin
wrappers around the same `SourceRawData` / filter / `SinkRawData` C++ objects that
run on all platforms.

---

## 9. Directory map

```
src/
├── core/           GL context, framebuffer pool, shader program compilation
├── filter/         ~40 filter implementations (all inherit from Filter/FilterGroup)
├── source/         SourceRawData, SourceImage — frame ingress, CPU→GPU upload
├── sink/           SinkRawData, SinkRender, SinkView (iOS) — GPU→screen or readback
├── face_detector/  FaceDetector (iOS: mars_vision; WASM: custom ncnn)
├── utils/          Util (resource paths, logging), DispatchQueue, MathToolbox
└── res/            Embedded textures (PNGs baked into the library at build time)

include/gpupixel/   Public API headers (consumers only include these)
third_party/
├── mars-face-kit/  iOS prebuilt: libmars-face-kit.a + mars_vision headers + .mars_model files
├── mnn/            iOS prebuilt: MNN.framework (neural network inference)
├── custom-face-landmark/  WASM prebuilt: liblnm_face_landmark.a + ncnn models
├── ncnn/           WASM prebuilt: libncnn.a
├── libyuv/         YUV↔RGB conversion (built from source, all platforms)
├── glad/           OpenGL loader (WASM only)
└── stb/            Header-only image load/save

demo/
├── ios/            ios_beauty.h/.mm — standalone iOS C API entry point
├── react-native/   GPUPixelCameraView, GPUPixelModule — React Native bridge
└── wasm_html/      wasm_app.cc + index.html — browser demo

script/
├── build_ios.sh    Builds BeautyFilter.xcframework (3 slices + lipo + xcframework)
└── build_wasm.sh   Builds app.wasm via Emscripten

cmake/
└── ios.toolchain.cmake   Full iOS/tvOS/watchOS/visionOS CMake toolchain
```
