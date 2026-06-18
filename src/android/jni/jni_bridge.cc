// JNI bridge for the Android build of the beauty filter library.
//
// Mirrors the flat C API used on iOS (`demo/ios/ios_beauty.mm`) and the WASM
// demo (`demo/wasm_html/wasm_app.cc`) so the same pipeline and parameter
// conventions apply across platforms.
//
// The Java side (com.aibeauty.beautyfilter.BeautyFilterNative) declares the
// matching `native` methods and calls `System.loadLibrary("beautyfilter")`.
//
// Pipeline:
//   - Face detection ON (default, mars-face-kit prebuilt present):
//       SourceRawData → BlusherFilter → FaceReshapeFilter → BeautyFaceFilter
//                     → SinkRawData (CPU readback)
//     Landmarks from FaceDetector::Detect() drive blusher/reshape, matching iOS.
//   - Face detection OFF:
//       SourceRawData → BeautyFaceFilter → SinkRawData
//     (blusher/reshape are created so setters work, but stay out of the chain
//      since they are landmark-driven and would have nothing to anchor to).

#include <jni.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "android/jni/jni_helpers.h"
#include "gpupixel/gpupixel.h"

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
#include "gpupixel/face_detector/face_detector.h"
#endif

using namespace gpupixel;

namespace {
std::shared_ptr<SourceRawData> g_source;
std::shared_ptr<SinkRawData> g_sink;
std::shared_ptr<BeautyFaceFilter> g_beauty;
std::shared_ptr<FaceReshapeFilter> g_reshape;
std::shared_ptr<BlusherFilter> g_blusher;

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
std::shared_ptr<FaceDetector> g_face_detector;
#endif

bool g_initialized = false;
std::mutex g_mutex;
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeInit(JNIEnv* env,
                                                             jclass /*clazz*/,
                                                             jstring res_path) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_initialized) {
    return 1;
  }

  std::string path = jni::JStringToStdString(env, res_path);
  GPUPixel::SetResourceRoot(path);

  g_source = SourceRawData::Create();
  g_sink = SinkRawData::Create();
  g_beauty = BeautyFaceFilter::Create();
  g_reshape = FaceReshapeFilter::Create();
  g_blusher = BlusherFilter::Create();

  if (!g_source || !g_sink || !g_beauty) {
    GPUPIXEL_JNI_LOGE("nativeInit failed: could not create pipeline objects");
    return -1;
  }

  // Default: light smoothing + light whitening so the effect is visible.
  g_beauty->SetBlurAlpha(0.7f);
  g_beauty->SetWhite(0.1f);

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  // Full chain with landmark-driven makeup/reshape (matches iOS).
  g_face_detector = FaceDetector::Create();
  g_source->AddSink(g_blusher);
  g_blusher->AddSink(g_reshape);
  g_reshape->AddSink(g_beauty);
  g_beauty->AddSink(g_sink);
#else
  // No detector: only the landmark-free beauty filter is in the chain.
  g_source->AddSink(g_beauty);
  g_beauty->AddSink(g_sink);
#endif

  g_initialized = true;
  GPUPIXEL_JNI_LOGI("nativeInit done, resourceRoot=%s", path.c_str());
  return 0;
}

JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeSetBeautyParams(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jfloat smoothing,
    jfloat whitening) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_beauty) {
    return;
  }
  g_beauty->SetBlurAlpha(smoothing / 10.0f);
  if (whitening > 0.0f) {
    g_beauty->SetWhite(whitening / 20.0f);
  }
}

JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeSetReshapeParams(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jfloat face_slim,
    jfloat eye_enlarge) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_reshape) {
    return;
  }
  g_reshape->SetFaceSlimLevel(face_slim / 200.0f);
  g_reshape->SetEyeZoomLevel(eye_enlarge / 100.0f);
}

JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeSetMakeupParams(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jfloat blusher) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_blusher) {
    return;
  }
  g_blusher->SetBlendLevel(blusher / 10.0f);
}

