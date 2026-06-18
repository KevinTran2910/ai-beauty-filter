// Minimal JNI helper utilities for the Android build of the beauty filter
// library.
//
// Several core sources (`src/utils/util.cc`, `src/source/source_image.cc`)
// `#include "android/jni/jni_helpers.h"` behind `#ifdef GPUPIXEL_ANDROID`.
// This header satisfies that include and provides a couple of small,
// reusable helpers used by the JNI bridge. The include path resolves because
// `src/` is on the target include path (see src/CMakeLists.txt).
#pragma once

#if defined(__ANDROID__)

#include <android/log.h>
#include <jni.h>

#include <string>

// Convenience logging macros (tag matches the library name).
#ifndef GPUPIXEL_JNI_LOG_TAG
#define GPUPIXEL_JNI_LOG_TAG "beautyfilter"
#endif

#define GPUPIXEL_JNI_LOGI(...) \
  __android_log_print(ANDROID_LOG_INFO, GPUPIXEL_JNI_LOG_TAG, __VA_ARGS__)
#define GPUPIXEL_JNI_LOGE(...) \
  __android_log_print(ANDROID_LOG_ERROR, GPUPIXEL_JNI_LOG_TAG, __VA_ARGS__)

namespace gpupixel {
namespace jni {

// Convert a Java string into a std::string (UTF-8). Returns an empty string
// when `jstr` is null.
inline std::string JStringToStdString(JNIEnv* env, jstring jstr) {
  if (jstr == nullptr) {
    return std::string();
  }
  const char* chars = env->GetStringUTFChars(jstr, nullptr);
  std::string result = chars ? chars : "";
  if (chars) {
    env->ReleaseStringUTFChars(jstr, chars);
  }
  return result;
}

}  // namespace jni
}  // namespace gpupixel

#endif  // __ANDROID__
