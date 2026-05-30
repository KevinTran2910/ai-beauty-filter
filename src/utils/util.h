#pragma once

#include <stdlib.h>
#include <string>
#include "gpupixel/gpupixel_define.h"

namespace gpupixel {
#define rotationSwapsSize(rotation)                   \
  ((rotation) == gpupixel::RotateLeft ||              \
   (rotation) == gpupixel::RotateRight ||             \
   (rotation) == gpupixel::RotateRightFlipVertical || \
   (rotation) == gpupixel::RotateRightFlipHorizontal)

class GPUPIXEL_API Util {
 public:
  static std::string StringFormat(const char* fmt, ...);
  static void Log(const std::string& tag, std::string format, ...);
  static int64_t NowTimeMs();

  static void SetResourceRoot(std::string root);
  static std::string GetResourcePath(std::string name = "");

 private:
  static std::string resource_root_path_;
};
}  // namespace gpupixel
