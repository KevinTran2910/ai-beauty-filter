#include "gpupixel/filter/face_reshape_filter.h"
#include "core/gpupixel_context.h"
namespace gpupixel {

// Standard shader for desktop platforms
const std::string kGPUPixelThinFaceFragmentShaderString_Standard = R"(
 varying vec2 textureCoordinate;
 uniform sampler2D inputImageTexture;

 uniform int hasFace;
 uniform float facePoints[106 * 2];

 uniform float aspectRatio;
 uniform float thinFaceDelta;
 uniform float bigEyeDelta;
 
 vec2 enlargeEye(vec2 textureCoord, vec2 originPosition, float radius, float delta) {

     float weight = distance(vec2(textureCoord.x, textureCoord.y / aspectRatio), vec2(originPosition.x, originPosition.y / aspectRatio)) / radius;

     weight = 1.0 - (1.0 - weight * weight) * delta;
     weight = clamp(weight,0.0,1.0);
     textureCoord = originPosition + (textureCoord - originPosition) * weight;
     return textureCoord;
 }
 
 vec2 curveWarp(vec2 textureCoord, vec2 originPosition, vec2 targetPosition, float delta) {

     vec2 offset = vec2(0.0);
     vec2 result = vec2(0.0);
     vec2 direction = (targetPosition - originPosition) * delta;

     float radius = distance(vec2(targetPosition.x, targetPosition.y / aspectRatio), vec2(originPosition.x, originPosition.y / aspectRatio));
     float ratio = distance(vec2(textureCoord.x, textureCoord.y / aspectRatio), vec2(originPosition.x, originPosition.y / aspectRatio)) / radius;

     ratio = 1.0 - ratio;
     ratio = clamp(ratio, 0.0, 1.0);
     offset = direction * ratio;

     result = textureCoord - offset;

     return result;
 }

 vec2 thinFace(vec2 currentCoordinate) {
     vec2 faceIndexs[9];
     faceIndexs[0] = vec2(3., 44.);
     faceIndexs[1] = vec2(29., 44.);
     faceIndexs[2] = vec2(7., 45.);
     faceIndexs[3] = vec2(25., 45.);
     faceIndexs[4] = vec2(10., 46.);
     faceIndexs[5] = vec2(22., 46.);
     faceIndexs[6] = vec2(14., 49.);
     faceIndexs[7] = vec2(18., 49.);
     faceIndexs[8] = vec2(16., 49.);

     for(int i = 0; i < 9; i++)
     {
         int originIndex = int(faceIndexs[i].x);
         int targetIndex = int(faceIndexs[i].y);
         vec2 originPoint = vec2(facePoints[originIndex * 2], facePoints[originIndex * 2 + 1]);
         vec2 targetPoint = vec2(facePoints[targetIndex * 2], facePoints[targetIndex * 2 + 1]);
         currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     }
     return currentCoordinate;
 }

 vec2 bigEye(vec2 currentCoordinate) {
     vec2 faceIndexs[2];
     faceIndexs[0] = vec2(74., 72.);
     faceIndexs[1] = vec2(77., 75.);

     for(int i = 0; i < 2; i++)
     {
         int originIndex = int(faceIndexs[i].x);
         int targetIndex = int(faceIndexs[i].y);

         vec2 originPoint = vec2(facePoints[originIndex * 2], facePoints[originIndex * 2 + 1]);
         vec2 targetPoint = vec2(facePoints[targetIndex * 2], facePoints[targetIndex * 2 + 1]);

         float radius = distance(vec2(targetPoint.x, targetPoint.y / aspectRatio), vec2(originPoint.x, originPoint.y / aspectRatio));
         radius = radius * 5.;
         currentCoordinate = enlargeEye(currentCoordinate, originPoint, radius, bigEyeDelta);
     }
     return currentCoordinate;
 }

 void main()
 {
     vec2 positionToUse = textureCoordinate;

     if (hasFace == 1) {
         positionToUse = thinFace(positionToUse);
         positionToUse = bigEye(positionToUse);
     }

     gl_FragColor = texture2D(inputImageTexture, positionToUse);
 }
)";

