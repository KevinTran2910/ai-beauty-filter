#!/bin/bash
# =============================================================================
# Bootstrap the AI Beauty Filter React Native demo app.
#
# Vì repo này không chứa sẵn project React Native (xcodeproj, Podfile…), script
# này sinh ra app bằng React Native CLI rồi "phủ" (overlay) các file demo + link
# native SDK qua một local CocoaPod (BeautyFilterSDK.podspec).
#
# Bạn chỉ cần chạy:  ./bootstrap.sh
# Yêu cầu: macOS + Xcode, Node.js (≥18), CocoaPods, Ruby/Bundler.
# =============================================================================
set -euo pipefail

ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
APP_NAME="BeautyFilterDemo"
APP="${ROOT}/${APP_NAME}"

EXTRA_DEPS="@react-native-community/slider react-native-image-picker"

# ---- Prerequisite checks -----------------------------------------------------
need() { command -v "$1" >/dev/null 2>&1 || { echo "❌ Thiếu '$1'. $2"; exit 1; }; }

[[ "$(uname)" == "Darwin" ]] || { echo "❌ Cần chạy trên macOS (Xcode)."; exit 1; }
need node "Cài qua https://nodejs.org hoặc 'brew install node'."
need npx  "Đi kèm Node.js."
need pod  "Cài CocoaPods: 'sudo gem install cocoapods' hoặc 'brew install cocoapods'."

[[ -d "${ROOT}/../../output/ios/BeautyFilter.xcframework" ]] || {
  echo "❌ Không thấy output/ios/BeautyFilter.xcframework — hãy chạy ./script/build_ios.sh trước."
  exit 1
}

# ---- 1. Sinh project RN (nếu chưa có) ---------------------------------------
if [[ -d "${APP}" ]]; then
  echo "ℹ️  ${APP_NAME}/ đã tồn tại — bỏ qua bước init, chỉ cập nhật overlay."
else
  echo "=== Sinh project React Native: ${APP_NAME} ==="
  ( cd "${ROOT}" && npx @react-native-community/cli@latest init "${APP_NAME}" --skip-install )
fi

# ---- 2. Cài dependencies ----------------------------------------------------
echo "=== Cài npm dependencies ==="
( cd "${APP}" && npm install && npm install ${EXTRA_DEPS} )

# ---- 3. Overlay file demo (App.tsx + JS wrappers) ---------------------------
echo "=== Overlay App.tsx + src/ ==="
cp "${ROOT}/app-template/App.tsx" "${APP}/App.tsx"
mkdir -p "${APP}/src"
cp "${ROOT}/app-template/src/index.ts" "${APP}/src/index.ts"

# ---- 4. Wire local pod vào Podfile ------------------------------------------
PODFILE="${APP}/ios/Podfile"
if ! grep -q "BeautyFilterSDK" "${PODFILE}"; then
  echo "=== Thêm pod 'BeautyFilterSDK' vào Podfile ==="
  # Chèn ngay sau dòng "target '<APP_NAME>' do".
  # podspec nằm ở GỐC REPO (để CocoaPods không bỏ qua vendored files ngoài thư mục
  # podspec). Từ <app>/ios lên gốc repo là 4 cấp: ../../../..
  /usr/bin/awk -v name="${APP_NAME}" '
    { print }
    $0 ~ "target '\''" name "'\'' do" {
      print "  # AI Beauty Filter local SDK (native bridge + prebuilt xcframework)"
      print "  pod '\''BeautyFilterSDK'\'', :path => '\''../../../..'\''"
    }
  ' "${PODFILE}" > "${PODFILE}.tmp" && mv "${PODFILE}.tmp" "${PODFILE}"
fi

# ---- 5. Quyền truy cập ảnh / camera (Info.plist) ----------------------------
PLIST="${APP}/ios/${APP_NAME}/Info.plist"
PB=/usr/libexec/PlistBuddy
add_plist() {
  ${PB} -c "Add :$1 string $2" "${PLIST}" 2>/dev/null \
    || ${PB} -c "Set :$1 $2" "${PLIST}"
}
echo "=== Thêm quyền vào Info.plist ==="
add_plist NSPhotoLibraryUsageDescription "App cần truy cập thư viện ảnh để chọn ảnh làm đẹp."
add_plist NSCameraUsageDescription "App cần camera cho chế độ làm đẹp real-time."

# ---- 6. pod install (tắt New Architecture cho demo) -------------------------
echo "=== pod install (RCT_NEW_ARCH_ENABLED=0) ==="
(
  cd "${APP}/ios"
  if command -v bundle >/dev/null 2>&1 && [[ -f "${APP}/Gemfile" ]]; then
    ( cd "${APP}" && bundle install ) || true
    RCT_NEW_ARCH_ENABLED=0 bundle exec pod install || RCT_NEW_ARCH_ENABLED=0 pod install
  else
    RCT_NEW_ARCH_ENABLED=0 pod install
  fi
)

cat <<EOF

✅ Hoàn tất!

Chạy nhanh trên SIMULATOR (làm mịn + trắng da; face detection tắt trên simulator):
  cd ${APP_NAME}
  npx react-native run-ios --simulator "iPhone 17"

Đầy đủ tính năng (thon mặt/to mắt/má hồng) trên IPHONE THẬT:
  open ${APP_NAME}/ios/${APP_NAME}.xcworkspace
  # Xcode: target ${APP_NAME} → Signing & Capabilities → chọn Team,
  # cắm iPhone, chọn làm destination rồi Run (▶).

Lý do: MNN + mars-face-kit chỉ có lát device → slice simulator build với face
detection TẮT để chạy được trên máy ảo.
EOF
