import { requireNativeComponent, ViewStyle } from 'react-native';

// ---------------------------------------------------------------------------
// JS/TS wrapper cho native component của BeautyFilter SDK.
//
// Dùng legacy bridge (requireNativeComponent + RCTViewManager). Trên Expo Dev
// Client / New Architecture, bridge này vẫn chạy qua interop layer — không cần
// đổi gì ở phía native.
// ---------------------------------------------------------------------------

/** Tham số chung cho cả hai chế độ ảnh tĩnh / camera. */
export interface FilterParamProps {
  smoothing?: number; // 0 – 10
  whitening?: number; // 0 – 10
  faceSlim?: number; // 0 – 10
  eyeEnlarge?: number; // 0 – 10
  blusher?: number; // 0 – 10
}

// ===========================================================================
// Static image component (chế độ chính của demo)
// ===========================================================================

export interface GPUPixelImageViewProps extends FilterParamProps {
  style?: ViewStyle;
  /** Đường dẫn ảnh nguồn: file:// hoặc path tuyệt đối. */
  imageUri?: string;
}

/**
 * GPUPixelImageView — render một ảnh tĩnh qua beauty filter.
 * Đổi `imageUri` để nạp ảnh mới; đổi các tham số để render lại tức thì.
 */
export const GPUPixelImageView =
  requireNativeComponent<GPUPixelImageViewProps>('GPUPixelImageView');

// ===========================================================================
// Camera component (real-time) — chỉ chạy đầy đủ trên thiết bị thật
// ===========================================================================

export interface GPUPixelCameraViewProps extends FilterParamProps {
  style?: ViewStyle;
}

/**
 * GPUPixelCameraView — camera trước với beauty filter real-time.
 *
 * Camera tự bật khi view xuất hiện trên màn hình và tự tắt khi biến mất —
 * không cần gọi method nào từ JS. Mọi tham số truyền qua props, giống hệt
 * GPUPixelImageView (đây là cách hoạt động ổn định trên New Architecture).
 */
export const GPUPixelCameraView =
  requireNativeComponent<GPUPixelCameraViewProps>('GPUPixelCameraView');
