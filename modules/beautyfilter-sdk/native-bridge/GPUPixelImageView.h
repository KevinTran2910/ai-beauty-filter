#pragma once

#import <UIKit/UIKit.h>

/**
 * GPUPixelImageView
 *
 * Một UIView nhận một ảnh tĩnh (từ thư viện ảnh), chạy qua pipeline beauty
 * filter của GPUPixel và render kết quả trực tiếp lên view này.
 *
 * Khác với GPUPixelCameraView (real-time camera), view này xử lý ảnh tĩnh:
 *   - Mỗi khi `imageUri` đổi → decode ảnh → RGBA → đẩy 1 frame vào pipeline.
 *   - Mỗi khi tham số (smoothing/whitening/...) đổi → áp dụng rồi render lại
 *     đúng frame đó (SinkView re-render trên mỗi ProcessData).
 *
 * Tất cả props được set từ JSX và áp dụng qua các setter dưới đây.
 */
@interface GPUPixelImageView : UIView

/** Đường dẫn ảnh nguồn (file:// hoặc path tuyệt đối). */
- (void)setImageUri:(NSString*)uri;

/** Độ mịn da: 0.0 – 10.0 */
- (void)setSmoothing:(float)value;

/** Độ trắng da: 0.0 – 10.0 */
- (void)setWhitening:(float)value;

/** Gọn mặt: 0.0 – 10.0 (cần face detector) */
- (void)setFaceSlim:(float)value;

/** To mắt: 0.0 – 10.0 (cần face detector) */
- (void)setEyeEnlarge:(float)value;

/** Má hồng: 0.0 – 10.0 (cần face detector) */
- (void)setBlusher:(float)value;

@end
