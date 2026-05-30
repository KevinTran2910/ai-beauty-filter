#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/nearby_sampling3x3_filter.h"

namespace gpupixel {
class GPUPIXEL_API WeakPixelInclusionFilter : public NearbySampling3x3Filter {
 public:
  static std::shared_ptr<WeakPixelInclusionFilter> Create();
  bool Init();

 protected:
  WeakPixelInclusionFilter() {};
};

}  // namespace gpupixel
