#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/pixellation_filter.h"

namespace gpupixel {
class GPUPIXEL_API HalftoneFilter : public PixellationFilter {
 public:
  static std::shared_ptr<HalftoneFilter> Create();
  bool Init();

 protected:
  HalftoneFilter() {};
};

}  // namespace gpupixel
