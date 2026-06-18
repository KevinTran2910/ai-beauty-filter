#pragma once

#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>

/**
 * GPUPixelCameraView
 *
 * Một UIView tích hợp sẵn:
 *   - AVCaptureSession: lấy frame từ camera trước
 *   - GPUPixel pipeline: xử lý beauty filter trên GPU
 *   - SinkView: render kết quả trực tiếp lên view này
 *
 * React Native chỉ cần hiển thị view này và gọi các setter để điều chỉnh params.
 */
@interface GPUPixelCameraView : UIView

/** Khởi tạo pipeline GPUPixel và bắt đầu capture. */
- (void)startCamera;

/** Dừng camera và giải phóng tài nguyên. */
- (void)stopCamera;

/** Độ mịn da: 0.0 – 10.0 */
- (void)setSmoothing:(float)value;

/** Độ trắng da: 0.0 – 10.0 */
- (void)setWhitening:(float)value;

/** Gọn mặt: 0.0 – 10.0 */
- (void)setFaceSlim:(float)value;

/** To mắt: 0.0 – 10.0 */
- (void)setEyeEnlarge:(float)value;

/** Má hồng: 0.0 – 10.0 */
- (void)setBlusher:(float)value;

@end
