#!/bin/bash
# =============================================================================
# Beautyfilter WebAssembly Build Script
# 
# This script builds the Beautyfilter library for WebAssembly (WASM) platform
# using Emscripten SDK. Before running this script, ensure that emscripten is 
# properly installed, either via emsdk or Homebrew.
# =============================================================================

set -e

# Set script variables
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "${SCRIPT_DIR}/.." && pwd )"
BUILD_DIR="${PROJECT_DIR}/build/wasm"
INSTALL_DIR="${PROJECT_DIR}/output"

# Check if Emscripten is installed
if ! command -v emcc &> /dev/null; then
    # Try to find Homebrew installed emscripten
    if command -v brew &> /dev/null; then
        echo "Detected Homebrew, searching for emscripten..."
        BREW_EMSCRIPTEN=$(brew --prefix emscripten 2>/dev/null || echo "")
        
        if [ -n "$BREW_EMSCRIPTEN" ] && [ -d "$BREW_EMSCRIPTEN" ]; then
            echo "Found Homebrew installed emscripten: $BREW_EMSCRIPTEN"
            export PATH="$BREW_EMSCRIPTEN/bin:$PATH"
            export EMSDK="$BREW_EMSCRIPTEN"
        else
            echo "Homebrew installed emscripten not found, please install first:"
            echo "brew install emscripten"
            exit 1
        fi
    else
        echo "Error: emcc command not found, please install Emscripten."
        echo "  Option 1: Install and activate emsdk (https://emscripten.org/docs/getting_started/downloads.html)"
        echo "  Option 2: Install via Homebrew (brew install emscripten)"
        exit 1
    fi
fi

# Confirm emcc is now available
if ! command -v emcc &> /dev/null; then
    echo "Error: emcc command is still not available even after attempting to locate emscripten."
    exit 1
fi

# Create build directory
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Set Emscripten compiler flags for better performance and memory handling
export EMCC_CFLAGS="-O3 -s WASM=1 -s ALLOW_MEMORY_GROWTH=1 -s TOTAL_MEMORY=67108864 -s DISABLE_EXCEPTION_CATCHING=0"
export EMCC_CXXFLAGS="${EMCC_CFLAGS} -std=c++11"

# Build configuration
echo "Configuring WebAssembly build..."
emcmake cmake "${PROJECT_DIR}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="${INSTALL_DIR}" \
  -DGPUPIXEL_BUILD_SHARED_LIBS=OFF \
  -DGPUPIXEL_ENABLE_FACE_DETECTOR=ON \
  -DGPUPIXEL_BUILD_DESKTOP_DEMO=ON \
  -DCMAKE_CXX_FLAGS="${EMCC_CXXFLAGS}" \
  -DCMAKE_C_FLAGS="${EMCC_CFLAGS}"

# Build
echo "Building..."
emmake make -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)

# Install
echo "Installing..."
emmake make install

# Copy additional files for development convenience
echo "Copying additional development files..."
DEMO_DIR="${PROJECT_DIR}/demo/wasm"

# Copy serve.py to output/bin
if [ -f "${DEMO_DIR}/serve.py" ]; then
    cp "${DEMO_DIR}/serve.py" "${INSTALL_DIR}/bin/"
    echo "Copied serve.py to output/bin/"
fi

# Copy logo directory to output/bin
if [ -d "${DEMO_DIR}/logo" ]; then
    cp -r "${DEMO_DIR}/logo" "${INSTALL_DIR}/bin/"
    echo "Copied logo directory to output/bin/"
fi

echo "WebAssembly build completed!"
echo "Static libraries and demo programs have been installed to: ${INSTALL_DIR}"
echo ""
echo "To run the demo:"
echo "  cd ${INSTALL_DIR}/bin"
echo "  python3 serve.py"
echo "  Open http://localhost:8080 in your browser"