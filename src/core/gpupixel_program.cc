#include "core/gpupixel_program.h"
#include <algorithm>
#include "core/gpupixel_context.h"
#include "utils/util.h"
#include "utils/shader_utils.h"

namespace gpupixel {

std::vector<GPUPixelGLProgram*> GPUPixelGLProgram::programs_;

// Add a helper function to preprocess shaders for WebGL compatibility
std::string PreprocessShaderForWebGL(const std::string& shader_source) {
#ifdef GPUPIXEL_WEBGL
  // Use our utility class to transform the shader for WebGL compatibility
  return ShaderUtils::TransformForWebGL(shader_source);
#else
  return shader_source;
#endif
}

GPUPixelGLProgram::GPUPixelGLProgram() : program_(-1) {
  programs_.push_back(this);
}

GPUPixelGLProgram::~GPUPixelGLProgram() {
  GPUPixelContext::GetInstance()->SyncRunWithContext([=] {
    std::vector<GPUPixelGLProgram*>::iterator itr =
        std::find(programs_.begin(), programs_.end(), this);
    if (itr != programs_.end()) {
      programs_.erase(itr);
    }

    bool should_delete_program = (program_ != -1);

    for (auto const& program : programs_) {
      if (should_delete_program) {
        if (program_ == program->GetProgram()) {
          should_delete_program = false;
          break;
        }
      }
    }

    if (should_delete_program) {
      glDeleteProgram(program_);
      program_ = -1;
    }
  });
}

GPUPixelGLProgram* GPUPixelGLProgram::CreateWithShaderString(
    const std::string& vertex_shader_source,
    const std::string& fragment_shader_source) {
  GPUPixelGLProgram* ret = new (std::nothrow) GPUPixelGLProgram();
  if (ret) {
    if (!ret->InitWithShaderString(vertex_shader_source,
                                   fragment_shader_source)) {
      delete ret;
      ret = nullptr;
    }
  }
  return ret;
}

bool GPUPixelGLProgram::InitWithShaderString(
    const std::string& vertex_shader_source,
    const std::string& fragment_shader_source) {
  if (program_ != -1) {
    CHECK_GL(glDeleteProgram(program_));
    program_ = -1;
  }
  CHECK_GL(program_ = glCreateProgram());

#ifdef GPUPIXEL_WEBGL
  // Log WebGL and GLSL ES version info
  const char* glVersionStr = (const char*)glGetString(GL_VERSION);
  const char* glslVersionStr = (const char*)glGetString(GL_SHADING_LANGUAGE_VERSION);
  const char* glRendererStr = (const char*)glGetString(GL_RENDERER);
  const char* glVendorStr = (const char*)glGetString(GL_VENDOR);

  // Additional GLSL version detection
  int majorVersion = 0, minorVersion = 0;
  if (glslVersionStr) {
    // Try to parse version string (e.g., "OpenGL ES GLSL ES 1.00" or "WebGL GLSL ES 3.00")
    const char* pattern = "ES (\\d+)\\.(\\d+)";
    std::regex regex(pattern);
    std::cmatch match;
    if (std::regex_search(glslVersionStr, match, regex) && match.size() > 2) {
      majorVersion = std::stoi(match[1].str());
      minorVersion = std::stoi(match[2].str());
    }
  }
#endif

  // Preprocess shaders for WebGL if needed
  std::string processed_vertex_shader = PreprocessShaderForWebGL(vertex_shader_source);
  std::string processed_fragment_shader = PreprocessShaderForWebGL(fragment_shader_source);

  CHECK_GL(uint32_t vert_shader = glCreateShader(GL_VERTEX_SHADER));
  const char* vertex_shader_source_str = processed_vertex_shader.c_str();
  CHECK_GL(glShaderSource(vert_shader, 1, &vertex_shader_source_str, NULL));
  CHECK_GL(glCompileShader(vert_shader));

  //
  GLint compile_success;
  glGetShaderiv(vert_shader, GL_COMPILE_STATUS, &compile_success);
  if (compile_success == GL_FALSE) {
    GLchar messages[256];
    glGetShaderInfoLog(vert_shader, sizeof(messages), 0, &messages[0]);
#if defined(GPUPIXEL_IOS) || defined(GPUPIXEL_MAC)
    NSString* message_string = [NSString stringWithUTF8String:messages];
    NSLog(@"%@", message_string);
#else

#endif
    gpupixel::Util::Log(
        "ERROR",
        "GL ERROR GPUPixelGLProgram::InitWithShaderString vertex shader %s",
        messages);

    // Clean up before returning
    glDeleteShader(vert_shader);
    glDeleteProgram(program_);
    program_ = -1;
    return false;
  }

  CHECK_GL(uint32_t frag_shader = glCreateShader(GL_FRAGMENT_SHADER));
  const char* fragment_shader_source_str = processed_fragment_shader.c_str();
  CHECK_GL(glShaderSource(frag_shader, 1, &fragment_shader_source_str, NULL));
  CHECK_GL(glCompileShader(frag_shader));

  glGetShaderiv(frag_shader, GL_COMPILE_STATUS, &compile_success);
  if (compile_success == GL_FALSE) {
    GLchar messages[256];
    glGetShaderInfoLog(frag_shader, sizeof(messages), 0, &messages[0]);
#if defined(GPUPIXEL_IOS) || defined(GPUPIXEL_MAC)
    NSString* message_string = [NSString stringWithUTF8String:messages];
    NSLog(@"%@", message_string);
#else

#endif
    gpupixel::Util::Log(
        "ERROR",
        "GL ERROR GPUPixelGLProgram::InitWithShaderString frag shader %s",
        messages);

    // Clean up before returning
    glDeleteShader(vert_shader);
    glDeleteShader(frag_shader);
    glDeleteProgram(program_);
    program_ = -1;
    return false;
  }

  CHECK_GL(glAttachShader(program_, vert_shader));
  CHECK_GL(glAttachShader(program_, frag_shader));

  CHECK_GL(glLinkProgram(program_));
  
  // Check link status
  GLint link_success;
  glGetProgramiv(program_, GL_LINK_STATUS, &link_success);
  if (link_success == GL_FALSE) {
    GLchar messages[256];
    glGetProgramInfoLog(program_, sizeof(messages), 0, &messages[0]);
    gpupixel::Util::Log(
        "ERROR",
        "GL ERROR GPUPixelGLProgram::InitWithShaderString linking program: %s",
        messages);
    
    // Clean up before returning
    glDeleteShader(vert_shader);
    glDeleteShader(frag_shader);
    glDeleteProgram(program_);
    program_ = -1;
    return false;
  }
  
  // After linking, validate the program
  glValidateProgram(program_);
  GLint validate_success;
  glGetProgramiv(program_, GL_VALIDATE_STATUS, &validate_success);
  if (validate_success == GL_FALSE) {
    GLchar messages[256];
    glGetProgramInfoLog(program_, sizeof(messages), 0, &messages[0]);
    gpupixel::Util::Log(
        "WARNING",
        "GL WARNING GPUPixelGLProgram::InitWithShaderString validating program: %s",
        messages);
    // Continue execution as validation warnings might still allow the program to run
  }

  CHECK_GL(glDeleteShader(vert_shader));
  CHECK_GL(glDeleteShader(frag_shader));

  return program_ != -1;
}

void GPUPixelGLProgram::UseProgram() {
  if (program_ == -1) {
    gpupixel::Util::Log("ERROR", "Attempting to use an invalid GL program");
    return;
  }
  CHECK_GL(glUseProgram(program_));
}

uint32_t GPUPixelGLProgram::GetAttribLocation(const std::string& attribute) {
  if (program_ == -1) {
    gpupixel::Util::Log("ERROR", "GetAttribLocation called with invalid program for attribute: %s", 
                         attribute.c_str());
    return -1;
  }
  
  GLint location = glGetAttribLocation(program_, attribute.c_str());

  return location;
}

uint32_t GPUPixelGLProgram::GetUniformLocation(
    const std::string& uniform_name) {
  if (program_ == -1) {
    gpupixel::Util::Log("ERROR", "GetUniformLocation called with invalid program for uniform: %s", 
                         uniform_name.c_str());
    return -1;
  }
  
  GLint location = glGetUniformLocation(program_, uniform_name.c_str());
  return location;
}

void GPUPixelGLProgram::SetUniformValue(const std::string& uniform_name,
                                        int value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  SetUniformValue(GetUniformLocation(uniform_name), value);
}

void GPUPixelGLProgram::SetUniformValue(const std::string& uniform_name,
                                        float value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  SetUniformValue(GetUniformLocation(uniform_name), value);
}

void GPUPixelGLProgram::SetUniformValue(const std::string& uniform_name,
                                        Matrix4 value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  SetUniformValue(GetUniformLocation(uniform_name), value);
}

void GPUPixelGLProgram::SetUniformValue(const std::string& uniform_name,
                                        Vector2 value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  SetUniformValue(GetUniformLocation(uniform_name), value);
}

void GPUPixelGLProgram::SetUniformValue(const std::string& uniform_name,
                                        Matrix3 value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  SetUniformValue(GetUniformLocation(uniform_name), value);
}

void GPUPixelGLProgram::SetUniformValue(const std::string& uniform_name,
                                        const void* value,
                                        int length) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  SetUniformValue(GetUniformLocation(uniform_name), value, length);
}

void GPUPixelGLProgram::SetUniformValue(int uniform_location, int value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  CHECK_GL(glUniform1i(uniform_location, value));
}

void GPUPixelGLProgram::SetUniformValue(int uniform_location, float value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  CHECK_GL(glUniform1f(uniform_location, value));
}

void GPUPixelGLProgram::SetUniformValue(int uniform_location, Matrix4 value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  CHECK_GL(glUniformMatrix4fv(uniform_location, 1, GL_FALSE, (float*)&value));
}

void GPUPixelGLProgram::SetUniformValue(int uniform_location, Vector2 value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  CHECK_GL(glUniform2f(uniform_location, value.x, value.y));
}

void GPUPixelGLProgram::SetUniformValue(int uniform_location, Matrix3 value) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  CHECK_GL(glUniformMatrix3fv(uniform_location, 1, GL_FALSE, (float*)&value));
}

void GPUPixelGLProgram::SetUniformValue(int uniform_location,
                                        const void* value,
                                        int length) {
  GPUPixelContext::GetInstance()->SetActiveGlProgram(this);
  CHECK_GL(glUniform1fv(uniform_location, length, (float*)value));
}

}  // namespace gpupixel
