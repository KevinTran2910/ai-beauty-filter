#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/filter.h"

namespace gpupixel {
class GPUPIXEL_API WhiteBalanceFilter : public Filter {
 public:
  static std::shared_ptr<WhiteBalanceFilter> Create();
  bool Init();
  virtual bool DoRender(bool updateSinks = true) override;

  void setTemperature(float temperature);
  void setTint(float tint);

 protected:
  WhiteBalanceFilter() {};

  float temperature_;
  float tint_;
};

}  // namespace gpupixel
