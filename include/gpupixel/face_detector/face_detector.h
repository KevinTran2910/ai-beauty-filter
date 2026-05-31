#pragma once

#include "gpupixel/gpupixel_define.h"
#include <vector>

namespace mars_vision {
class MarsFaceLandmarker;
}

namespace gpupixel {

class GPUPIXEL_API FaceDetector {
 public:
  static std::shared_ptr<FaceDetector> Create();
  std::vector<float> Detect(const uint8_t* data,
                            int width,
                            int height,
                            int stride,
                            GPUPIXEL_MODE_FMT fmt,
                            GPUPIXEL_FRAME_TYPE type);

 private:
  FaceDetector();
  std::shared_ptr<mars_vision::MarsFaceLandmarker> mars_face_detector_;
};
}  // namespace gpupixel
