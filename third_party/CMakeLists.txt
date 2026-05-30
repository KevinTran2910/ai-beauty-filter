# ---- Custom face landmark library configuration ----
# Custom face landmark library for wasm
if(${CURRENT_PLAT} STREQUAL ${PLAT_WASM})
  # Check if the library exists at the expected path
  set(face_landmark_lib_path
      ${CMAKE_CURRENT_SOURCE_DIR}/custom-face-landmark/libs/${CURRENT_PLAT}/liblnm_face_landmark.a)
  
  # Regular library import if the file exists
  add_library(face_landmark_lib STATIC IMPORTED GLOBAL)
  set_target_properties(
    face_landmark_lib 
    PROPERTIES 
      IMPORTED_LOCATION ${face_landmark_lib_path}
      INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/custom-face-landmark/include
  )

  # Also include the ncnn library
  set(ncnn_lib_path ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/lib/libncnn.a)

  add_library(ncnn STATIC IMPORTED GLOBAL)
  
  set_target_properties(
    ncnn PROPERTIES IMPORTED_LOCATION ${ncnn_lib_path}
    INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/include)
  target_link_libraries(face_landmark_lib INTERFACE ncnn)

  # Define WITH_SIMD to enable SIMD support in the custom face landmark library
  add_compile_definitions(WITH_SIMD)
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
