# ---- Face detection library configuration ----

if(GPUPIXEL_ENABLE_FACE_DETECTOR)
  if(${CURRENT_PLAT} STREQUAL ${PLAT_WASM})
    # WASM: custom ncnn-based face landmark library
    set(face_landmark_lib_path
        ${CMAKE_CURRENT_SOURCE_DIR}/custom-face-landmark/libs/${CURRENT_PLAT}/liblnm_face_landmark.a)

    add_library(face_landmark_lib STATIC IMPORTED GLOBAL)
    set_target_properties(
      face_landmark_lib
      PROPERTIES
        IMPORTED_LOCATION ${face_landmark_lib_path}
        INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/custom-face-landmark/include
    )

    set(ncnn_lib_path ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/lib/libncnn.a)
    add_library(ncnn STATIC IMPORTED GLOBAL)
    set_target_properties(
      ncnn PROPERTIES IMPORTED_LOCATION ${ncnn_lib_path}
      INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/include)
    target_link_libraries(face_landmark_lib INTERFACE ncnn)

    add_compile_definitions(WITH_SIMD)

  elseif(${CURRENT_PLAT} STREQUAL ${PLAT_IOS})
    # iOS: mars-face-kit prebuilt static library + MNN neural network framework
    set(mars_face_header ${CMAKE_CURRENT_SOURCE_DIR}/mars-face-kit/include)

    add_library(marsface STATIC IMPORTED GLOBAL)
    set_target_properties(
      marsface PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/mars-face-kit/libs/ios/libmars-face-kit.a
        INTERFACE_INCLUDE_DIRECTORIES ${mars_face_header})
    add_library(marsface::marsface ALIAS marsface)

    # MNN neural network framework (required by mars-face-kit on iOS)
    set(mnn_lib_path ${CMAKE_CURRENT_SOURCE_DIR}/mnn/libs/ios)
    find_library(mnn_lib NAMES "MNN" PATHS ${mnn_lib_path} NO_DEFAULT_PATH)
    add_library(mnn STATIC IMPORTED GLOBAL)
    set_target_properties(mnn PROPERTIES IMPORTED_LOCATION ${mnn_lib})

  elseif(${CURRENT_PLAT} STREQUAL ${PLAT_ANDROID})
    # Android: mars-face-kit prebuilt SHARED library per ABI. Unlike iOS it
    # bundles its own inference backend (no MNN needed) and only depends on
    # system libs (liblog/libm/libdl/libc).
    set(mars_face_header ${CMAKE_CURRENT_SOURCE_DIR}/mars-face-kit/include)
    set(_marsface_so
        ${CMAKE_CURRENT_SOURCE_DIR}/mars-face-kit/libs/android/${ANDROID_ABI}/libmars-face-kit.so)

    if(NOT EXISTS ${_marsface_so})
      message(FATAL_ERROR
        "GPUPIXEL_ENABLE_FACE_DETECTOR=ON but no mars-face-kit prebuilt for "
        "ABI '${ANDROID_ABI}' at:\n  ${_marsface_so}\n"
        "Add the prebuilt, build a supported ABI (arm64-v8a / armeabi-v7a), "
        "or configure with -DGPUPIXEL_ENABLE_FACE_DETECTOR=OFF.")
    endif()

    add_library(marsface SHARED IMPORTED GLOBAL)
    set_target_properties(marsface PROPERTIES
      IMPORTED_LOCATION ${_marsface_so}
      INTERFACE_INCLUDE_DIRECTORIES ${mars_face_header})
    add_library(marsface::marsface ALIAS marsface)
  endif()
endif()

# ---- libyuv configuration ----
# YUV image processing library for efficient color space conversion
add_subdirectory(libyuv EXCLUDE_FROM_ALL) # EXCLUDE_FROM_ALL means don't install
                                          # yuv target

target_include_directories(
  yuv PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/libyuv/include>)

# add -fPIC option to solve dynamic library linking issues
set_target_properties(yuv PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(${CURRENT_PLAT} STREQUAL ${PLAT_WASM} OR ${CURRENT_PLAT} STREQUAL ${PLAT_IOS})
  install(
    TARGETS yuv
    ARCHIVE DESTINATION lib
    LIBRARY DESTINATION lib)
endif()

# glad is only needed on WASM (desktop OpenGL loader); iOS uses OpenGLES directly
if(${CURRENT_PLAT} STREQUAL ${PLAT_WASM})
  add_library(glad STATIC ${CMAKE_CURRENT_SOURCE_DIR}/glad/src/glad.c)
  set_target_properties(glad PROPERTIES POSITION_INDEPENDENT_CODE ON)
  target_include_directories(
    glad PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/glad/include>)
endif()

# ---- stb image library configuration ----
# Header-only image loading/saving library
add_library(stb INTERFACE)

target_include_directories(
  stb INTERFACE $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/stb>)
