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
# Yêu cầu: macOS + Xcode 16.1+, Node.js 20.19+, CocoaPods 1.13+ (Expo SDK 54 / RN 0.81).
# =============================================================================
set -euo pipefail

ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
APP_NAME="BeautyFilterExpo"
APP="${ROOT}/${APP_NAME}"
MODULE_SRC="${ROOT}/modules/beautyfilter-sdk"
MODULE_DST="${APP}/modules/beautyfilter-sdk"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Thiếu '$1'. $2"; exit 1; }; }

# ver_ge A B -> trả 0 (true) nếu A >= B. Không dùng 'sort -V' vì BSD sort trên
# macOS không hỗ trợ; viết thuần bash 3.2 (bash mặc định của macOS).
ver_ge() {
  local IFS=. i
  local -a a=($1) b=($2)
  for ((i=0; i<${#b[@]}; i++)); do
    local x="${a[i]:-0}" y="${b[i]:-0}"
    x="${x%%[!0-9]*}"; y="${y%%[!0-9]*}"   # bỏ hậu tố không phải số (vd 16.1-beta)
    x="${x:-0}"; y="${y:-0}"
    if ((10#$x > 10#$y)); then return 0; fi
    if ((10#$x < 10#$y)); then return 1; fi
  done
  return 0
}

# need_version "tên" "ver hiện tại" "ver tối thiểu" "gợi ý"
need_version() {
  local name="$1" cur="$2" min="$3" hint="$4"
  [[ -n "$cur" ]] || { echo "Không đọc được version của '$name'. $hint"; exit 1; }
  ver_ge "$cur" "$min" || { echo "'$name' $cur < $min (yêu cầu của Expo SDK 54). $hint"; exit 1; }
}

[[ "$(uname)" == "Darwin" ]] || { echo "Cần chạy trên macOS (Xcode)."; exit 1; }
need node "Cài qua https://nodejs.org hoặc 'brew install node'."
need npx  "Đi kèm Node.js."
need pod  "Cài CocoaPods: 'sudo gem install cocoapods' hoặc 'brew install cocoapods'."
need xcodebuild "Cài Xcode đầy đủ từ App Store, rồi 'sudo xcode-select -s /Applications/Xcode.app'."

# ---- Kiểm tra version theo yêu cầu Expo SDK 54 / RN 0.81 --------------------
# Nguồn: docs.expo.dev/versions/v54.0.0 (Node 20.19+, Xcode 16.1+, iOS 15.1+).
need_version "Node.js"   "$(node -v | sed 's/^v//')"                       "20.19.0" \
  "Expo SDK 54 cần Node >= 20.19. Nâng cấp bằng nvm hoặc 'brew upgrade node'."
need_version "Xcode"     "$(xcodebuild -version 2>/dev/null | head -1 | awk '{print $2}')" "16.1" \
  "Expo SDK 54 (RN 0.81) cần Xcode >= 16.1. Cập nhật qua App Store."
need_version "CocoaPods" "$(pod --version 2>/dev/null)"                     "1.13.0" \
  "Nâng cấp: 'sudo gem install cocoapods' hoặc 'brew upgrade cocoapods'."

[[ -d "${MODULE_SRC}/prebuilt-sdk/ios/BeautyFilter.xcframework" ]] || {
  echo "Không thấy ${MODULE_SRC}/prebuilt-sdk/ios/BeautyFilter.xcframework — module thiếu native SDK."
  exit 1
}
[[ -f "${MODULE_SRC}/prebuilt-sdk/ios/libmars-face-kit.a" ]] || {
  echo "Không thấy ${MODULE_SRC}/prebuilt-sdk/ios/libmars-face-kit.a — thiếu thư viện face detection (device)."
  exit 1
}

# ---- 1. Tạo app Expo (nếu chưa có) ------------------------------------------
if [[ -d "${APP}" ]]; then
  echo "${APP_NAME}/ đã tồn tại — bỏ qua bước tạo app, chỉ cập nhật module/overlay."
else
  echo "=== Tạo app Expo: ${APP_NAME} (pin SDK 54) ==="
  # Pin template về dist-tag sdk-54: '@latest' hiện trỏ SDK 56 → lệch native bridge.
  # Tag sdk-54 tự lấy patch 54.x mới nhất nên vẫn cố định major SDK.
  ( cd "${ROOT}" && npx create-expo-app@latest "${APP_NAME}" --template expo-template-blank-typescript@sdk-54 )
fi

# ---- 2. Cài dependencies ----------------------------------------------------
echo "=== Cài Expo dependencies ==="
( cd "${APP}" && npx expo install expo-dev-client expo-image-picker @react-native-community/slider )

# ---- 3. Copy module beautyfilter-sdk (kèm native) vào app -------------------
echo "=== Copy module beautyfilter-sdk (kèm native) ==="
rm -rf "${MODULE_DST}"
mkdir -p "${MODULE_DST}"
cp -R "${MODULE_SRC}/." "${MODULE_DST}/"

# Podspec phải khai báo user_target_xcconfig để app target link libmars-face-kit.a
# (device). Thiếu bước này → lỗi linker: mars_vision::MarsFaceLandmarker::Create().
node - "${MODULE_DST}/BeautyFilterSDK.podspec" <<'NODE'
const fs = require('fs');
const file = process.argv[2];
let txt = fs.readFileSync(file, 'utf8');
if (txt.includes('s.user_target_xcconfig')) {
  console.log('  -> podspec đã có user_target_xcconfig (link mars-face-kit cho device).');
} else {
  const block = `
  # pod_target_xcconfig OTHER_LDFLAGS không lan sang app target — libbeautyfilter.a
  # (device) vẫn thiếu MarsFaceLandmarker::Create() khi link app nếu không khai báo ở đây.
  s.user_target_xcconfig = {
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]' => '$(inherited) "$(PODS_ROOT)/../../modules/beautyfilter-sdk/prebuilt-sdk/ios"',
    'LIBRARY_SEARCH_PATHS[sdk=iphoneos*]'   => '$(inherited) "$(PODS_ROOT)/../../modules/beautyfilter-sdk/prebuilt-sdk/ios"',
    'OTHER_LDFLAGS[sdk=iphoneos*]'          => '$(inherited) -framework MNN -framework CoreML -framework Metal -l"mars-face-kit"',
  }
`;
  if (!txt.includes('s.requires_arc')) {
    console.error('Không vá được podspec: thiếu s.requires_arc');
    process.exit(1);
  }
  txt = txt.replace(/\n\s*s\.requires_arc/, `${block}\n  s.requires_arc`);
  fs.writeFileSync(file, txt);
  console.log('  -> đã thêm user_target_xcconfig vào BeautyFilterSDK.podspec.');
}
NODE

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
echo "=== expo prebuild -p ios (New Architecture, khớp app.json newArchEnabled) ==="
( cd "${APP}" && npx expo prebuild -p ios --clean )

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
