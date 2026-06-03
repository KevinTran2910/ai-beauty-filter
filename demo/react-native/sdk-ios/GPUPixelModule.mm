#import "GPUPixelModule.h"
#import "GPUPixelCameraView.h"
#import <React/RCTUIManager.h>

/**
 * GPUPixelModule
 *
 * Native Module cho phép JS/TypeScript:
 *   1. Điều khiển beauty params qua ref của GPUPixelCameraView
 *   2. Bắt/dừng camera
 *
 * Cách dùng từ JS:
 *   import { GPUPixelModule } from './GPUPixelModule';
 *   GPUPixelModule.setBeautyParams(viewRef, smoothing, whitening);
 */
@implementation GPUPixelModule

RCT_EXPORT_MODULE(GPUPixelModule);

// Bắt buộc khi extend RCTEventEmitter
- (NSArray<NSString*>*)supportedEvents {
  return @[];
}

// Chạy method trên main thread vì liên quan đến UIView
- (dispatch_queue_t)methodQueue {
  return dispatch_get_main_queue();
}

/**
 * Bắt camera và bắt đầu xử lý beauty filter.
 * @param reactTag   Tag của GPUPixelCameraView trong React Native
 */
RCT_EXPORT_METHOD(startCamera:(nonnull NSNumber*)reactTag) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager* uiManager,
                                      NSDictionary<NSNumber*, UIView*>* viewRegistry) {
    GPUPixelCameraView* view = (GPUPixelCameraView*)viewRegistry[reactTag];
    if (view && [view isKindOfClass:[GPUPixelCameraView class]]) {
      [view startCamera];
    }
  }];
}

/**
 * Dừng camera.
 */
RCT_EXPORT_METHOD(stopCamera:(nonnull NSNumber*)reactTag) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager* uiManager,
                                      NSDictionary<NSNumber*, UIView*>* viewRegistry) {
    GPUPixelCameraView* view = (GPUPixelCameraView*)viewRegistry[reactTag];
    if (view && [view isKindOfClass:[GPUPixelCameraView class]]) {
      [view stopCamera];
    }
  }];
}

/**
 * Cài đặt độ mịn và độ trắng da.
 * @param smoothing  0.0 – 10.0
 * @param whitening  0.0 – 10.0
 */
RCT_EXPORT_METHOD(setBeautyParams:(nonnull NSNumber*)reactTag
                       smoothing:(float)smoothing
                       whitening:(float)whitening) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager* uiManager,
                                      NSDictionary<NSNumber*, UIView*>* viewRegistry) {
    GPUPixelCameraView* view = (GPUPixelCameraView*)viewRegistry[reactTag];
    if (view && [view isKindOfClass:[GPUPixelCameraView class]]) {
      [view setSmoothing:smoothing];
      [view setWhitening:whitening];
    }
  }];
}

/**
 * Cài đặt chỉnh hình khuôn mặt.
 * @param faceSlim    0.0 – 10.0
 * @param eyeEnlarge  0.0 – 10.0
 */
RCT_EXPORT_METHOD(setReshapeParams:(nonnull NSNumber*)reactTag
                         faceSlim:(float)faceSlim
                       eyeEnlarge:(float)eyeEnlarge) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager* uiManager,
                                      NSDictionary<NSNumber*, UIView*>* viewRegistry) {
    GPUPixelCameraView* view = (GPUPixelCameraView*)viewRegistry[reactTag];
    if (view && [view isKindOfClass:[GPUPixelCameraView class]]) {
      [view setFaceSlim:faceSlim];
      [view setEyeEnlarge:eyeEnlarge];
    }
  }];
}

/**
 * Cài đặt má hồng.
 * @param blusher  0.0 – 10.0
 */
RCT_EXPORT_METHOD(setMakeupParams:(nonnull NSNumber*)reactTag
                          blusher:(float)blusher) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager* uiManager,
                                      NSDictionary<NSNumber*, UIView*>* viewRegistry) {
    GPUPixelCameraView* view = (GPUPixelCameraView*)viewRegistry[reactTag];
    if (view && [view isKindOfClass:[GPUPixelCameraView class]]) {
      [view setBlusher:blusher];
    }
  }];
}

@end
