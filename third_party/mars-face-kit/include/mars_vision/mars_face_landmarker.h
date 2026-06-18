//
//  mars-face-kit
//
//  Created by PixPark on 2024/11/18.
//

#pragma once

#include "mars_vision/mars_defines.h"
namespace mars_face_kit {

enum class RunningMode {
  // Run the vision detection on single image inputs.
  IMAGE = 0,
  // Run the vision detection on the decoded frames of an input video.
  VIDEO = 1,
};

struct FaceLandmarkerOptions {
  // The path to the model file.
  std::string model_path;

  // The running mode of the detection. Default to the image mode.
  // FaceLandmarker has two running modes:
  // 1) The image mode for recognizing face key points on single image
  // inputs. 2) The video mode for recognizing face key points on the decoded
  // frames of a
  //    video.
  RunningMode running_mode;
  // The maximum number of faces can be detected by the FaceLandmarker.
  int num_faces = 1;
  // The minimum confidence score of face presence score in the face landmark
  // detection.
  float min_face_presence_confidence = 0.5;
};

struct FaceDetectionInfo {
  // face roi area
  Rect rect = {0.0f, 0.0f, 0.0f, 0.0f};
  // 2-D key points (filled by prebuilt at offset 16)
  std::vector<Point2d> key_points = {};
  // 3-D key points present in the prebuilt's ABI (offset 40, 24 bytes).
  // Must be declared to keep sizeof(FaceDetectionInfo)==96 and to let
  // our destructor free the prebuilt-allocated Point3d array.
  std::vector<Point3d> key_points_3d = {};
  // Remaining 32 bytes of POD scalar fields in the prebuilt.
  // Copied as raw bytes; no heap pointers, no destructor needed.
  char _reserved[32] = {};
};

class MARS_VISION_API MarsFaceDetector {
 public:
  static std::shared_ptr<MarsFaceDetector> CreateFaceDetector();

  // set models and resources root path
  virtual int Init(std::string models_path) = 0;

  // detect face key points
  virtual int Detect(const MarsImage& image,
                     std::vector<FaceDetectionInfo>& results) = 0;

#ifdef FB_DEBUG
  MarsImage roi_image;
#endif
};
}  // namespace mars_vision
