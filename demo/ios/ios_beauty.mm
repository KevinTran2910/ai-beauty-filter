#include "ios_beauty.h"

#include <memory>
#include <vector>
#include <mutex>

#include "gpupixel/gpupixel.h"
#include "gpupixel/sink/sink_view.h"

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
#include "gpupixel/face_detector/face_detector.h"
#endif

using namespace gpupixel;

namespace {
std::shared_ptr<SourceRawData>    g_source;
std::shared_ptr<SinkView>         g_sink_view;
std::shared_ptr<BeautyFaceFilter> g_beauty;
std::shared_ptr<FaceReshapeFilter> g_reshape;
std::shared_ptr<BlusherFilter>    g_blusher;

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
std::shared_ptr<FaceDetector>     g_face_detector;
#endif

bool       g_initialized = false;
std::mutex g_mutex;
}

int GPUPixelIOS_Init(void* view, const char* resPath) {
  if (g_initialized) return 1;

  GPUPixel::SetResourceRoot(resPath);

  g_source   = SourceRawData::Create();
  g_sink_view = SinkView::Create(view);
  g_beauty   = BeautyFaceFilter::Create();
  g_reshape  = FaceReshapeFilter::Create();
  g_blusher  = BlusherFilter::Create();

  // Mặc định: làm mịn nhẹ, trắng nhẹ
  g_beauty->SetWhite(0.1f);

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  g_face_detector = FaceDetector::Create();
#endif

  // Pipeline: source → blusher → reshape → beauty → view
  g_source->AddSink(g_blusher);
  g_blusher->AddSink(g_reshape);
  g_reshape->AddSink(g_beauty);
  g_beauty->AddSink(g_sink_view);

  g_initialized = true;
  return 0;
}

void GPUPixelIOS_Destroy(void) {
  g_source.reset();
  g_sink_view.reset();
  g_beauty.reset();
  g_reshape.reset();
  g_blusher.reset();
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  g_face_detector.reset();
#endif
  g_initialized = false;
}

void GPUPixelIOS_SetBeautyParams(float smoothing, float whitening) {
  if (!g_beauty) return;
  g_beauty->SetBlurAlpha(smoothing / 10.0f);
  if (whitening > 0.0f)
    g_beauty->SetWhite(whitening / 20.0f);
}

void GPUPixelIOS_SetReshapeParams(float faceSlim, float eyeEnlarge) {
  if (!g_reshape) return;
  g_reshape->SetFaceSlimLevel(faceSlim / 200.0f);
  g_reshape->SetEyeZoomLevel(eyeEnlarge / 100.0f);
}

void GPUPixelIOS_SetMakeupParams(float blusher) {
  if (!g_blusher) return;
  g_blusher->SetBlendLevel(blusher / 10.0f);
}

void GPUPixelIOS_ProcessFrame(const uint8_t* rgbaData, int width, int height) {
  if (!g_initialized || !rgbaData || width <= 0 || height <= 0) return;

  std::lock_guard<std::mutex> lock(g_mutex);

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  if (g_face_detector) {
    std::vector<float> landmarks = g_face_detector->Detect(
        rgbaData, width, height, width * 4,
        GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_RGBA);
    if (g_blusher)  g_blusher->SetFaceLandmarks(landmarks);
    if (g_reshape)  g_reshape->SetFaceLandmarks(landmarks);
  }
#endif

  g_source->ProcessData(rgbaData, width, height, width * 4,
                        GPUPIXEL_FRAME_TYPE_RGBA);
}
