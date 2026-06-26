#!/bin/bash
# =============================================================================
# setup.sh — Dựng app Expo cho AI Beauty Filter (tự chứa)
#
# Khác bootstrap.sh bản bare RN: KHÔNG sửa Podfile/Info.plist bằng tay, mà để
# Expo config plugin (modules/beautyfilter-sdk/app.plugin.js) tự inject khi
# prebuild. Native (native-bridge + prebuilt-sdk) đã nằm sẵn trong module nên
# script chạy độc lập, không cần thư mục ../ios.
#
# Script này:
#   1. Tạo app Expo (create-expo-app) + cài expo-dev-client, expo-image-picker, slider.
#   2. Copy module beautyfilter-sdk (kèm native) vào app/modules/.
#   3. Khai báo plugin trong app.json + đặt App.tsx demo.
#   4. expo prebuild (plugin chèn pod + quyền) — sẵn sàng run:ios.
#
# Yêu cầu: macOS + Xcode, Node.js (>=18), CocoaPods.
# =============================================================================
set -euo pipefail

ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
APP_NAME="BeautyFilterExpo"
APP="${ROOT}/${APP_NAME}"
MODULE_SRC="${ROOT}/modules/beautyfilter-sdk"
MODULE_DST="${APP}/modules/beautyfilter-sdk"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Thiếu '$1'. $2"; exit 1; }; }

[[ "$(uname)" == "Darwin" ]] || { echo "Cần chạy trên macOS (Xcode)."; exit 1; }
need node "Cài qua https://nodejs.org hoặc 'brew install node'."
need npx  "Đi kèm Node.js."
need pod  "Cài CocoaPods: 'sudo gem install cocoapods' hoặc 'brew install cocoapods'."

[[ -d "${MODULE_SRC}/prebuilt-sdk/ios/BeautyFilter.xcframework" ]] || {
  echo "Không thấy ${MODULE_SRC}/prebuilt-sdk/ios/BeautyFilter.xcframework — module thiếu native SDK."
  exit 1
}

# ---- 1. Tạo app Expo (nếu chưa có) ------------------------------------------
if [[ -d "${APP}" ]]; then
  echo "${APP_NAME}/ đã tồn tại — bỏ qua bước tạo app, chỉ cập nhật module/overlay."
else
  echo "=== Tạo app Expo: ${APP_NAME} ==="
  ( cd "${ROOT}" && npx create-expo-app@latest "${APP_NAME}" --template blank-typescript )
fi

# ---- 2. Cài dependencies ----------------------------------------------------
echo "=== Cài Expo dependencies ==="
( cd "${APP}" && npx expo install expo-dev-client expo-image-picker @react-native-community/slider )

# ---- 3. Copy module beautyfilter-sdk (kèm native) vào app -------------------
echo "=== Copy module beautyfilter-sdk (kèm native) ==="
rm -rf "${MODULE_DST}"
mkdir -p "${MODULE_DST}"
cp -R "${MODULE_SRC}/." "${MODULE_DST}/"

# Để app import được 'beautyfilter-sdk' như package: link vào node_modules.
( cd "${APP}" && npm install "./modules/beautyfilter-sdk" )

# ---- 4. Khai báo plugin trong app.json + đặt App.tsx demo -------------------
echo "=== Khai báo plugin trong app.json ==="
node - "${APP}/app.json" <<'NODE'
const fs = require('fs');
const file = process.argv[2];
const j = JSON.parse(fs.readFileSync(file, 'utf8'));
j.expo = j.expo || {};
j.expo.plugins = j.expo.plugins || [];
const plugin = './modules/beautyfilter-sdk/app.plugin.js';
if (!j.expo.plugins.some(p => (Array.isArray(p) ? p[0] : p) === plugin)) {
  j.expo.plugins.push(plugin);
}
fs.writeFileSync(file, JSON.stringify(j, null, 2) + '\n');
console.log('  -> đã thêm plugin:', plugin);
NODE

echo "=== Đặt App.tsx demo ==="
cp "${ROOT}/example/App.tsx" "${APP}/App.tsx"

# ---- 5. Prebuild (sinh ios/, plugin chèn pod + quyền) -----------------------
echo "=== expo prebuild -p ios (RCT_NEW_ARCH_ENABLED=0) ==="
( cd "${APP}" && RCT_NEW_ARCH_ENABLED=0 npx expo prebuild -p ios --clean )

cat <<EOF

Hoàn tất!

Chạy trên SIMULATOR (làm mịn + trắng da; face detection tắt trên simulator):
  cd ${APP_NAME}
  npx expo run:ios

Đầy đủ tính năng (thon mặt/to mắt/má hồng) trên IPHONE THẬT:
  open ${APP_NAME}/ios/${APP_NAME}.xcworkspace
  # Xcode: chọn Team ở Signing & Capabilities, cắm iPhone, Run (▶).

Hoặc build trên cloud (không cần Mac): eas build -p ios --profile development
EOF
