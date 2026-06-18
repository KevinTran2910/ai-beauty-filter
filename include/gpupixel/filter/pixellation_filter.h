#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/filter.h"

namespace gpupixel {
class GPUPIXEL_API PixellationFilter : public Filter {
 public:
  static std::shared_ptr<PixellationFilter> Create();
  bool Init();
  virtual bool DoRender(bool updateSinks = true) override;

  void setPixelSize(float pixel_size);

 protected:
  PixellationFilter() {};

  float pixel_size_;
};

}  // namespace gpupixel
