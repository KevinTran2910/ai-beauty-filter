#include "wasm_app.h"
#include <iostream>
#include <memory>
#include <vector>
#include <mutex>
#include "gpupixel/gpupixel.h"

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
#include "gpupixel/face_detector/custom_face_detector.h"
#endif

using namespace gpupixel;

/**
 * Global variables for managing GPUPixel components
 * These are kept in an anonymous namespace to avoid external linkage
 */
namespace {
std::shared_ptr<SourceRawData> source_raw_data_;  // Raw data source component
std::shared_ptr<SinkRender> sink_render_canvas_;  // Canvas rendering sink
std::shared_ptr<BeautyFaceFilter> beauty_filter_;  // Beauty filter for face processing
std::shared_ptr<FaceReshapeFilter> reshape_filter_; // Face reshape filter
std::shared_ptr<BlusherFilter> blusher_filter_; // Blusher filter

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
std::shared_ptr<CustomFaceDetector> face_detector_; // Face detector
#endif

bool g_is_initialized = false;  // Global initialization flag
std::mutex g_processing_mutex;  // Mutex to protect concurrent access to processing pipeline
}  // namespace

/**
 * Initializes the GPUPixel system with the specified resource path
 * @param resource_path Path to GPUPixel resources
 * @return 0 on success, 1 if already initialized
 */
int Init(const char* resource_path) {
  std::cout << "Init: Starting GPUPixel initialization, resource path: "
            << resource_path << std::endl;

  if (g_is_initialized) {
    std::cout << "Init: Already initialized, skipping initialization"
              << std::endl;
    return 1;
  }

  GPUPixel::SetResourceRoot(resource_path);
  std::cout << "Init: Resource root directory set" << std::endl;

  source_raw_data_ = SourceRawData::Create();
  std::cout << "Init: SourceRawData created" << std::endl;

  sink_render_canvas_ = SinkRender::Create();
  sink_render_canvas_->SetFillMode(SinkRender::FillMode::PreserveAspectRatio);
  sink_render_canvas_->SetMirror(false);
  std::cout << "Init: SinkRender created" << std::endl;

  beauty_filter_ = BeautyFaceFilter::Create();
  std::cout << "Init: BeautyFaceFilter created" << std::endl;

  // Set default whiteness 
  if (beauty_filter_) {
    beauty_filter_->SetWhite(0.1f);
    std::cout << "Init: Default whiteness set to 0.1/1.0" << std::endl;
  }

  reshape_filter_ = FaceReshapeFilter::Create();
  std::cout << "Init: FaceReshapeFilter created" << std::endl;


  blusher_filter_ = BlusherFilter::Create();
  std::cout << "Init: BlusherFilter created" << std::endl;

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  face_detector_ = CustomFaceDetector::Create(4);
  std::cout << "Init: CustomFaceDetector created" << std::endl;
#endif


  source_raw_data_->AddSink(blusher_filter_);
  blusher_filter_->AddSink(reshape_filter_);
  reshape_filter_->AddSink(beauty_filter_);
  beauty_filter_->AddSink(sink_render_canvas_);
  std::cout << "Init: Processing pipeline configured" << std::endl;

  g_is_initialized = true;
  std::cout << "Init: Initialization completed successfully" << std::endl;
  return 0;
}

/**
 * Cleans up and destroys all GPUPixel components
 */
void Destroy() {
  std::cout << "Destroy: Starting resource cleanup" << std::endl;

  source_raw_data_.reset();
  std::cout << "Destroy: SourceRawData released" << std::endl;

  sink_render_canvas_.reset();
  std::cout << "Destroy: SinkRender released" << std::endl;

  beauty_filter_.reset();
  std::cout << "Destroy: BeautyFaceFilter released" << std::endl;

  reshape_filter_.reset();
  std::cout << "Destroy: FaceReshapeFilter released" << std::endl;

  blusher_filter_.reset();
  std::cout << "Destroy: BlusherFilter released" << std::endl;

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  face_detector_.reset();
  std::cout << "Destroy: CustomFaceDetector released" << std::endl;
#endif

  g_is_initialized = false;
  std::cout << "Destroy: Resource cleanup completed" << std::endl;
}

/**
 * Updates beauty parameters for face processing
 * @param smoothing Smoothing intensity (0-10)
 * @param whitening Whitening intensity (0-10). If <= 0, keeps current whiteness.
 */