// WebGL compatible shader that avoids dynamic indexing
const std::string kGPUPixelThinFaceFragmentShaderString_WebGL = R"(
 precision highp float;
 varying highp vec2 textureCoordinate;
 uniform sampler2D inputImageTexture;

 uniform int hasFace;
 uniform float facePoints[106 * 2];

 uniform highp float aspectRatio;
 uniform float thinFaceDelta;
 uniform float bigEyeDelta;
 
 vec2 enlargeEye(vec2 textureCoord, vec2 originPosition, float radius, float delta) {
     float weight = distance(vec2(textureCoord.x, textureCoord.y / aspectRatio), vec2(originPosition.x, originPosition.y / aspectRatio)) / radius;
     weight = 1.0 - (1.0 - weight * weight) * delta;
     weight = clamp(weight,0.0,1.0);
     textureCoord = originPosition + (textureCoord - originPosition) * weight;
     return textureCoord;
 }
 
 vec2 curveWarp(vec2 textureCoord, vec2 originPosition, vec2 targetPosition, float delta) {
     vec2 offset = vec2(0.0);
     vec2 result = vec2(0.0);
     vec2 direction = (targetPosition - originPosition) * delta;
     float radius = distance(vec2(targetPosition.x, targetPosition.y / aspectRatio), vec2(originPosition.x, originPosition.y / aspectRatio));
     float ratio = distance(vec2(textureCoord.x, textureCoord.y / aspectRatio), vec2(originPosition.x, originPosition.y / aspectRatio)) / radius;
     ratio = 1.0 - ratio;
     ratio = clamp(ratio, 0.0, 1.0);
     offset = direction * ratio;
     result = textureCoord - offset;
     return result;
 }

 vec2 thinFace(vec2 currentCoordinate) {
     // Manually unrolled loop with hard-coded indices to avoid dynamic array indexing
     // Iteration 0 - index 3,44
     vec2 originPoint = vec2(facePoints[6], facePoints[7]);
     vec2 targetPoint = vec2(facePoints[88], facePoints[89]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 1 - index 29,44
     originPoint = vec2(facePoints[58], facePoints[59]);
     targetPoint = vec2(facePoints[88], facePoints[89]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 2 - index 7,45
     originPoint = vec2(facePoints[14], facePoints[15]);
     targetPoint = vec2(facePoints[90], facePoints[91]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 3 - index 25,45
     originPoint = vec2(facePoints[50], facePoints[51]);
     targetPoint = vec2(facePoints[90], facePoints[91]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 4 - index 10,46
     originPoint = vec2(facePoints[20], facePoints[21]);
     targetPoint = vec2(facePoints[92], facePoints[93]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 5 - index 22,46
     originPoint = vec2(facePoints[44], facePoints[45]);
     targetPoint = vec2(facePoints[92], facePoints[93]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 6 - index 14,49
     originPoint = vec2(facePoints[28], facePoints[29]);
     targetPoint = vec2(facePoints[98], facePoints[99]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 7 - index 18,49
     originPoint = vec2(facePoints[36], facePoints[37]);
     targetPoint = vec2(facePoints[98], facePoints[99]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     // Iteration 8 - index 16,49
     originPoint = vec2(facePoints[32], facePoints[33]);
     targetPoint = vec2(facePoints[98], facePoints[99]);
     currentCoordinate = curveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
     
     return currentCoordinate;
 }

 vec2 bigEye(vec2 currentCoordinate) {
     // Iteration 0 - index 74,72
     vec2 originPoint = vec2(facePoints[148], facePoints[149]);
     vec2 targetPoint = vec2(facePoints[144], facePoints[145]);
     float radius = distance(vec2(targetPoint.x, targetPoint.y / aspectRatio), vec2(originPoint.x, originPoint.y / aspectRatio));
     radius = radius * 5.0;
     currentCoordinate = enlargeEye(currentCoordinate, originPoint, radius, bigEyeDelta);
     
     // Iteration 1 - index 77,75
     originPoint = vec2(facePoints[154], facePoints[155]);
     targetPoint = vec2(facePoints[150], facePoints[151]);
     radius = distance(vec2(targetPoint.x, targetPoint.y / aspectRatio), vec2(originPoint.x, originPoint.y / aspectRatio));
     radius = radius * 5.0;
     currentCoordinate = enlargeEye(currentCoordinate, originPoint, radius, bigEyeDelta);
     
     return currentCoordinate;
 }

 void main() {
     vec2 positionToUse = textureCoordinate;

     if (hasFace == 1) {
         positionToUse = thinFace(positionToUse);
         positionToUse = bigEye(positionToUse);
     }

     gl_FragColor = texture2D(inputImageTexture, positionToUse);
 }
)";

// Function to get the appropriate shader string based on platform
std::string getFaceReshapeShaderString() {
#ifdef GPUPIXEL_WEBGL
  return kGPUPixelThinFaceFragmentShaderString_WebGL;
#else
  #if defined(GPUPIXEL_GLES_SHADER)
    return "precision highp float;\n" + kGPUPixelThinFaceFragmentShaderString_Standard;
  #elif defined(GPUPIXEL_GL_SHADER)
    return kGPUPixelThinFaceFragmentShaderString_Standard;
  #endif
#endif
}

FaceReshapeFilter::FaceReshapeFilter() {}

FaceReshapeFilter::~FaceReshapeFilter() {}

std::shared_ptr<FaceReshapeFilter> FaceReshapeFilter::Create() {
  auto ret = std::shared_ptr<FaceReshapeFilter>(new FaceReshapeFilter());
  gpupixel::GPUPixelContext::GetInstance()->SyncRunWithContext([&] {
    if (ret && !ret->Init()) {
      ret.reset();
    }
  });
  return ret;
}

bool FaceReshapeFilter::Init() {
  // Use our shader selection function to get the appropriate shader
  if (!InitWithFragmentShaderString(getFaceReshapeShaderString())) {
    return false;
  }
  RegisterProperty("thin_face", 0,
                   "The smoothing of filter with range between -1 and 1.",
                   [this](float& val) { SetFaceSlimLevel(val); });

  RegisterProperty("big_eye", 0,
                   "The smoothing of filter with range between -1 and 1.",
                   [this](float& val) { SetEyeZoomLevel(val); });

  std::vector<float> defaut;
  RegisterProperty("face_landmark", defaut,
                   "The face landmark of filter with range between -1 and 1.",
                   [this](std::vector<float> val) { SetFaceLandmarks(val); });

  this->thin_face_delta_ = 0.0;
  // [0, 0.15]
  this->big_eye_delta_ = 0.0;
  return true;
}

void FaceReshapeFilter::SetFaceLandmarks(std::vector<float> landmarks) {
  const size_t required_coords = 106 * 2;
  if (landmarks.size() < required_coords) {
    has_face_ = false;
    face_landmarks_.clear();
    return;
  }

  face_landmarks_ = landmarks;
  has_face_ = true;
}

bool FaceReshapeFilter::DoRender(bool updateSinks) {
  float aspect = (float)framebuffer_->GetWidth() / framebuffer_->GetHeight();
  filter_program_->SetUniformValue("aspectRatio", aspect);

  filter_program_->SetUniformValue("thinFaceDelta", this->thin_face_delta_);

  filter_program_->SetUniformValue("bigEyeDelta", this->big_eye_delta_);

  filter_program_->SetUniformValue("hasFace", has_face_);
  if (has_face_) {
    filter_program_->SetUniformValue("facePoints", face_landmarks_.data(),
                                     static_cast<int>(face_landmarks_.size()));
  }
  return Filter::DoRender(updateSinks);
}

#pragma mark - face slim
void FaceReshapeFilter::SetFaceSlimLevel(float level) {
  thin_face_delta_ = level;
}

#pragma mark - eye zoom
void FaceReshapeFilter::SetEyeZoomLevel(float level) {
  big_eye_delta_ = level;
}

}  // namespace gpupixel
