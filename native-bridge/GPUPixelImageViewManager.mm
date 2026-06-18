#import "GPUPixelImageViewManager.h"
#import "GPUPixelImageView.h"

@implementation GPUPixelImageViewManager

RCT_EXPORT_MODULE(GPUPixelImageView)

- (UIView*)view {
  return [[GPUPixelImageView alloc] init];
}

// Props set trực tiếp từ JSX → gọi các setter tương ứng trên view.
RCT_EXPORT_VIEW_PROPERTY(imageUri, NSString)
RCT_EXPORT_VIEW_PROPERTY(smoothing, float)
RCT_EXPORT_VIEW_PROPERTY(whitening, float)
RCT_EXPORT_VIEW_PROPERTY(faceSlim, float)
RCT_EXPORT_VIEW_PROPERTY(eyeEnlarge, float)
RCT_EXPORT_VIEW_PROPERTY(blusher, float)

@end
