#!/bin/bash
# =============================================================================
# Build script for iOS — produces BeautyFilter.xcframework
#
# Builds two slices (arm64 only — mars-face-kit & MNN have no x86_64 binary):
#   1. Device      arm64        (iphoneos)
#   2. Simulator   arm64        (iphonesimulator — Apple Silicon Mac)
#
# Requires: macOS + Xcode + cmake
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
  local platform="$1"      # OS64 | SIMULATOR64 | SIMULATORARM64
  local sdk="$2"           # iphoneos | iphonesimulator
  local build_dir="$3"
  local install_dir="$4"
  local face_detector="${5:-ON}"  # ON for device; OFF for simulator (MNN +
                                  # mars-face-kit are device-only static libs)

  echo ""
  echo "=== Building platform=${platform} sdk=${sdk} face_detector=${face_detector} ==="

  mkdir -p "${build_dir}"

  cmake "${PROJECT_DIR}" \
    -B "${build_dir}" \
    -G Xcode \
    -DCMAKE_TOOLCHAIN_FILE="${PROJECT_DIR}/cmake/ios.toolchain.cmake" \
    -DPLATFORM="${platform}" \
    -DCMAKE_INSTALL_PREFIX="${install_dir}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DGPUPIXEL_BUILD_SHARED_LIBS=OFF \
    -DGPUPIXEL_ENABLE_FACE_DETECTOR="${face_detector}" \
    -DGPUPIXEL_BUILD_DESKTOP_DEMO=OFF \
    -DDEPLOYMENT_TARGET=13.0

  cmake --build "${build_dir}" --config Release -- -sdk "${sdk}"
  cmake --install "${build_dir}" --config Release
}

# ---- 1. Device: arm64 --------------------------------------------------------

cmake_build OS64 iphoneos \
  "${BUILD_BASE}/ios_device" \
  "${BUILD_BASE}/ios_device_install"

# ---- 2. Simulator: arm64 (Apple Silicon Mac) ---------------------------------
# Face detection OFF on simulator: MNN.framework and libmars-face-kit.a ship as
# device-only static binaries (no simulator slice), so a simulator slice that
# referenced them could never be linked. The simulator slice therefore supports
# only the landmark-free filters (skin smoothing / whitening).

cmake_build SIMULATORARM64 iphonesimulator \
  "${BUILD_BASE}/ios_sim_arm64" \
  "${BUILD_BASE}/ios_sim_arm64_install" \
  OFF

# ---- 3. Package XCFramework --------------------------------------------------

echo ""
echo "=== Creating XCFramework ==="

rm -rf "${XCFRAMEWORK_OUT}"
mkdir -p "${OUTPUT_DIR}"

xcodebuild -create-xcframework \
  -library "${BUILD_BASE}/ios_device_install/lib/libbeautyfilter.a" \
    -headers "${PROJECT_DIR}/include" \
  -library "${BUILD_BASE}/ios_sim_arm64_install/lib/libbeautyfilter.a" \
    -headers "${PROJECT_DIR}/include" \
  -output "${XCFRAMEWORK_OUT}"

# ---- 6. Copy headers, resources, models and MNN framework -------------------

echo ""
echo "=== Copying headers, resources, models and MNN framework ==="

rm -rf "${OUTPUT_DIR}/include" "${OUTPUT_DIR}/res" "${OUTPUT_DIR}/models" "${OUTPUT_DIR}/MNN.framework"
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
