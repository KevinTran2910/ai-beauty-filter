#include "gpupixel/face_detector/face_detector.h"
#include <cassert>
#include "mars_vision/mars_defines.h"
#include "mars_vision/mars_face_landmarker.h"
#include "utils/util.h"

namespace gpupixel {

std::shared_ptr<FaceDetector> FaceDetector::Create() {
  return std::shared_ptr<FaceDetector>(new FaceDetector());
}

FaceDetector::FaceDetector() {
  std::string models_path = Util::GetResourcePath("models");

  mars_face_detector_ = mars_face_kit::MarsFaceDetector::CreateFaceDetector();

  int ret = mars_face_detector_->Init(models_path);
  assert(ret == 0 && "FaceDetector: failed to init face detector");
}

std::vector<float> FaceDetector::Detect(const uint8_t* data,
                                        int width,
                                        int height,
                                        int stride,
                                        GPUPIXEL_MODE_FMT /*fmt*/,
                                        GPUPIXEL_FRAME_TYPE type) {
  mars_face_kit::MarsImage image = {};
  image.data = const_cast<uint8_t*>(data);
  image.width = (stride > 0 && stride / 4 != width) ? stride / 4 : width;
  image.height = height;
  image.stride = stride;
  image.timestamp = 0;
  image.rotate_type = mars_face_kit::RotateType::CLOCKWISE_0;
  image.format = (type == GPUPIXEL_FRAME_TYPE_RGBA)
                     ? mars_face_kit::MarsImageFormat::RGBA
                     : mars_face_kit::MarsImageFormat::BGRA;

  std::vector<mars_face_kit::FaceDetectionInfo> face_results;
  std::vector<float> landmarks;

  mars_face_detector_->Detect(image, face_results);

  if (!face_results.empty()) {
    const float inv_w = 1.0f / static_cast<float>(width);
    const float inv_h = 1.0f / static_cast<float>(height);
    for (const auto& point : face_results[0].key_points) {
      landmarks.push_back(point.x * inv_w);
      landmarks.push_back(point.y * inv_h);
    }
  }

  return landmarks;
}

}  // namespace gpupixel
