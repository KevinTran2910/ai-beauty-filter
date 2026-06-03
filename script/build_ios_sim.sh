#!/bin/bash
# =============================================================================
# Build script for iOS SIMULATOR — produces a face-detection-FREE xcframework
#
# Why this exists:
#   MNN.framework and libmars-face-kit.a are device-only (arm64-iOS, no
#   simulator slice), so the full SDK cannot link on the iOS Simulator. This
#   script builds a simulator slice with GPUPIXEL_ENABLE_FACE_DETECTOR=OFF so
#   the package can be smoke-tested on the Simulator with static-image input.
#   Working features on simulator: skin smoothing + whitening.
#   NOT working: face slim / eye enlarge / blusher (need landmarks).
#
# Isolation (does NOT touch the device build):
#   build dir  : build/ios_sim_nofd  +  build/ios_sim_nofd_install
#   output     : output/ios-sim/      (device stays at output/ios/)
#
# Requires: macOS + Xcode + cmake
# =============================================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "${SCRIPT_DIR}/.." && pwd )"
BUILD_BASE="${PROJECT_DIR}/build"
OUTPUT_DIR="${PROJECT_DIR}/output/ios-sim"
XCFRAMEWORK_OUT="${OUTPUT_DIR}/BeautyFilter.xcframework"

BUILD_DIR="${BUILD_BASE}/ios_sim_nofd"
INSTALL_DIR="${BUILD_BASE}/ios_sim_nofd_install"

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

# ---- Build: Simulator arm64, face detector OFF -------------------------------

echo ""
echo "=== Building SIMULATOR arm64 (face detector OFF) ==="

mkdir -p "${BUILD_DIR}"

cmake "${PROJECT_DIR}" \
  -B "${BUILD_DIR}" \
  -G Xcode \
  -DCMAKE_TOOLCHAIN_FILE="${PROJECT_DIR}/cmake/ios.toolchain.cmake" \
  -DPLATFORM=SIMULATORARM64 \
  -DCMAKE_INSTALL_PREFIX="${INSTALL_DIR}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGPUPIXEL_BUILD_SHARED_LIBS=OFF \
  -DGPUPIXEL_ENABLE_FACE_DETECTOR=OFF \
  -DGPUPIXEL_BUILD_DESKTOP_DEMO=OFF \
  -DDEPLOYMENT_TARGET=13.0

cmake --build "${BUILD_DIR}" --config Release -- -sdk iphonesimulator
cmake --install "${BUILD_DIR}" --config Release

# ---- Package XCFramework (simulator-only) ------------------------------------

echo ""
echo "=== Creating simulator XCFramework ==="

rm -rf "${XCFRAMEWORK_OUT}"
mkdir -p "${OUTPUT_DIR}"

xcodebuild -create-xcframework \
  -library "${INSTALL_DIR}/lib/libbeautyfilter.a" \
    -headers "${PROJECT_DIR}/include" \
  -output "${XCFRAMEWORK_OUT}"

# ---- Copy headers + image resources (no models / no MNN: detector is OFF) ----

echo ""
echo "=== Copying headers + resources ==="

rm -rf "${OUTPUT_DIR}/include" "${OUTPUT_DIR}/res"
cp -r "${PROJECT_DIR}/include" "${OUTPUT_DIR}/include"
cp -r "${PROJECT_DIR}/src/res" "${OUTPUT_DIR}/res"

# ---- Done --------------------------------------------------------------------

echo ""
echo "============================================"
echo "  iOS SIMULATOR build complete!"
echo "============================================"
echo ""
echo "  XCFramework : ${XCFRAMEWORK_OUT}"
echo "  Headers     : ${OUTPUT_DIR}/include/"
echo "  Resources   : ${OUTPUT_DIR}/res/"
echo ""
echo "  Face detection: DISABLED (smoothing + whitening only)."
echo "  Device build  : untouched at output/ios/"
