# Define project name and initialize project
set(gpupixel_libs_name "beautyfilter")

# WASM-specific configuration
# No special compilation options needed for WASM

# index file
set(common_source_files
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_program.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_context.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_framebuffer.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_framebuffer_factory.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/source/source.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/source/source_raw_data.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/source/source_image.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/sink/sink_raw_data.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/sink/sink_render.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/sink/sink.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/utils/math_toolbox.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/utils/dispatch_queue.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/utils/util.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/contrast_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/glass_sphere_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/brightness_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/ios_blur_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/hsb_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/sobel_edge_detection_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/sphere_refraction_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/directional_sobel_edge_detection_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/blusher_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/box_high_pass_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/luminance_range_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/box_blur_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/sketch_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/directional_non_maximum_suppression_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/toon_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/pixellation_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/beauty_face_unit_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/single_component_gaussian_blur_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/non_maximum_suppression_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/canny_edge_detection_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/bilateral_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/color_matrix_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/exposure_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/rgb_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/face_makeup_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/hue_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/nearby_sampling3x3_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/posterize_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/color_invert_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/single_component_gaussian_blur_mono_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/gaussian_blur_mono_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/convolution3x3_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/weak_pixel_inclusion_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/halftone_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/saturation_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/emboss_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/grayscale_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/lipstick_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/box_mono_blur_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/box_difference_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/crosshatch_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/filter_group.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/gaussian_blur_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/beauty_face_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/face_reshape_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/white_balance_filter.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/filter/smooth_toon_filter.cc)

# Add face detection source files based on options
if(GPUPIXEL_ENABLE_FACE_DETECTOR)
  if (${CURRENT_PLAT} STREQUAL ${PLAT_WASM})
    list(APPEND common_source_files
         ${CMAKE_CURRENT_SOURCE_DIR}/face_detector/custom_face_detector.cc)
  else()
  list(APPEND common_source_files
       ${CMAKE_CURRENT_SOURCE_DIR}/face_detector/face_detector.cc)
  endif()
endif()

set(lib_source_code_files ${common_source_files})

# header files
set(public_common_header_files
    ${PROJECT_SOURCE_DIR}/include/gpupixel/gpupixel.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/gpupixel_define.h)

# Add face detection header files based on options
if(GPUPIXEL_ENABLE_FACE_DETECTOR)
  if (${CURRENT_PLAT} STREQUAL ${PLAT_WASM})
    set(public_face_detector_header_files
        ${PROJECT_SOURCE_DIR}/include/gpupixel/face_detector/custom_face_detector.h)
  else()
    set(public_face_detector_header_files
        ${PROJECT_SOURCE_DIR}/include/gpupixel/face_detector/face_detector.h)
  endif()
else()
  set(public_face_detector_header_files "")
endif()

set(public_source_header_files
    ${PROJECT_SOURCE_DIR}/include/gpupixel/source/source.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/source/source_raw_data.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/source/source_image.h)

set(public_sink_header_files
    ${PROJECT_SOURCE_DIR}/include/gpupixel/sink/sink_raw_data.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/sink/sink.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/sink/sink_render.h)

set(public_utils_header_files
    ${PROJECT_SOURCE_DIR}/include/gpupixel/utils/math_toolbox.h)

set(public_filter_header_files
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/gaussian_blur_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/non_maximum_suppression_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/weak_pixel_inclusion_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/crosshatch_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/box_high_pass_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/rgb_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/white_balance_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/smooth_toon_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/brightness_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/single_component_gaussian_blur_mono_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/grayscale_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/toon_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/contrast_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/lipstick_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/directional_non_maximum_suppression_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/glass_sphere_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/sketch_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/convolution3x3_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/luminance_range_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/posterize_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/exposure_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/sphere_refraction_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/color_matrix_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/hue_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/blusher_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/bilateral_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/face_reshape_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/box_mono_blur_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/nearby_sampling3x3_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/gaussian_blur_mono_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/face_makeup_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/emboss_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/canny_edge_detection_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/box_difference_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/beauty_face_unit_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/hsb_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/directional_sobel_edge_detection_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/sobel_edge_detection_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/beauty_face_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/single_component_gaussian_blur_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/box_blur_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/saturation_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/color_invert_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/filter_group.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/ios_blur_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/halftone_filter.h
    ${PROJECT_SOURCE_DIR}/include/gpupixel/filter/pixellation_filter.h)

