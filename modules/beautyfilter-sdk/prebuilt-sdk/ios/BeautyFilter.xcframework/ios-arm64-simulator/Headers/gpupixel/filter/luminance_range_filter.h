#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/filter.h"

namespace gpupixel {
class GPUPIXEL_API LuminanceRangeFilter : public Filter {
 public:
  static std::shared_ptr<LuminanceRangeFilter> Create();
  bool Init();
  virtual bool DoRender(bool updateSinks = true) override;

  void setRangeReductionFactor(float range_reduction_factor);

 protected:
  LuminanceRangeFilter() {};
  float range_reduction_factor_;
};

}  // namespace gpupixel
