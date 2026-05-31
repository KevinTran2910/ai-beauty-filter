# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

**AI Beauty Filter SDK** (internal name: GPUPixel) — a GPU-accelerated real-time beauty filter C++ library targeting WebAssembly (primary/working build), iOS, and Linux. The library outputs `libbeautyfilter.a` and is integrated into React Native apps via an Objective-C native module bridge.

## Build commands

### WASM (primary working build)

Requires Emscripten (`emcc`) on PATH:

```bash
# First-time Emscripten setup
git clone https://github.com/emscripten-core/emsdk.git
cd emsdk && ./emsdk install latest && ./emsdk activate latest && source ./emsdk_env.sh

# Build
./script/build_wasm.sh

# Serve demo
cd output/bin && python3 serve.py   # → http://localhost:8080
```

### iOS (macOS + Xcode required)

```bash
./script/build_ios.sh
# Output: output/ios/lib/libbeautyfilter.a + output/ios/include/
```

### Manual CMake (Linux desktop)

```bash
mkdir -p build/linux && cd build/linux
cmake ../.. -DCMAKE_BUILD_TYPE=Release
make -j$(nproc)
```

### Code formatting

```bash
./script/format_code.sh   # clang-format for C++/ObjC, cmake-format for CMakeLists
```

Style rules are in `.clang-format` at the repo root.

## Architecture

### Layer model

```
React Native JS/TS
    ↓  Native Module bridge (ObjC)
C++ library: libbeautyfilter  (src/)
    ↓  OpenGL ES / WebGL2 calls
GPU: textures, GLSL shaders, framebuffers
```

### Filter pipeline (data flow per frame)

```
Raw BGRA frame → FaceDetector (ncnn, 106 landmarks)
              → BlusherFilter     (landmark-aware blush)
              → FaceReshapeFilter (landmark-aware warp)
              → BeautyFaceFilter  (bilateral blur + whitening)
              → SinkView / SinkRawData
```

All filters inherit from `Filter` (in `src/filter/filter.cc`). Chains are built using `FilterGroup`. Sources push frames; sinks consume them.

### Key source directories

| Path | Purpose |
|------|---------|
| `src/core/` | GL context, framebuffer, shader program management |
| `src/filter/` | All filter implementations (~40 filters) |
| `src/source/` | `SourceRawData`, `SourceImage` — frame ingress |
| `src/sink/` | `SinkRawData`, `SinkRender`, `SinkView` (iOS only) |
| `src/face_detector/` | ncnn-based detector (non-WASM) / custom detector (WASM) |
| `src/res/` | Texture assets embedded into the library |
| `include/gpupixel/` | Public API headers (all external consumers use these) |
| `third_party/` | ncnn, stb, glad, libyuv, custom-face-landmark |
| `demo/wasm_html/` | WASM demo app (`wasm_app.cc`, `index.html`) |
| `demo/ios/` | iOS entry point (`ios_beauty.mm`) |
| `core_ai/` | Standalone face landmark WASM sub-project (separate build) |

### Platform-specific behavior

**WASM**: uses `custom_face_detector.cc` (not ncnn); requires WebGL2 + SharedArrayBuffer; WASM link flags set `PTHREAD_POOL_SIZE=8`, SIMD128, `ALLOW_MEMORY_GROWTH`.

**iOS**: all `.cc` files compiled as ObjC++ via Xcode attribute `GCC_INPUT_FILETYPE=sourcecode.cpp.objcpp`; min deployment target iOS 13; requires `OpenGLES`, `AVFoundation`, `CoreVideo`, `CoreMedia` frameworks.

### Exported WASM API

The build exports these C functions for JS consumption:
`_GPUPixel_Initialize`, `_GPUPixel_CreatePipeline`, `_GPUPixel_AddFilter`, `_GPUPixel_SetFilterParameter`, `_GPUPixel_ProcessImage`, `_GPUPixel_Destroy`, `_malloc`, `_free`.

Module is loaded as `createBeautyFilter()` (Emscripten `MODULARIZE=1`, `EXPORT_NAME='createBeautyFilter'`).

### CMake build options

| Option | Default | Effect |
|--------|---------|--------|
| `GPUPIXEL_BUILD_SHARED_LIBS` | OFF | Build shared instead of static lib |
| `GPUPIXEL_ENABLE_FACE_DETECTOR` | ON | Include ncnn face detection |
| `GPUPIXEL_BUILD_DESKTOP_DEMO` | ON | Build the demo app target |

Platform is auto-detected from `CMAKE_SYSTEM_NAME` into `CURRENT_PLAT` (`wasm`/`ios`/`android`/`linux`/`mac`).

### Resource management

Filter textures (lookup tables, blusher, etc.) are in `src/res/`. AI models live in `third_party/custom-face-landmark/models/`. At runtime, call `GPUPixel::SetResourceRoot(path)` before any filter creation; then use `GPUPixel::GetResourcePath(name)` to resolve assets.

### React Native integration

The ObjC native module layer lives in `demo/ios/`: `GPUPixelCameraView.mm` owns the `AVCaptureSession` and calls `SourceRawData::ProcessData()` each camera frame. JS calls `setBeautyParams({ smoothing, whitening })` which maps to `_beauty->SetBlurAlpha()` / `_beauty->SetWhite()`.

## Current status (as of 2026-05-31)

- WASM build: fully working
- iOS build: ready to run on macOS — `./script/build_ios.sh` produces `BeautyFilter.xcframework`
  - Face detection enabled (`mars-face-kit` + `MNN.framework` ported from gpupixel)
  - Output: `output/ios/BeautyFilter.xcframework`, `output/ios/models/`, `output/ios/res/`, `output/ios/MNN.framework/`
  - GitHub Actions CI: `.github/workflows/build-ios.yml` (runs on macOS, face detection ON)
- Android: not yet implemented (`PLAT_ANDROID` branch in CMake is a stub)