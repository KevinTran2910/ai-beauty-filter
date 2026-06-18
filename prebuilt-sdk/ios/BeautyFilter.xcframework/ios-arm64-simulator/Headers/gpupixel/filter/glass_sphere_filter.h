#pragma once

#include "gpupixel/gpupixel_define.h"
#include "gpupixel/filter/sphere_refraction_filter.h"

namespace gpupixel {
class GPUPIXEL_API GlassSphereFilter : public SphereRefractionFilter {
 public:
  static std::shared_ptr<GlassSphereFilter> Create();
  bool Init();

 protected:
  GlassSphereFilter() {};
};

}  // namespace gpupixel
