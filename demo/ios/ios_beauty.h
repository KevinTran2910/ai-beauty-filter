#pragma once

#import <UIKit/UIKit.h>
#import <Foundation/Foundation.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Khởi tạo thư viện GPUPixel với UIView để render kết quả.
 * Gọi một lần duy nhất sau khi view đã được add vào hierarchy.
 *
 * @param view       UIView sẽ nhận output sau khi xử lý
 * @param resPath    Đường dẫn tới thư mục resources (chứa model, texture)
 * @return           0 nếu thành công
 */
int GPUPixelIOS_Init(void* view, const char* resPath);

/**
 * Giải phóng tất cả tài nguyên.
 */
void GPUPixelIOS_Destroy(void);

/**
 * Cài đặt thông số làm đẹp da.
 *
 * @param smoothing   Độ mịn (0.0 – 10.0)
 * @param whitening   Độ trắng (0.0 – 10.0)
 */
void GPUPixelIOS_SetBeautyParams(float smoothing, float whitening);

/**
 * Cài đặt thông số chỉnh hình khuôn mặt.
 * Yêu cầu face detector được bật lúc build.
 *
 * @param faceSlim    Độ gọn mặt (0.0 – 10.0)
 * @param eyeEnlarge  Độ to mắt  (0.0 – 10.0)
 */
void GPUPixelIOS_SetReshapeParams(float faceSlim, float eyeEnlarge);

/**
 * Cài đặt thông số makeup.
 *
 * @param blusher     Độ đậm má hồng (0.0 – 10.0)
 */
void GPUPixelIOS_SetMakeupParams(float blusher);

/**
 * Xử lý một frame RGBA từ camera và render ra view.
 *
 * @param rgbaData  Con trỏ tới dữ liệu RGBA (width * height * 4 bytes)
 * @param width     Chiều rộng frame (pixels)
 * @param height    Chiều cao frame  (pixels)
 */
void GPUPixelIOS_ProcessFrame(const uint8_t* rgbaData, int width, int height);

#ifdef __cplusplus
}
#endif
