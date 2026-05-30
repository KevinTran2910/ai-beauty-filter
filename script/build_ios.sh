#!/bin/bash
# =============================================================================
# Build script cho iOS (arm64 device)
# Chạy trên macOS với Xcode đã cài đặt.
# =============================================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "${SCRIPT_DIR}/.." && pwd )"
BUILD_DIR="${PROJECT_DIR}/build/ios"
INSTALL_DIR="${PROJECT_DIR}/output/ios"

# Kiểm tra đang chạy trên macOS
if [[ "$(uname)" != "Darwin" ]]; then
  echo "Lỗi: Script này chỉ chạy trên macOS."
  exit 1
fi

# Kiểm tra Xcode
if ! xcode-select -p &>/dev/null; then
  echo "Lỗi: Xcode chưa được cài đặt. Cài qua: xcode-select --install"
  exit 1
fi

mkdir -p "${BUILD_DIR}"

echo "Đang cấu hình CMake cho iOS..."
cmake "${PROJECT_DIR}" \
  -B "${BUILD_DIR}" \
  -G Xcode \
  -DCMAKE_TOOLCHAIN_FILE="${PROJECT_DIR}/cmake/ios.toolchain.cmake" \
  -DPLATFORM=OS64 \
  -DCMAKE_INSTALL_PREFIX="${INSTALL_DIR}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGPUPIXEL_BUILD_SHARED_LIBS=OFF \
  -DGPUPIXEL_ENABLE_FACE_DETECTOR=OFF \
  -DGPUPIXEL_BUILD_DESKTOP_DEMO=OFF \
  -DDEPLOYMENT_TARGET=13.0

echo "Đang build..."
cmake --build "${BUILD_DIR}" \
  --config Release \
  -- -sdk iphoneos

echo "Đang cài đặt vào ${INSTALL_DIR}..."
cmake --install "${BUILD_DIR}" --config Release

echo ""
echo "Build iOS hoàn tất!"
echo "Thư viện tĩnh: ${INSTALL_DIR}/lib/libbeautyfilter.a"
echo "Headers:       ${INSTALL_DIR}/include/"
echo ""
echo "Tiếp theo: thêm libbeautyfilter.a và thư mục include/ vào Xcode project của bạn."
