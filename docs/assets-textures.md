# Assets & Textures

Tất cả assets được bundle trong `app/src/main/assets/` và **copy sang `getFilesDir()`** trong `MainActivity.initNative()`. Native code đọc từ filesystem, không phải từ APK assets trực tiếp.

```
assets/
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

Whitening trong `BeautyFaceUnitFilter` là một 3-step LUT chain. Thứ tự áp dụng:

```
Original pixel color
      │
      ▼ Step 1
lookup_gray.png     (1D strip LUT, 256×1 hoặc 512×1)
      │
      ▼ Step 2
lookup_origin.png   (4×4×16 3D LUT)
      │
      ▼ Step 3
lookup_skin.png     (4×4×16 3D LUT)
      │
      ▼ Step 4 (uniform lookUpCustom)
lookup_light.png    (512×512 → encode 8×8×64 3D LUT, bound as lookUpCustom)
      │
      ▼
Whitened color (blend với original theo uniform 'whiten')
```

### lookup_gray.png

| Property | Value |
|---|---|
| Format | 1D color strip (greyscale mapping) |
| Mục đích | Điều chỉnh luminance trước khi vào 3D LUT |
| Thay thế | Có — dùng Photoshop/HALD chart; vẽ 1 strip 256px × 1px |

### lookup_origin.png + lookup_skin.png

| Property | Value |
|---|---|
| Format | 4×4×16 3D LUT (encode dạng 2D grid) |
| Mục đích | Color grading cho skin tone gốc → skin tone mong muốn |
| Thay thế | Có — xem "Tạo LUT mới" bên dưới |

### lookup_custom.png

| Property | Value |
|---|---|
| Kích thước | 512×512 px |
| Format | Encode 8×8×64 3D LUT (HALD format hoặc custom tiling) |
| Mục đích | Bundled resource, nhưng Android shader hiện không bind file này trong `BeautyFaceUnitFilter::Init()` |
| Thay thế | Có, nhưng cần đổi code để bind file này thay cho `lookup_light.png` |

### lookup_light.png

Dùng trong shader với uniform tên `lookUpCustom`. Trong code hiện tại, đây là LUT final của whitening chain; `lookup_custom.png` có trong assets/CMake nhưng không được bind.

---

## Tạo LUT Mới

### Công cụ gợi ý

- **Photoshop** + plugin "Export Color Lookup Tables" (.cube → convert sang PNG)
- **DaVinci Resolve** — export .cube, dùng script convert sang format project
- **Python + numpy** để generate programmatically

### Format 4×4×16 3D LUT (cho lookup_origin, lookup_skin)

```
Width = 4 * 16 = 64 px
Height = 4 px
Mỗi "cell" 4×4 là một B-slice của LUT
16 slices sắp xếp ngang
```

Để sample trong GLSL:

```glsl
vec3 sampleLUT3D_4x4x16(sampler2D lut, vec3 color) {
    float blueIdx = color.b * 15.0;
    float blueFloor = floor(blueIdx);
    float blueFrac = fract(blueIdx);
    
    vec2 uvFloor = vec2(
        (blueFloor * 4.0 + color.r * 3.0) / 63.0,
        color.g * 3.0 / 3.0
    );
    // ... bilinear + trilinear interpolation
}
```

---

## Makeup Textures

### blusher.png

| Property | Value |
|---|---|
| Mục đích | Má hồng overlay lên vùng cheek |
| Texture region | `{left=395, top=520, width=489, height=209}` (pixel coords trong texture) |
| Blend mode | Multiply (mode 15), hardcoded trong `FaceMakeupFilter::DoRender()` |
| Alpha | Kiểm soát bởi `setMakeupParams(blusher)`, 0.0–1.0 |

**Thay thế blusher:**
1. Tạo ảnh PNG với alpha channel
2. Vùng má đặt vào region `{395, 520, 489, 209}` trong texture space
3. Replace file `assets/res/blusher.png`
4. Force app copy lại assets rồi gọi `init()` lại (hoặc reload texture: cần add native method `reloadTextures()`)

Để thay đổi texture region (mapping vùng ảnh nào lên vùng mặt nào), sửa trong `blusher_filter.cc`:

```cpp
SetTextureBounds({395, 520, 489, 209});  // {left, top, width, height}
```

### mouth.png

Lipstick/lip color texture. `LipstickFilter` đã có trong source/CMake, nhưng hiện tại **chưa được activate** trong Android pipeline vì `jni_bridge.cc` chưa tạo/wire filter này. Khi cần kích hoạt, xem `extending-filters.md`.

---

## Model Files

### face_det.mars_model + face_align.mars_model

| Property | Value |
|---|---|
| Format | Proprietary mars-face-kit binary format |
| Loaded by | `libmars-face-kit.so` (prebuilt, closed source) |
| Thay thế | **Không được** — format độc quyền, chỉ hoạt động với mars-face-kit |
| Output | Keypoints normalized [0,1]; code hiện yêu cầu tối thiểu 106 điểm cho reshape và 111 điểm cho makeup mesh |

Để dùng model detector khác (MediaPipe, TFLite Face Mesh, v.v.), cần viết wrapper mới implement cùng interface như `face_detector.cc` và thay đổi `CMakeLists.txt` flag `-DGPUPIXEL_ENABLE_FACE_DETECTOR`.

---

## Copy Assets Flow

`MainActivity.java` copy assets lần đầu:

```java
// Pseudocode của copyAssets()
copyDir("assets/res/", filesDir + "/res/");
copyDir("assets/models/", filesDir + "/models/");
BeautyFilterNative.init(filesDir.getAbsolutePath());
```

Nếu thay đổi file trong `assets/`, app hiện tại sẽ copy đè file trong `filesDir` mỗi lần `initNative()` chạy. Không có version check hoặc hash check; startup cost tăng theo số lượng asset.

**Fix nếu cần hot-reload LUT:** Thêm file hash check hoặc version file trong assets.
