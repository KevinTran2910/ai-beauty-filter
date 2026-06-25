# DAYO Beauty Filter (iOS) — Presentation

> Slide-style. Each `## ` section is one slide. The *Talking points* lines are
> speaker notes.

---

## Slide 1 — Introduction

**DAYO Beauty Filter — iOS** — a **real-time** beauty camera app for iPhone, built
with **React Native + a native bridge**.

- Camera/image → skin smoothing, whitening, face slimming, eye enlargement, blush
  → displayed instantly.
- Runs on the GPU through the **GPUPixel** engine.
- A three-layer architecture: **React Native (UI)** + **ObjC++ (bridge)** +
  **C++/OpenGL (prebuilt engine)**.

*Talking points:* "This is a real-time beauty app for iOS. The C++ engine does the
heavy lifting on the GPU; a thin ObjC++ bridge connects it to React Native."

---

## Slide 2 — The Problem

Real-time beautification is hard in three ways:

1. **Speed** — ~30 frames per second, each 720p frame has hundreds of thousands
   of pixels to process.
2. **Latency** — the image must appear almost instantly, with no stutter.
3. **Face-aware effects** — face slim / eye enlarge / blush need to know where the
   eyes, mouth, and face contour are.

→ Solution: push image processing onto the **GPU** (GPUPixel), and use **AI
landmark detection** (mars-face-kit).

---

## Slide 3 — Tech Stack

| Layer | Technology |
|---|---|
| App / UI | React Native + TypeScript, sliders, image picker |
| Bridge | ObjC++ `RCTViewManager` (prop-driven) |
| Camera | `AVCaptureSession`, front camera, BGRA `1280x720` |
| Image processing | C++17, **GPUPixel**, **OpenGL ES**, packaged as `BeautyFilter.xcframework` |
| Face detection | **MNN.framework** + **libmars-face-kit.a** (device only) |
| Resources | CocoaPods copies `res/` + `models/` into the app bundle |
| Build | CocoaPods + `bootstrap.sh` (generates the RN app) |
| Devices | iOS 15.1+, arm64 |

---

## Slide 4 — Overall Architecture

```
┌──────────────── React Native (TypeScript) ────────────────┐
│  App.tsx  →  props (smoothing, whitening, faceSlim, ...)   │
└───────────────────────────────┬───────────────────────────┘
                                 │  RCTViewManager (prop-driven)
┌────────────────────────────────▼──────────────────────────┐
│              ObjC++ Bridge (native-bridge/*.mm)            │
│   GPUPixelCameraView / GPUPixelImageView                  │
└────────────────────────────────┬──────────────────────────┘
                                 │  shared_ptr<...> (C++)
┌────────────────────────────────▼──────────────────────────┐
│          C++ / OpenGL ES — GPUPixel (xcframework)         │
│   SourceRawData → Blusher → Reshape → BeautyFace → SinkView│
└───────────────────────────────────────────────────────────┘
```

- **React Native** handles: UI, sliders, image picker, passing parameters via
  props.
- **ObjC++** handles: camera (AVFoundation), image decode, view lifecycle.
- **C++/GPU** handles: color conversion, beauty filters, face warping, rendering.

*Talking points:* "The frame source differs from other platforms — on iOS it is
AVFoundation + ObjC++ — but the GPU pipeline is the same shared C++ engine."

---

## Slide 5 — One Frame (Camera)

1. `AVCaptureSession` returns a **BGRA** frame from the front camera (already
   rotated and mirrored by AVFoundation).
2. Face detection runs on the frame → updates landmarks.
3. The BGRA pixel pointer is pushed straight into `SourceRawData::ProcessData`.
4. GPU: converts BGRA→RGB in a shader, runs the filter chain.
5. Renders straight into **`SinkView`** (a `CAEAGLLayer` on the `UIView`) — **no
   readback**.

→ Both camera and static-image modes render directly; **the image is never copied
back to the CPU**.

---

## Slide 6 — Filter Pipeline

```
SourceRawData (BGRA/RGBA → RGB)
   → BlusherFilter        (blush, landmark-driven)
   → FaceReshapeFilter    (face slim + eye enlarge, landmark warp)
   → BeautyFaceFilter     (skin smoothing + whitening)
   → SinkView (render to screen)
```