set(internal_core_header_files
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_program.h
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_framebuffer_factory.h
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_context.h
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_framebuffer.h
    ${CMAKE_CURRENT_SOURCE_DIR}/core/gpupixel_gl_include.h)

set(internal_utils_header_files
    ${CMAKE_CURRENT_SOURCE_DIR}/utils/dispatch_queue.h
    ${CMAKE_CURRENT_SOURCE_DIR}/utils/shader_utils.h
    ${CMAKE_CURRENT_SOURCE_DIR}/utils/util.h)

set(lib_header_code_files
    ${public_common_header_files}
    ${public_face_detector_header_files}
    ${public_source_header_files}
    ${public_sink_header_files}
    ${public_filter_header_files}
    ${public_utils_header_files}
    ${internal_core_header_files}
    ${internal_utils_header_files})

set(lib_image_resource_files
    ${CMAKE_CURRENT_SOURCE_DIR}/res/mouth.png
    ${CMAKE_CURRENT_SOURCE_DIR}/res/blusher.png
    ${CMAKE_CURRENT_SOURCE_DIR}/res/lookup_gray.png
    ${CMAKE_CURRENT_SOURCE_DIR}/res/lookup_light.png
    ${CMAKE_CURRENT_SOURCE_DIR}/res/lookup_custom.png
    ${CMAKE_CURRENT_SOURCE_DIR}/res/lookup_origin.png
    ${CMAKE_CURRENT_SOURCE_DIR}/res/lookup_skin.png)

set(custom_lib_models_resource_dir
    ${PROJECT_SOURCE_DIR}/third_party/custom-face-landmark/models)

# iOS: add ObjcView + SinkView rendering sources.
# NOTE: append to lib_source_code_files (the list add_library() actually uses);
# common_source_files was already snapshotted into it above, so appending there
# would silently drop these files from the build.
if(${CURRENT_PLAT} STREQUAL ${PLAT_IOS})
  list(APPEND lib_source_code_files
       ${CMAKE_CURRENT_SOURCE_DIR}/sink/objc_view.mm
       ${CMAKE_CURRENT_SOURCE_DIR}/sink/sink_view.mm)
endif()

# Library configuration
# ##############################################################################

if(GPUPIXEL_BUILD_SHARED_LIBS)
  add_library(
    ${gpupixel_libs_name} SHARED
    ${lib_source_code_files} ${lib_header_code_files}
    ${lib_image_resource_files})
else()
  add_library(
    ${gpupixel_libs_name} STATIC
    ${lib_source_code_files} ${lib_header_code_files}
    ${lib_image_resource_files})
endif()

target_include_directories(
  ${gpupixel_libs_name} PUBLIC ${PROJECT_SOURCE_DIR}/include
                               ${CMAKE_CURRENT_SOURCE_DIR})

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# ---- Platform-specific library dependencies ----

