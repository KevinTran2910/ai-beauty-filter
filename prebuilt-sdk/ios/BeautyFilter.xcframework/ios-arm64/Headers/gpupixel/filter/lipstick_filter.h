#pragma once

#include "gpupixel/filter/face_makeup_filter.h"

namespace gpupixel {
class GPUPIXEL_API LipstickFilter : public FaceMakeupFilter {
 public:
  static std::shared_ptr<LipstickFilter> Create();

  bool Init() override;

 private:
  LipstickFilter();
};

}  // namespace gpupixel