// Runs face detection on an RGBA frame (typically downscaled by the caller) and
// pushes the resulting landmarks onto the blusher/reshape filters. Detection is
// the heaviest per-frame cost, so the caller throttles it (every Nth frame) and
// feeds a small frame; landmarks are normalized [0,1] so they still map onto the
// full-resolution frame processed by nativeProcessInto. `small_rgba` must be a
// direct ByteBuffer of at least width*height*4 bytes. No-op without a detector.
JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeDetectFace(
    JNIEnv* env,
    jclass /*clazz*/,
    jobject small_rgba,
    jint width,
    jint height) {
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_initialized || !g_face_detector || small_rgba == nullptr ||
      width <= 0 || height <= 0) {
    return;
  }
  const uint8_t* pixels = reinterpret_cast<const uint8_t*>(
      env->GetDirectBufferAddress(small_rgba));
  if (pixels == nullptr) {
    GPUPIXEL_JNI_LOGE("nativeDetectFace: arg must be a direct ByteBuffer");
    return;
  }
  if (env->GetDirectBufferCapacity(small_rgba) <
      static_cast<jlong>(width) * height * 4) {
    return;
  }
  std::vector<float> landmarks = g_face_detector->Detect(
      pixels, width, height, width * 4, GPUPIXEL_MODE_FMT_VIDEO,
      GPUPIXEL_FRAME_TYPE_RGBA);
  if (g_blusher) g_blusher->SetFaceLandmarks(landmarks);
  if (g_reshape) g_reshape->SetFaceLandmarks(landmarks);
#else
  (void)env;
  (void)small_rgba;
  (void)width;
  (void)height;
#endif
}

// Runs one RGBA frame through the GL pipeline (using the most recent landmarks
// from nativeDetectFace) and writes the filtered result into `out_rgba`. Both
// buffers must be direct ByteBuffers of at least width*height*4 bytes; nothing
// is allocated per frame. Returns true on success.
JNIEXPORT jboolean JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeProcessInto(
    JNIEnv* env,
    jclass /*clazz*/,
    jobject in_rgba,
    jint width,
    jint height,
    jobject out_rgba) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_initialized || in_rgba == nullptr || out_rgba == nullptr ||
      width <= 0 || height <= 0) {
    return JNI_FALSE;
  }

  const jlong needed = static_cast<jlong>(width) * height * 4;
  const uint8_t* in =
      reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(in_rgba));
  uint8_t* out =
      reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(out_rgba));
  if (in == nullptr || out == nullptr) {
    GPUPIXEL_JNI_LOGE("nativeProcessInto: args must be direct ByteBuffers");
    return JNI_FALSE;
  }
  if (env->GetDirectBufferCapacity(in_rgba) < needed ||
      env->GetDirectBufferCapacity(out_rgba) < needed) {
    GPUPIXEL_JNI_LOGE("nativeProcessInto: buffer too small (need %lld)",
                      static_cast<long long>(needed));
    return JNI_FALSE;
  }

  // Synchronous: ProcessData blocks on the GL worker thread, so the sink buffer
  // is ready immediately after it returns.
  g_source->ProcessData(in, width, height, width * 4, GPUPIXEL_FRAME_TYPE_RGBA);

  const uint8_t* result = g_sink->GetRgbaBuffer();
  if (result == nullptr) {
    return JNI_FALSE;
  }
  const jlong out_size =
      static_cast<jlong>(g_sink->GetWidth()) * g_sink->GetHeight() * 4;
  if (out_size != needed) {
    GPUPIXEL_JNI_LOGE("nativeProcessInto: size mismatch (out %lld, need %lld)",
                      static_cast<long long>(out_size),
                      static_cast<long long>(needed));
    return JNI_FALSE;
  }
  std::memcpy(out, result, static_cast<size_t>(needed));
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_aibeauty_beautyfilter_BeautyFilterNative_nativeDestroy(
    JNIEnv* /*env*/,
    jclass /*clazz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_source.reset();
  g_sink.reset();
  g_beauty.reset();
  g_reshape.reset();
  g_blusher.reset();
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  g_face_detector.reset();
#endif
  g_initialized = false;
}

}  // extern "C"
