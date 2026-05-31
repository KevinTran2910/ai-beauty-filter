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

  mars_face_detector_ = mars_vision::MarsFaceLandmarker::Create();

  mars_vision::FaceLandmarkerOptions opts;
  opts.model_path = models_path;
  opts.running_mode = mars_vision::RunningMode::VIDEO;

  int ret = mars_face_detector_->Init(opts);
  assert(ret == 0 && "FaceDetector: failed to init mars_vision landmarker");
}

std::vector<float> FaceDetector::Detect(const uint8_t* data,
                                        int width,
                                        int height,
                                        int stride,
                                        GPUPIXEL_MODE_FMT fmt,
                                        GPUPIXEL_FRAME_TYPE type) {
  mars_vision::MarsImage image;
  image.data = (uint8_t*)data;
  image.width = width == stride / 4 ? width : stride / 4;
  image.height = height;
  image.stride = stride;
  image.timestamp = 0;
  image.rotate_type = mars_vision::RotateType::CLOCKWISE_0;

  if (type == GPUPIXEL_FRAME_TYPE_RGBA) {
    image.format = mars_vision::MarsImageFormat::RGBA;
  } else if (type == GPUPIXEL_FRAME_TYPE_BGRA) {
    image.format = mars_vision::MarsImageFormat::BGRA;
  }

  std::vector<mars_vision::FaceLandmarkerResult> face_results;
  std::vector<float> landmarks;

  mars_face_detector_->Detect(image, face_results);
  // Only process first face; key_points are already normalized [0,1] by the lib
  for (auto& result : face_results) {
    for (auto& point : result.key_points) {
      landmarks.push_back(point.x / width);
      landmarks.push_back(point.y / height);
    }
    break;
  }

  return landmarks;
}

}  // namespace gpupixel
