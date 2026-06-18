#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/filter.h"

namespace gpupixel {
class GPUPIXEL_API PosterizeFilter : public Filter {
 public:
  static std::shared_ptr<PosterizeFilter> Create();
  bool Init();
  virtual bool DoRender(bool updateSinks = true) override;

  void setColorLevels(int color_levels);

 protected:
  PosterizeFilter() {};

  int color_levels_;
};

}  // namespace gpupixel
