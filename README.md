# AI Beauty Filter — Tích hợp Expo (iOS)

Thư mục này là **lớp tích hợp Expo** cho BeautyFilter SDK (GPUPixel + MNN), nằm
ngang hàng với `ios/`. Mục tiêu: chạy SDK trong một app **Expo** thay vì app
bare React Native do `../ios/bootstrap.sh` sinh ra.

Thư mục **tự chứa**: native (`native-bridge/`, `prebuilt-sdk/`) đã nằm trong
`modules/beautyfilter-sdk/` và được commit qua git, nên clone repo về máy khác là
chạy được ngay — không cần copy thêm gì từ `../ios`.

> Điểm khác cốt lõi so với bản bare RN: **không sửa `Podfile`/`Info.plist` bằng
> tay**. Vì `expo prebuild` sinh lại thư mục `ios/`, mọi thay đổi native được
> khai báo trong **config plugin** (`modules/beautyfilter-sdk/app.plugin.js`) và
> Expo tự áp dụng mỗi lần prebuild.

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
# hoặc mở ios/BeautyFilterExpo.xcworkspace trong Xcode để chạy trên iPhone thật
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

## So với bản bare RN (`../ios`)

| Bare RN (`../ios/bootstrap.sh`)        | Expo (`expo_ios/`)                      |
| -------------------------------------- | --------------------------------------- |
| `@react-native-community/cli init`     | `create-expo-app`                       |
| awk vá Podfile + PlistBuddy vá plist   | `app.plugin.js` (tự chạy khi prebuild)  |
| `react-native-image-picker`            | `expo-image-picker`                     |
| `npx react-native run-ios`             | `npx expo run:ios` / EAS Build          |
| `requireNativeComponent` + bridge      | **giữ nguyên** (chạy qua interop)       |

## Giới hạn giống bản bare RN

- Simulator: chỉ làm mịn + trắng da (MNN/mars chỉ có slice device).
- iPhone thật: đầy đủ thon mặt / to mắt / má hồng; cần chọn Team để ký.
- `prebuilt-sdk/ios/` là binary bắt buộc — plugin chỉ wire chứ không thay thế.
