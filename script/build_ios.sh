#!/bin/bash
# =============================================================================
# Build script for iOS — produces BeautyFilter.xcframework
#
# Builds three slices:
#   1. Device      arm64        (iphoneos)
#   2. Simulator   x86_64       (iphonesimulator — Intel Mac / CI)
#   3. Simulator   arm64        (iphonesimulator — Apple Silicon Mac)
# Combines slices 2+3 with lipo, then packages into an XCFramework.
#
# Requires: macOS + Xcode
# =============================================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "${SCRIPT_DIR}/.." && pwd )"
BUILD_BASE="${PROJECT_DIR}/build"
OUTPUT_DIR="${PROJECT_DIR}/output/ios"
XCFRAMEWORK_OUT="${OUTPUT_DIR}/BeautyFilter.xcframework"

# ---- Prerequisite checks -----------------------------------------------------

if [[ "$(uname)" != "Darwin" ]]; then
  echo "Error: This script requires macOS."
  exit 1
fi

if ! xcode-select -p &>/dev/null; then
  echo "Error: Xcode not installed. Run: xcode-select --install"
  exit 1
fi

if ! command -v cmake &>/dev/null; then
  echo "Error: cmake not found. Install via: brew install cmake"
  exit 1
fi

# ---- Helper ------------------------------------------------------------------

cmake_build() {
  local platform="$1"   # OS64 | SIMULATOR64 | SIMULATORARM64
  local sdk="$2"        # iphoneos | iphonesimulator
  local build_dir="$3"
  local install_dir="$4"

  echo ""
  echo "=== Building platform=${platform} sdk=${sdk} ==="

  mkdir -p "${build_dir}"

  cmake "${PROJECT_DIR}" \
    -B "${build_dir}" \
    -G Xcode \
    -DCMAKE_TOOLCHAIN_FILE="${PROJECT_DIR}/cmake/ios.toolchain.cmake" \
    -DPLATFORM="${platform}" \
    -DCMAKE_INSTALL_PREFIX="${install_dir}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DGPUPIXEL_BUILD_SHARED_LIBS=OFF \
    -DGPUPIXEL_ENABLE_FACE_DETECTOR=ON \
    -DGPUPIXEL_BUILD_DESKTOP_DEMO=OFF \
    -DDEPLOYMENT_TARGET=13.0

  cmake --build "${build_dir}" --config Release -- -sdk "${sdk}"
  cmake --install "${build_dir}" --config Release
}

# ---- 1. Device: arm64 --------------------------------------------------------

cmake_build OS64 iphoneos \
  "${BUILD_BASE}/ios_device" \
  "${BUILD_BASE}/ios_device_install"

# ---- 2. Simulator: x86_64 (Intel Mac / GitHub Actions) ----------------------

cmake_build SIMULATOR64 iphonesimulator \
  "${BUILD_BASE}/ios_sim_x86" \
  "${BUILD_BASE}/ios_sim_x86_install"

# ---- 3. Simulator: arm64 (Apple Silicon Mac) ---------------------------------

cmake_build SIMULATORARM64 iphonesimulator \
  "${BUILD_BASE}/ios_sim_arm64" \
  "${BUILD_BASE}/ios_sim_arm64_install"

# ---- 4. Lipo: combine simulator slices into fat binary -----------------------

echo ""
echo "=== Combining simulator slices with lipo ==="

SIM_FAT_DIR="${BUILD_BASE}/ios_sim_fat"
mkdir -p "${SIM_FAT_DIR}"

lipo -create \
  "${BUILD_BASE}/ios_sim_x86_install/lib/libbeautyfilter.a" \
  "${BUILD_BASE}/ios_sim_arm64_install/lib/libbeautyfilter.a" \
  -output "${SIM_FAT_DIR}/libbeautyfilter.a"

echo "Simulator fat binary architectures:"
lipo -info "${SIM_FAT_DIR}/libbeautyfilter.a"

# ---- 5. Package XCFramework --------------------------------------------------

echo ""
echo "=== Creating XCFramework ==="

rm -rf "${XCFRAMEWORK_OUT}"
mkdir -p "${OUTPUT_DIR}"

xcodebuild -create-xcframework \
  -library "${BUILD_BASE}/ios_device_install/lib/libbeautyfilter.a" \
    -headers "${PROJECT_DIR}/include" \
  -library "${SIM_FAT_DIR}/libbeautyfilter.a" \
    -headers "${PROJECT_DIR}/include" \
  -output "${XCFRAMEWORK_OUT}"

# ---- 6. Copy headers, resources, models and MNN framework -------------------

echo ""
echo "=== Copying headers, resources, models and MNN framework ==="

cp -r "${PROJECT_DIR}/include" "${OUTPUT_DIR}/include"
cp -r "${PROJECT_DIR}/src/res" "${OUTPUT_DIR}/res"
cp -r "${PROJECT_DIR}/third_party/mars-face-kit/models" "${OUTPUT_DIR}/models"
cp -r "${PROJECT_DIR}/third_party/mnn/libs/ios/MNN.framework" "${OUTPUT_DIR}/MNN.framework"

# ---- Done --------------------------------------------------------------------

echo ""
echo "============================================"
echo "  iOS build complete!"
echo "============================================"
echo ""
echo "  XCFramework : ${XCFRAMEWORK_OUT}"
echo "  Headers     : ${OUTPUT_DIR}/include/"
echo "  Resources   : ${OUTPUT_DIR}/res/"
echo "  Models      : ${OUTPUT_DIR}/models/"
echo "  MNN         : ${OUTPUT_DIR}/MNN.framework/"
echo ""
echo "Integration checklist:"
echo "  1. Drag BeautyFilter.xcframework into your Xcode project"
echo "     (check 'Copy items if needed', Embed: Do Not Embed for static lib)"
echo "  2. Drag MNN.framework into your Xcode project (Embed & Sign)"
echo "  3. Copy output/ios/res/ and output/ios/models/ into your app bundle"
echo "     (Add them to your Xcode target's Copy Bundle Resources phase)"
echo "  4. Link system frameworks:"
echo "     OpenGLES  AVFoundation  CoreVideo  CoreMedia  UIKit  Metal  CoreML"
echo "  5. In your ViewController:"
echo "     NSString* resRoot = [[NSBundle mainBundle] resourcePath];"
echo "     GPUPixelIOS_Init((__bridge void*)renderView, [resRoot UTF8String]);"
echo "     GPUPixelIOS_ProcessFrame(rgbaData, width, height);"