void SetBeautyParams(float smoothing, float whitening) {
  std::cout << "SetBeautyParams: Setting beauty parameters - Smoothing: "
            << smoothing << ", Whitening: " << whitening << std::endl;

  if (beauty_filter_) {
    float blurAlpha = smoothing / 10.0f;
    beauty_filter_->SetBlurAlpha(blurAlpha);

    // Only update whiteness when caller passes a positive value.
    // Default whiteness (0.1/1.0)
    if (whitening > 0.0f) {
      float whiteValue = whitening / 20.0f;
      beauty_filter_->SetWhite(whiteValue);
      std::cout << "SetBeautyParams: Applied values - Blur Alpha: " << blurAlpha
                << ", White Value: " << whiteValue << std::endl;
    } else {
      std::cout << "SetBeautyParams: Applied values - Blur Alpha: " << blurAlpha
                << ", White Value: 0.1/1.0" << std::endl;
    }
  } else {
    std::cout << "SetBeautyParams: Warning - BeautyFaceFilter not initialized"
              << std::endl;
  }
}

/**
 * Updates face reshape parameters
 * @param face_slim Face slimming intensity (0-10)
 * @param eye_enlarge Eye enlarging intensity (0-10)
 */
void SetReshapeParams(float face_slim, float eye_enlarge) {
  std::cout << "SetReshapeParams: Setting reshape parameters - Face Slim: "
            << face_slim << ", Eye Enlarge: " << eye_enlarge << std::endl;

  if (reshape_filter_) {
    float faceSlimLevel = face_slim / 200.0f;
    float eyeZoomLevel = eye_enlarge / 100.0f;

    reshape_filter_->SetFaceSlimLevel(faceSlimLevel);
    reshape_filter_->SetEyeZoomLevel(eyeZoomLevel);

    std::cout << "SetReshapeParams: Applied values - Face Slim Level: " << faceSlimLevel
              << ", Eye Zoom Level: " << eyeZoomLevel << std::endl;
  } else {
    std::cout << "SetReshapeParams: Warning - FaceReshapeFilter not initialized"
              << std::endl;
  }
}

/**
 * Updates makeup parameters
 * @param blusher Blusher intensity (0-10)
 */
void SetMakeupParams(float blusher) {
  std::cout << "SetMakeupParams: Setting blusher parameter - Blusher: "
            << blusher << std::endl;

  if (blusher_filter_) {
    float blusherLevel = blusher / 10.0f;
    blusher_filter_->SetBlendLevel(blusherLevel);
    std::cout << "SetMakeupParams: Applied blusher level: " << blusherLevel << std::endl;
  } else {
    std::cout << "SetMakeupParams: Warning - BlusherFilter not initialized" << std::endl;
  }
}

/**
 * Processes a single frame of RGBA image data
 * @param rgba_data Pointer to RGBA image data
 * @param width Image width in pixels
 * @param height Image height in pixels
 */
void ProcessImage(const uint8_t* rgba_data, int width, int height) {
  if (!g_is_initialized) {
    std::cout << "ProcessImage: Error - System not initialized" << std::endl;
    return;
  }

  if (!rgba_data) {
    std::cout << "ProcessImage: Error - Image data is null" << std::endl;
    return;
  }

  if (width <= 0 || height <= 0) {
    std::cout << "ProcessImage: Error - Invalid image dimensions (width: "
              << width << ", height: " << height << ")" << std::endl;
    return;
  }

  // Use mutex to prevent concurrent access to the processing pipeline
  std::lock_guard<std::mutex> lock(g_processing_mutex);

  sink_render_canvas_->SetRenderSize(width, height);

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  // Perform face detection on the input frame
  if (face_detector_) {
    std::vector<float> landmarks = face_detector_->Detect(
        rgba_data, width, height, width * 4, GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_RGBA);

    if (blusher_filter_) blusher_filter_->SetFaceLandmarks(landmarks);
    if (reshape_filter_) reshape_filter_->SetFaceLandmarks(landmarks);
  }
#endif

  // Process the frame through the filter pipeline
  source_raw_data_->ProcessData(rgba_data, width, height, width * 4,
                              GPUPIXEL_FRAME_TYPE_RGBA);
}
