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
  smoothing: number;  // 0 – 10
  whitening: number;  // 0 – 10
}

export interface ReshapeParams {
  faceSlim: number;   // 0 – 10
  eyeEnlarge: number; // 0 – 10
}

export interface MakeupParams {
  blusher: number;    // 0 – 10
}

export interface GPUPixelCameraRef {
  startCamera: () => void;
  stopCamera: () => void;
  setBeautyParams: (params: BeautyParams) => void;
  setReshapeParams: (params: ReshapeParams) => void;
  setMakeupParams: (params: MakeupParams) => void;
}

export interface GPUPixelCameraViewProps {
  style?: ViewStyle;
  // Props trực tiếp qua JSX (tùy chọn — thay thế cho method calls)
  smoothing?: number;
  whitening?: number;
  faceSlim?: number;
  eyeEnlarge?: number;
  blusher?: number;
}

// ---------------------------------------------------------------------------
// Native bindings
// ---------------------------------------------------------------------------

const { GPUPixelModule: NativeGPUPixelModule } = NativeModules;

// Native view component đã đăng ký trong GPUPixelViewManager.mm
const NativeGPUPixelCameraView = requireNativeComponent<GPUPixelCameraViewProps>(
  'GPUPixelCameraView'
);

// ---------------------------------------------------------------------------
// React component
// ---------------------------------------------------------------------------

/**
 * GPUPixelCameraView
 *
 * Component hiển thị camera với beauty filter real-time.
 *
 * Cách dùng:
 * ```tsx
 * const cameraRef = useRef<GPUPixelCameraRef>(null);
 *
 * useEffect(() => {
 *   cameraRef.current?.startCamera();
 *   return () => cameraRef.current?.stopCamera();
 * }, []);
 *
 * // Điều chỉnh filter
 * cameraRef.current?.setBeautyParams({ smoothing: 7, whitening: 5 });
 *
 * return <GPUPixelCameraView ref={cameraRef} style={StyleSheet.absoluteFill} />;
 * ```
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

  return React.createElement(NativeGPUPixelCameraView, { ...props, ref: nativeRef });
});

GPUPixelCameraView.displayName = 'GPUPixelCameraView';
