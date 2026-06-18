# Face Landmarks — Hệ Tọa Độ Landmark

## Tổng Quan

mars-face-kit detect và return một vector keypoints của khuôn mặt. `FaceDetector::Detect()` normalize mỗi keypoint `(x, y)` về `[0, 1]` trong không gian ảnh input (origin góc trên-trái, x sang phải, y xuống dưới).

JNI bridge không nhận landmark array từ Java. Nó nhận RGBA frame, gọi mars-face-kit, rồi nhận `std::vector<float>` dạng cặp x,y liên tiếp:

```cpp
std::vector<float> landmarks = g_face_detector->Detect(
    pixels, width, height, width * 4, GPUPIXEL_MODE_FMT_VIDEO,
    GPUPIXEL_FRAME_TYPE_RGBA);
```

Sau đó push vào các filter:

```cpp
if (g_blusher) g_blusher->SetFaceLandmarks(landmarks);
if (g_reshape) g_reshape->SetFaceLandmarks(landmarks);
```

## Vùng Mặt và Index Ranges (mars-face-kit standard)

```
        Forehead
    ┌──────────────┐
    │  17 ─── 26   │  ← Eyebrows: 17–26 (trái), 22–26 (phải — overlap)
    │  36  38  45  │  ← Eyes: 36–41 (trái), 42–47 (phải)
    │    30─33     │  ← Nose bridge: 27–30, cánh mũi: 31–35
    │  48─────54   │  ← Mouth outer: 48–59
    │  60─────67   │  ← Mouth inner: 60–67
    │    8         │  ← Chin tip: 8
    └──┐         ┌─┘
       0─────────16   ← Jawline: 0–16 (17 points)
```

### Mapping quan sát được theo mars-face-kit model

| Index range | Vùng mặt | Ghi chú |
|---|---|---|
| 0 – 16 | Jawline / chin contour | 17 điểm, 0=left, 16=right, 8=chin tip |
| 17 – 21 | Eyebrow trái | Outer → inner |
| 22 – 26 | Eyebrow phải | Inner → outer |
| 27 – 30 | Nose bridge | Top-down |
| 31 – 35 | Nose tip + nostrils | 31=tip, 32-33=trái, 34-35=phải |
| 36 – 41 | Mắt trái | 36=outer corner, 39=inner corner |
| 42 – 47 | Mắt phải | 42=inner corner, 45=outer corner |
| 48 – 59 | Mouth outer lip | 48=left corner, 54=right corner |
| 60 – 67 | Mouth inner lip | |
| 68 – 75 | Pupils + eye centers | Xem bên dưới |
| 76 – 83 | Iris points | |
| 84 – 105 | Additional contour | Cheeks, phần bổ sung |

> **Lưu ý:** mars-face-kit là thư viện closed-source. Index mapping trên dựa vào quan sát từ code FaceReshapeFilter và FaceMakeupFilter. `FaceReshapeFilter` cần tối thiểu 106 điểm, nhưng `FaceMakeupFilter` dùng mesh có indices 106–110 nên cần tối thiểu 111 điểm. Nếu cần mapping chính xác 100%, dùng debug overlay để vẽ từng điểm lên ảnh.

## Indices Đang Dùng Trong Code

### FaceReshapeFilter — thinFace

```cpp
// 9 origin -> target pairs hardcode trong shader:
(3 -> 44), (29 -> 44), (7 -> 45), (25 -> 45), (10 -> 46),
(22 -> 46), (14 -> 49), (18 -> 49), (16 -> 49)
```

### FaceReshapeFilter — bigEye

```cpp
// Mắt trái
enlargeEye(center=landmarks[74], radiusAnchor=landmarks[72], bigEyeDelta);

// Mắt phải
enlargeEye(center=landmarks[77], radiusAnchor=landmarks[75], bigEyeDelta);
```

### FaceMakeupFilter — triangle mesh

Triangle mesh gồm 111 điểm (indices 0–110), phủ các vùng:
- Eyebrows (left + right)
- Eyes (left + right)
- Cheeks (left + right) — vùng BlusherFilter dùng
- Nose
- Upper + lower lip
- Chin

Triangle indices được hardcode trong `face_makeup_filter.cc`. Mesh này define vùng mà makeup texture được map lên.

## Coordinate Transforms

### mars-face-kit output → Filter input

```
[0, 1] normalized  →  vẫn [0, 1] khi pass vào SetFaceLandmarks()
```

### FaceMakeupFilter — clip space conversion

```cpp
// Trong FaceMakeupFilter::SetFaceLandmarks():
float clipX = landmark.x * 2.0f - 1.0f;  // [0,1] -> [-1,1]
float clipY = landmark.y * 2.0f - 1.0f;  // [0,1] -> [-1,1]
```

### FaceReshapeFilter — UV space

```cpp
// Warp được tính trong UV space [0,1]
// Displacement tính theo Euclidean distance trong UV
float dist = distance(uv, anchorPoint);
vec2 warpedUV = uv + direction * falloff(dist) * magnitude;
```

## Debug: Vẽ Landmark Overlay

Để debug, thêm vào `MainActivity.java` sau `runDetect()`:

```java
private void drawLandmarkOverlay(Canvas canvas, float[] landmarks, int frameW, int frameH) {
    Paint p = new Paint();
    p.setColor(Color.GREEN);
    p.setStrokeWidth(4);
    for (int i = 0; i < landmarks.length; i += 2) {
        float x = landmarks[i] * canvas.getWidth();
        float y = landmarks[i + 1] * canvas.getHeight();
        canvas.drawCircle(x, y, 3, p);
        // Vẽ index number để identify
        canvas.drawText(String.valueOf(i / 2), x + 5, y, p);
    }
}
```

`detectFace()` trong JNI giữ landmarks ở native side và không trả về Java. Để expose về Java, cần lưu vector landmark gần nhất trong `jni_bridge.cc` và thêm native method `float[] getLandmarks()` trong `BeautyFilterNative`.
