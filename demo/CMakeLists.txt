# ---- Application project definition ----
# WASM demo application for BeautyFilter library
set(GPL_APP_NAME "app")

# ---- WASM Build Configuration ----
set(APP_SOURCE_FILES "${CMAKE_CURRENT_SOURCE_DIR}/wasm_html/wasm_app.cc")
add_executable(${GPL_APP_NAME} ${APP_SOURCE_FILES})

set_target_properties(${GPL_APP_NAME} PROPERTIES SUFFIX ".js")

# Add WebGL compatibility definition
target_compile_definitions(${GPL_APP_NAME} PRIVATE GPUPIXEL_WEBGL)
# Also add the definition to beautyfilter library
get_target_property(GPUPIXEL_COMPILE_DEFS beautyfilter COMPILE_DEFINITIONS)
if(GPUPIXEL_COMPILE_DEFS)
  list(APPEND GPUPIXEL_COMPILE_DEFS GPUPIXEL_WEBGL)
else()
  set(GPUPIXEL_COMPILE_DEFS GPUPIXEL_WEBGL)
endif()
set_target_properties(beautyfilter PROPERTIES COMPILE_DEFINITIONS "${GPUPIXEL_COMPILE_DEFS}")

target_link_options(
  ${GPL_APP_NAME}
  PRIVATE
  "SHELL:-s EXPORTED_FUNCTIONS=['_Init','_Destroy','_SetBeautyParams','_SetReshapeParams','_SetMakeupParams','_ProcessImage','_malloc','_free']"
  "SHELL:-s EXPORTED_RUNTIME_METHODS=['ccall','cwrap','FS','getValue','UTF8ToString','HEAPU8']"
  "SHELL:-s USE_WEBGL2=1"
  "SHELL:-s FULL_ES3=1"
  "SHELL:-s ALLOW_MEMORY_GROWTH=1"
  "SHELL:-s ASSERTIONS=1"
  "SHELL:-s MODULARIZE=1"
  "SHELL:-s EXPORT_NAME='createBeautyFilter'"
  "SHELL:-s ENVIRONMENT='web,worker'"
  "SHELL:-s USE_PTHREADS=1"
  "SHELL:-s PTHREAD_POOL_SIZE=8"
  "SHELL:--bind"
  "SHELL:-s WASM=1"
  "SHELL:--preload-file ${PROJECT_SOURCE_DIR}/src/res/@/beautyfilter/res"
  "SHELL:--preload-file ${PROJECT_SOURCE_DIR}/third_party/custom-face-landmark/models@/models"
  )

target_link_libraries(${GPL_APP_NAME} PRIVATE beautyfilter glad)

target_compile_options(
  ${GPL_APP_NAME}
  PRIVATE
  "-pthread"
  "-sUSE_PTHREADS=1"
  "-msimd128"
  )

install(
  TARGETS ${GPL_APP_NAME}
  ARCHIVE DESTINATION lib
  LIBRARY DESTINATION lib
  RUNTIME DESTINATION bin)

# WebAssembly installation configuration
install(TARGETS ${GPL_APP_NAME} RUNTIME DESTINATION bin)

# Install all Emscripten generated files
install(
  FILES "${CMAKE_CURRENT_BINARY_DIR}/${GPL_APP_NAME}.wasm"
        "${CMAKE_CURRENT_BINARY_DIR}/${GPL_APP_NAME}.data"
        "${CMAKE_CURRENT_BINARY_DIR}/${GPL_APP_NAME}.worker.js"
  DESTINATION bin
  OPTIONAL)

install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/wasm_html/index.html" DESTINATION bin)
