/*
 * GPUPixel
 *
 * Created by PixPark on 2023
 * Copyright © 2023 PixPark. All rights reserved.
 */

#ifndef GPUPIXEL_SHADER_UTILS_H_
#define GPUPIXEL_SHADER_UTILS_H_

#include <string>
#include <regex>
#include <sstream>
#include "utils/util.h"

namespace gpupixel {

class ShaderUtils {
public:
  /**
   * Transforms a shader to be compatible with WebGL
   * 
   * @param shader_source Original shader source code
   * @return WebGL-compatible shader source
   */
  static std::string TransformForWebGL(const std::string& shader_source) {
    std::string result = shader_source;
    
#ifdef GPUPIXEL_WEBGL
    // Detect WebGL version from context
    static int detectedWebGLVersion = DetectWebGLVersion();
    
    // Add appropriate precision qualifier if not present (required in WebGL)
    if (result.find("precision ") == std::string::npos) {
      result = "precision highp float;\n" + result;
    }
    
    // Add WebGL define to detect in shaders with version
    std::stringstream webglDefine;
    webglDefine << "#define GPUPIXEL_WEBGL 1\n";
    webglDefine << "#define GPUPIXEL_WEBGL_VERSION " << detectedWebGLVersion << "\n";
    
    result = webglDefine.str() + result;
    
    // No need for complex array transformation since we've coded the shaders
    // to be directly compatible with WebGL 1.0
    
    return result;
#else
    return result;
#endif
  }

private:
#ifdef GPUPIXEL_WEBGL
  // Detect which WebGL version is being used
  static int DetectWebGLVersion() {
    // Default to WebGL 1.0 if detection fails
    int version = 100;
    
    const char* glVersionStr = (const char*)glGetString(GL_VERSION);
    const char* glslVersionStr = (const char*)glGetString(GL_SHADING_LANGUAGE_VERSION);
    
    if (glslVersionStr) {
      // Parse GLSL ES version string like "OpenGL ES GLSL ES 3.00" or "WebGL GLSL ES 3.00"
      std::string versionStr(glslVersionStr);
      
      // Check for "3.00" in the version string - could be "3.00", "#.00", or 3
      if (versionStr.find("3.00") != std::string::npos || 
          versionStr.find(" 3 ") != std::string::npos ||
          versionStr.find("WebGL 2.0") != std::string::npos) {
        version = 300;
        Util::Log("INFO", "Detected WebGL 2.0 (GLSL ES 3.00)");
      } else if (versionStr.find("1.00") != std::string::npos ||
                versionStr.find("WebGL 1.0") != std::string::npos) {
        version = 100;
        Util::Log("INFO", "Detected WebGL 1.0 (GLSL ES 1.00)");
      } else {
        // Check GL_VERSION as fallback
        if (glVersionStr && strstr(glVersionStr, "WebGL 2.0")) {
          version = 300;
          Util::Log("INFO", "Detected WebGL 2.0 from GL_VERSION");
        } else {
          Util::Log("WARNING", "Unrecognized GLSL ES version string: %s, defaulting to 1.00", glslVersionStr);
        }
      }
    } else if (glVersionStr) {
      // If no GLSL version string available, try to extract from GL_VERSION
      if (strstr(glVersionStr, "WebGL 2.0")) {
        version = 300;
        Util::Log("INFO", "Detected WebGL 2.0 from GL_VERSION only");
      } else {
        Util::Log("WARNING", "Failed to detect GLSL ES version, defaulting to 1.00");
      }
    } else {
      Util::Log("WARNING", "Failed to detect GLSL ES version, defaulting to 1.00");
    }
    
    return version;
  }
#endif
};

} // namespace gpupixel

#endif // GPUPIXEL_SHADER_UTILS_H_
