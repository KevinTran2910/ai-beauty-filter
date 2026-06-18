cmake_minimum_required(VERSION 3.16)

# ---- Project ----
# GPUPixel is a GPU-accelerated image processing library supporting multiple
# platforms including WASM, iOS, Android, and desktop.

project(
  GPUPixel
  VERSION 1.3.0
  LANGUAGES CXX C)

# ---- Build options ----
option(GPUPIXEL_BUILD_SHARED_LIBS "Build shared libraries" OFF)
option(GPUPIXEL_ENABLE_FACE_DETECTOR "Enable face detection functionality" ON)
option(GPUPIXEL_BUILD_DESKTOP_DEMO "Build demo application" ON)

if(GPUPIXEL_ENABLE_FACE_DETECTOR)
  add_compile_definitions(GPUPIXEL_ENABLE_FACE_DETECTOR)
endif()

# ---- Platform detection ----
set(PLAT_WASM "wasm")
set(PLAT_IOS "ios")
set(PLAT_ANDROID "android")
set(PLAT_LINUX "linux")
set(PLAT_MAC "mac")

if(CMAKE_SYSTEM_NAME MATCHES "Emscripten")
  set(CURRENT_PLAT "wasm")
elseif(CMAKE_SYSTEM_NAME STREQUAL "iOS")
  set(CURRENT_PLAT "ios")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Android")
  set(CURRENT_PLAT "android")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
  set(CURRENT_PLAT "mac")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  set(CURRENT_PLAT "linux")
else()
  message(FATAL_ERROR "Unsupported platform: ${CMAKE_SYSTEM_NAME}")
endif()

message(STATUS "Building for platform: ${CURRENT_PLAT}")



# ---- C++ standard settings ----
# Configure the C++ language version and features
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- Print build options ----
message(STATUS "========================================")
message(STATUS "             BUILD OPTIONS              ")
message(STATUS "========================================")
message(STATUS "GPUPIXEL_BUILD_SHARED_LIBS: ${GPUPIXEL_BUILD_SHARED_LIBS}")
message(STATUS "GPUPIXEL_ENABLE_FACE_DETECTOR: ${GPUPIXEL_ENABLE_FACE_DETECTOR}")
message(STATUS "GPUPIXEL_BUILD_DESKTOP_DEMO: ${GPUPIXEL_BUILD_DESKTOP_DEMO}")

# ---- System information ----
message(STATUS "========================================")
message(STATUS "           SYSTEM INFORMATION           ")
message(STATUS "========================================")
message(STATUS "CMake Version: ${CMAKE_VERSION}")
message(STATUS "Operating System: ${CMAKE_SYSTEM_NAME}")
message(STATUS "Processor Architecture: ${CMAKE_SYSTEM_PROCESSOR}")
message(STATUS "C Compiler: ${CMAKE_C_COMPILER_ID} ${CMAKE_C_COMPILER_VERSION}")
message(
  STATUS "C++ Compiler: ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")

# ---- Build configuration ----
message(STATUS "========================================")
message(STATUS "           BUILD CONFIGURATION          ")
message(STATUS "========================================")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Install Prefix: ${CMAKE_INSTALL_PREFIX}")
message(STATUS "========================================")
message(STATUS "         CONFIGURATION COMPLETE         ")
message(STATUS "========================================")

# ---- Build safety checks ----
# Prevent in-source builds which can cause numerous issues
if(PROJECT_SOURCE_DIR STREQUAL PROJECT_BINARY_DIR)
  message(
    FATAL_ERROR
      "In-source builds not allowed. Please make a new directory (called a build directory) and run CMake from there."
  )
endif()

# ---- Add dependencies via CPM ----
# see https://github.com/TheLartians/CPM.cmake for more info CPM is a CMake
# package manager that simplifies adding dependencies

# include(cmake/CPM.cmake)

# ---- Add source files ----
# External dependencies first
add_subdirectory(third_party)

# Main library source code
add_subdirectory(src)

# Optional desktop demo application
if(GPUPIXEL_BUILD_DESKTOP_DEMO)
  add_subdirectory(demo)
endif()