| Effect | Filter | Needs landmarks? |
|---|---|---|
| Skin smoothing | `BeautyFaceFilter` | No |
| Whitening | LUT (`BeautyFaceUnitFilter`) | No |
| Face slim | `FaceReshapeFilter` | Yes |
| Eye enlarge | `FaceReshapeFilter` | Yes |
| Blush | `BlusherFilter` | Yes |

*Talking points:* "Three of the five effects need landmarks — device only. The
simulator can still do smoothing + whitening."

---

## Slide 7 — Face Landmarks

- Uses **mars-face-kit + MNN** → returns `[0,1]`-normalized landmarks.
- Drives: the face contour (face slim), the eyes (eye enlarge), the cheeks
  (blush).
- **Device only:** MNN + mars only ship an `iphoneos` slice. The simulator slice
  is built with the face detector turned off.
- The camera uses `VIDEO` mode (BGRA); static images use `PICTURE` mode (RGBA).

*Talking points:* "Detection currently runs every frame at full resolution —
that's the first thing we'd optimize for FPS."

---

## Slide 8 — Performance

1. **No readback** — both camera and image render straight into `SinkView`.
2. **BGRA straight to the GPU** — the camera produces BGRA; color conversion
   happens in a shader.
3. **Rotation/mirroring by AVFoundation** — `videoOrientation` + `videoMirrored`,
   no CPU rotation.
4. **`alwaysDiscardsLateVideoFrames`** — drops late frames while the pipeline is
   busy.
5. **Coalesced reprocessing (static image)** — multiple slider changes collapse
   into one render at the end of the run loop.
6. **Per-view pipeline** — each view has its own graph; no global-state
   contention.

> **Watch out:** face detection runs **every frame at full resolution**. That is
> the first candidate for raising FPS.

---

## Slide 9 — App Features

- 🖼️ **Image** mode: pick a photo from the library → run it through the beauty
  pipeline (works on the simulator too).
- 📷 **Camera** mode: real-time front camera (physical iPhone only).
- 🎚️ **Five sliders** `0..10`: smoothing, whitening, face slim, eye enlarge,
  blush.
- ⚙️ Fully **prop-driven** control — changing a prop re-renders.

---

## Slide 10 — Two Processing Paths

| | **Camera** (live) | **Static image** |
|---|---|---|
| View | `GPUPixelCameraView` | `GPUPixelImageView` |
| Input | BGRA from AVCaptureSession | RGBA from UIImage decode |
| Detect mode | `VIDEO` | `PICTURE` |
| Trigger | auto-starts when the view appears | reprocess on prop change |
| Output | `SinkView` (live) | `SinkView` (once per run loop) |
| Readback | **No** | **No** |

*Talking points:* "Same GPU pipeline, same `SinkView` destination. Only the frame
source and the render trigger differ."

---

## Slide 11 — Shared Engine

- The **GPUPixel C++ engine is shared** with other platforms.
- The ObjC++ bridge (`GPUPixelCameraView.mm`) calls the same engine API.
- The parameter scaling is consistent (`smoothing/10`, `whitening/20`,
  `faceSlim/200`, `eyeEnlarge/100`, `blusher/10`).
- → One C++ core, multiple platforms.

---

## Slide 12 — Suggested Demo

1. Open the app → **Image** tab → pick a portrait → see the default beautify.
2. Drag **smoothing / whitening** → skin changes immediately (works on the
   simulator).
3. On a physical iPhone → **Camera** tab → grant permission → see real-time
   beauty.
4. Drag **face slim / eye enlarge / blush** → warping + blush follow the face.

---

## Slide 13 — Summary

- An iOS beauty app: **React Native orchestration + ObjC++ bridge + C++/OpenGL
  engine**.
- Reuses the shared GPUPixel engine → less effort, consistent effects.
- Renders straight to `SinkView`, no readback in either mode.
- Five effects; three of them follow the face via AI landmarks (device only).

**Detailed docs:** `reference/architecture.md`, `reference/filter-pipeline.md`,
`reference/api-bridge.md`, `reference/face-landmarks.md`,
`getting-started/build-guide.md`, `reference/assets-and-textures.md`,
`reference/extending-filters.md`.
