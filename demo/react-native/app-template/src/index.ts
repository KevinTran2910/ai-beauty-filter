import {
  NativeModules,
  requireNativeComponent,
  findNodeHandle,
  ViewStyle,
} from 'react-native';
import React, { useRef, useImperativeHandle, forwardRef } from 'react';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface BeautyParams {
  smoothing: number; // 0 – 10
  whitening: number; // 0 – 10
}

export interface ReshapeParams {
  faceSlim: number; // 0 – 10
  eyeEnlarge: number; // 0 – 10
}

export interface MakeupParams {
  blusher: number; // 0 – 10
}

/** Tham số chung cho cả hai chế độ ảnh tĩnh / camera. */
export interface FilterParamProps {
  smoothing?: number;
  whitening?: number;
  faceSlim?: number;
  eyeEnlarge?: number;
  blusher?: number;
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
// Camera component (real-time) — giữ lại cho trường hợp chạy trên thiết bị thật
// ===========================================================================

export interface GPUPixelCameraRef {
  startCamera: () => void;
  stopCamera: () => void;
  setBeautyParams: (params: BeautyParams) => void;
  setReshapeParams: (params: ReshapeParams) => void;
  setMakeupParams: (params: MakeupParams) => void;
}

export interface GPUPixelCameraViewProps extends FilterParamProps {
  style?: ViewStyle;
}

const { GPUPixelModule: NativeGPUPixelModule } = NativeModules;

const NativeGPUPixelCameraView =
  requireNativeComponent<GPUPixelCameraViewProps>('GPUPixelCameraView');

/**
 * GPUPixelCameraView — camera trước với beauty filter real-time.
 * Gọi startCamera() sau khi mount; stopCamera() khi unmount.
 */
export const GPUPixelCameraView = forwardRef<
  GPUPixelCameraRef,
  GPUPixelCameraViewProps
>((props, ref) => {
  const nativeRef = useRef<any>(null);

  useImperativeHandle(ref, () => ({
    startCamera() {
      const tag = findNodeHandle(nativeRef.current);
      if (tag) NativeGPUPixelModule.startCamera(tag);
    },
    stopCamera() {
      const tag = findNodeHandle(nativeRef.current);
      if (tag) NativeGPUPixelModule.stopCamera(tag);
    },
    setBeautyParams({ smoothing, whitening }: BeautyParams) {
      const tag = findNodeHandle(nativeRef.current);
      if (tag) NativeGPUPixelModule.setBeautyParams(tag, smoothing, whitening);
    },
    setReshapeParams({ faceSlim, eyeEnlarge }: ReshapeParams) {
      const tag = findNodeHandle(nativeRef.current);
      if (tag) NativeGPUPixelModule.setReshapeParams(tag, faceSlim, eyeEnlarge);
    },
    setMakeupParams({ blusher }: MakeupParams) {
      const tag = findNodeHandle(nativeRef.current);
      if (tag) NativeGPUPixelModule.setMakeupParams(tag, blusher);
    },
  }));

  return React.createElement(NativeGPUPixelCameraView, {
    ...props,
    ref: nativeRef,
  });
});

GPUPixelCameraView.displayName = 'GPUPixelCameraView';
