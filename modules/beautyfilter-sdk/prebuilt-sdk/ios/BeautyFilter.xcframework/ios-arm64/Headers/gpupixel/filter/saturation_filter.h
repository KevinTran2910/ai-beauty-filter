#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/filter.h"

namespace gpupixel {
class GPUPIXEL_API SaturationFilter : public Filter {
 public:
  static std::shared_ptr<SaturationFilter> Create();
  bool Init();
  virtual bool DoRender(bool updateSinks = true) override;

  void setSaturation(float saturation);

 protected:
  SaturationFilter() {};

  float saturation_;
};

}  // namespace gpupixel
