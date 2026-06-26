# AI Beauty Filter — Tích hợp Expo (iOS)

Thư mục này là **lớp tích hợp Expo** cho BeautyFilter SDK (GPUPixel + MNN). Mục tiêu: chạy SDK trong một app **Expo** thay vì app bare React Native do `../ios/bootstrap.sh` sinh ra.

Thư mục **tự chứa**: native (`native-bridge/`, `prebuilt-sdk/`) đã nằm trong  
`modules/beautyfilter-sdk/`.

## Lưu ý môi trường

- Build iOS cần **macOS + Xcode** (không build được trên Windows). Trên Windows
có thể chỉnh sửa code, nhưng để build/run hãy dùng máy Mac hoặc **EAS Build**.
- **Không dùng Expo Go** — SDK có native code custom (xcframework + ObjC++
bridge). Bắt buộc **Expo Dev Client** (custom dev build).

## Cấu trúc

```text
expo_ios/
├── setup.sh                         # macOS: tạo app Expo + wire module + prebuild
├── example/
│   └── App.tsx                      # màn demo (dùng expo-image-picker)
└── modules/
    └── beautyfilter-sdk/            # module cục bộ, copy vào app/modules/
        ├── app.plugin.js            # config plugin: chèn pod + quyền Info.plist
        ├── package.json             # khai báo "expo.plugin"
        ├── BeautyFilterSDK.podspec  # gom native bridge + xcframework + resources
        ├── src/index.ts             # JS wrapper (GPUPixelImageView / CameraView)
        ├── native-bridge/           # lớp cầu nối ObjC++ (đã kèm, commit qua git)
        └── prebuilt-sdk/ios/        # native SDK dựng sẵn (đã kèm):
                                     #   BeautyFilter.xcframework, MNN.framework,
                                     #   libmars-face-kit.a, include, models, res
```

## Cách dùng (tự động)

Trên macOS, từ thư mục này:

```bash
chmod +x setup.sh
./setup.sh
```

Script tạo app Expo `BeautyFilterExpo/`, cài deps, gắn module (kèm native), khai
báo plugin trong `app.json`, đặt `App.tsx` demo, rồi `expo prebuild`. Sau đó:

```bash
cd BeautyFilterExpo
npx expo run:ios          # simulator (làm mịn + trắng da)
```

Không có Mac? Build cloud: `eas build -p ios --profile development`.

## Cách dùng (thủ công, nếu đã có app Expo sẵn)

1. Copy `modules/beautyfilter-sdk/` (đã kèm native) vào `<app>/modules/`.
2. `npx expo install expo-dev-client expo-image-picker @react-native-community/slider`
3. `npm install ./modules/beautyfilter-sdk`
4. Thêm vào `app.json`:
  ```json
   { "expo": { "plugins": ["./modules/beautyfilter-sdk/app.plugin.js"] } }
  ```
5. `npx expo prebuild -p ios --clean` rồi `npx expo run:ios`.