if(${CURRENT_PLAT} STREQUAL ${PLAT_WASM})
  target_link_libraries(${gpupixel_libs_name} PRIVATE yuv stb glad GL face_landmark_lib ncnn)

  set(WASM_LINK_FLAGS
      "-s WASM=1 -s EXPORTED_RUNTIME_METHODS=['ccall','cwrap'] -s EXPORTED_FUNCTIONS=['_GPUPixel_Initialize','_GPUPixel_CreatePipeline','_GPUPixel_AddFilter','_GPUPixel_SetFilterParameter','_GPUPixel_ProcessImage','_GPUPixel_Destroy','_malloc','_free'] -s ALLOW_MEMORY_GROWTH=1")
  set(WASM_LINK_FLAGS "${WASM_LINK_FLAGS} -s USE_PTHREADS=1 -msimd128")
  set(WASM_LINK_FLAGS "${WASM_LINK_FLAGS}
    -s USE_WEBGL2=1 \
    -s FULL_ES3=1 \
    -s ASSERTIONS=1 \
    -s MODULARIZE=1 \
    -s EXPORT_NAME='createBeautyFilter' \
    -s ENVIRONMENT='web,worker' \
    -s PTHREAD_POOL_SIZE=8"
  )
  set_target_properties(${gpupixel_libs_name} PROPERTIES LINK_FLAGS "${WASM_LINK_FLAGS}")
  target_compile_definitions(${gpupixel_libs_name} PRIVATE __EMSCRIPTEN__)
  target_compile_options(${gpupixel_libs_name} PRIVATE "-pthread" "-msimd128")

elseif(${CURRENT_PLAT} STREQUAL ${PLAT_IOS})
  # iOS system frameworks
  find_library(OPENGLES_LIB OpenGLES REQUIRED)
  find_library(FOUNDATION_LIB Foundation REQUIRED)
  find_library(UIKIT_LIB UIKit REQUIRED)
  find_library(COREVIDEO_LIB CoreVideo REQUIRED)
  find_library(COREMEDIA_LIB CoreMedia REQUIRED)
  find_library(AVFOUNDATION_LIB AVFoundation REQUIRED)
  find_library(COREML_LIB CoreML REQUIRED)
  find_library(METAL_LIB Metal REQUIRED)

  set(_ios_face_libs "")
  if(GPUPIXEL_ENABLE_FACE_DETECTOR)
    set(_ios_face_libs marsface::marsface mnn)
  endif()

  target_link_libraries(${gpupixel_libs_name} PRIVATE
    yuv stb
    ${_ios_face_libs}
    ${OPENGLES_LIB}
    ${FOUNDATION_LIB}
    ${UIKIT_LIB}
    ${COREVIDEO_LIB}
    ${COREMEDIA_LIB}
    ${AVFOUNDATION_LIB}
    ${COREML_LIB}
    ${METAL_LIB}
  )

  # gpupixel_gl_include.h uses #import <UIKit/UIKit.h> and gpupixel_context.h
  # uses EAGLContext* — both are ObjC types that C++ (.cc) cannot parse.
  # Telling Xcode to treat every source file in this target as ObjC++ fixes it
  # without renaming any file.
  set_target_properties(${gpupixel_libs_name} PROPERTIES
    XCODE_ATTRIBUTE_GCC_INPUT_FILETYPE sourcecode.cpp.objcpp
    XCODE_ATTRIBUTE_CLANG_ENABLE_OBJC_ARC YES
    XCODE_ATTRIBUTE_IPHONEOS_DEPLOYMENT_TARGET "13.0"
  )

elseif(${CURRENT_PLAT} STREQUAL ${PLAT_LINUX})
  find_package(OpenGL REQUIRED)
  target_link_libraries(${gpupixel_libs_name} PRIVATE yuv stb OpenGL::GL)

endif()

# install header

install(DIRECTORY ${PROJECT_SOURCE_DIR}/include/ DESTINATION include)

# WASM doesn't need Apple framework signing

# install library
install(
  TARGETS ${gpupixel_libs_name}
  ARCHIVE DESTINATION lib
  LIBRARY DESTINATION lib
  RUNTIME DESTINATION lib # win dll install lib
  FRAMEWORK DESTINATION lib)

# install image resource
install(
  FILES ${lib_image_resource_files}
  DESTINATION res
  COMPONENT resource)

# iOS: install face detection models alongside the library
if(${CURRENT_PLAT} STREQUAL ${PLAT_IOS} AND GPUPIXEL_ENABLE_FACE_DETECTOR)
  set(mars_models_dir ${PROJECT_SOURCE_DIR}/third_party/mars-face-kit/models)
  install(
    DIRECTORY ${mars_models_dir}/
    DESTINATION models
    COMPONENT resource)
endif()


