#import "GPUPixelViewManager.h"
#import "GPUPixelCameraView.h"

@implementation GPUPixelViewManager

RCT_EXPORT_MODULE(GPUPixelCameraView)

- (UIView*)view {
  return [[GPUPixelCameraView alloc] init];
}

// Props có thể set trực tiếp từ JSX
RCT_EXPORT_VIEW_PROPERTY(smoothing, float)
RCT_EXPORT_VIEW_PROPERTY(whitening, float)
RCT_EXPORT_VIEW_PROPERTY(faceSlim, float)
RCT_EXPORT_VIEW_PROPERTY(eyeEnlarge, float)
RCT_EXPORT_VIEW_PROPERTY(blusher, float)

@end
