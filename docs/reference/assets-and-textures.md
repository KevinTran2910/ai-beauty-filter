# Assets & Textures

iOS does not copy assets manually. CocoaPods copies `res/` and `models/` into the
**app bundle** at build time:

```ruby
# BeautyFilterSDK.podspec
s.resources = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models']
```

Native code resolves resources through the bundle's resource path:

```objc
NSString* resPath = [[NSBundle mainBundle] resourcePath];
GPUPixel::SetResourceRoot([resPath UTF8String]);
```

The engine then loads textures/models using paths relative to `resPath`
(`res/...`, `models/...`).

> CocoaPods copies the files verbatim through its copy-resources script (**no PNG
> compression**), so the LUT `.png` files are not corrupted. This is why `res/`
> and `models/` must sit at the bundle root, not namespaced.

```
prebuilt-sdk/ios/
  res/
    blusher.png
    lookup_custom.png
    lookup_gray.png
    lookup_light.png
    lookup_origin.png
    lookup_skin.png
    mouth.png
  models/
    face_det.mars_model
    face_align.mars_model
```

---

## LUT Textures (Whitening Pipeline)

Whitening in `BeautyFaceUnitFilter` is a chain of LUTs, applied in order:

```
Original pixel color
      │
      ▼ Step 1
lookup_gray.png     (1D strip LUT)
      │
      ▼ Step 2
lookup_origin.png   (4×4×16 3D LUT)
      │
      ▼ Step 3
lookup_skin.png     (4×4×16 3D LUT)
      │
      ▼ Step 4 (uniform lookUpCustom)
lookup_light.png    (512×512 → encodes an 8×8×64 3D LUT, bound as lookUpCustom)
      │
      ▼
Whitened color (blended with the original by the 'whiten' uniform)
```

### lookup_gray.png

| Property | Value |
|---|---|
| Format | 1D color strip (greyscale mapping) |
| Purpose | Adjusts luminance before entering the 3D LUTs |

### lookup_origin.png + lookup_skin.png

| Property | Value |
|---|---|
| Format | 4×4×16 3D LUT (encoded as a 2D grid) |
| Purpose | Color-grade the original skin tone to the target skin tone |

### lookup_custom.png

| Property | Value |
|---|---|
| Size | 512×512 px |
| Format | Encodes an 8×8×64 3D LUT |
| Purpose | Bundled resource; the shader currently does **not** bind this file |

### lookup_light.png

Used in the shader through the `lookUpCustom` uniform. It is the final LUT of the
whitening chain; `lookup_custom.png` is present in the bundle but is not bound.

---

## Creating a New LUT

### Tools

- **Photoshop** + the "Export Color Lookup Tables" plugin (.cube → PNG)
- **DaVinci Resolve** — export .cube
- **Python + numpy** — generate programmatically

### 4×4×16 3D LUT format (lookup_origin, lookup_skin)

```
Width = 4 * 16 = 64 px
Height = 4 px
Each 4×4 "cell" is a B-slice; 16 slices arranged horizontally
```

GLSL sampling:

```glsl
vec3 sampleLUT3D_4x4x16(sampler2D lut, vec3 color) {
    float blueIdx = color.b * 15.0;
    float blueFloor = floor(blueIdx);
    vec2 uvFloor = vec2(
        (blueFloor * 4.0 + color.r * 3.0) / 63.0,
        color.g * 3.0 / 3.0
    );
    // ... bilinear + trilinear interpolation
}
```

### Updating a LUT on iOS

Because resources live in the app bundle (read-only, copied at build time), there
is **no runtime hot-reload**. To change a LUT:

1. Replace the file in `prebuilt-sdk/ios/res/`.
2. Run `pod install` again (or do a clean build) so CocoaPods re-copies it into
   the bundle.
3. Rebuild the app.

---

## Makeup Textures

### blusher.png

| Property | Value |
|---|---|
| Purpose | Blush overlay on the cheek region |
| Texture region | `{left=395, top=520, width=489, height=209}` (pixel coords) |
| Blend mode | Multiply, hardcoded in `FaceMakeupFilter::DoRender()` |
| Alpha | Controlled by the `blusher` prop (0..10 → `/10`) |

**Replacing the blush texture:**
1. Create a PNG with an alpha channel, placing the cheek artwork inside the region
   `{395, 520, 489, 209}`.
2. Replace `prebuilt-sdk/ios/res/blusher.png`.
3. Run `pod install` and rebuild.

The texture region (`SetTextureBounds`) lives in the prebuilt engine
(`blusher_filter.cc`) and **cannot be changed** from the iOS side because the
engine is already built. Changing the region requires rebuilding the xcframework
from GPUPixel source.

### mouth.png

A lipstick texture. `LipstickFilter` exists in the engine/headers but is **not
yet wired** into the iOS bridge (`GPUPixelImageView.mm` /
`GPUPixelCameraView.mm` do not create it). See
[extending-filters.md](extending-filters.md).

---

## Model Files

### face_det.mars_model + face_align.mars_model

| Property | Value |
|---|---|
| Format | Proprietary mars-face-kit binary |
| Loaded by | `libmars-face-kit.a` + `MNN.framework` (device slice only) |
| Replaceable | **No** — proprietary format |
| Output | Keypoints normalized to [0,1]; at least 106 points for reshape, 111 for the makeup mesh |

The models **only load on device** (the simulator slice does not link MNN/mars).
No models on the simulator means no landmarks.

---

## Asset Flow Summary

| | iOS |
|---|---|
| Asset source | `prebuilt-sdk/ios/res` + `models` |
| How it enters the app | CocoaPods copies into the app bundle (build time) |
| Resource root | `SetResourceRoot([NSBundle resourcePath])` |
| Hot-reload | No (bundle is read-only; requires a rebuild) |
| PNG compression | None (copy-resources, verbatim) |
