package com.aibeauty.beautyfilter;

import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Thin Java wrapper over the native beauty filter pipeline.
 *
 * <p>The native methods are implemented in {@code src/android/jni/jni_bridge.cc}
 * and compiled into {@code libbeautyfilter.so}. The pipeline and parameter
 * scaling mirror the iOS / WASM demos so behaviour is identical across
 * platforms.
 */
public final class BeautyFilterNative {

    static {
        // mars-face-kit is a NEEDED dependency of libbeautyfilter.so when the
        // library is built with face detection ON. Preloading it first makes
        // symbol resolution robust across devices; ignore if absent (the
        // face-detection-OFF build has no such dependency).
        try {
            System.loadLibrary("mars-face-kit");
        } catch (UnsatisfiedLinkError ignored) {
            // Built without face detection — fine.
        }
        System.loadLibrary("beautyfilter");
    }

    private BeautyFilterNative() {
    }

    // ---- Native entry points (symbols resolved by name) ----

    private static native int nativeInit(String resourceRoot);

    private static native void nativeSetBeautyParams(float smoothing, float whitening);

    private static native void nativeSetReshapeParams(float faceSlim, float eyeEnlarge);

    private static native void nativeSetMakeupParams(float blusher);

    private static native void nativeDetectFace(ByteBuffer smallRgba, int width, int height);

    private static native boolean nativeProcessInto(
            ByteBuffer inRgba, int width, int height, ByteBuffer outRgba);

    private static native boolean nativeSetOutputSurface(Surface surface, int width, int height);

    private static native void nativeResizeOutputSurface(int width, int height);

    private static native void nativeClearOutputSurface();

    private static native boolean nativeProcessPreview(
            ByteBuffer inRgba, int width, int height, int rotationDegrees, boolean mirror);

    private static native float[] nativeGetPerfStats();

    private static native void nativeDestroy();

    // ---- Friendly API ----

    /**
     * Initialise the pipeline once. {@code resourceRoot} must be a directory
     * that contains a {@code res/} subfolder with the filter textures.
     *
     * @return 0 on success, 1 if already initialised, negative on failure.
     */
    public static int init(String resourceRoot) {
        return nativeInit(resourceRoot);
    }

    /** smoothing/whitening on a 0–10 scale (matches iOS/WASM). */
    public static void setBeautyParams(float smoothing, float whitening) {
        nativeSetBeautyParams(smoothing, whitening);
    }

    /** Landmark-driven; effective once face detection is wired in. */
    public static void setReshapeParams(float faceSlim, float eyeEnlarge) {
        nativeSetReshapeParams(faceSlim, eyeEnlarge);
    }

    /** Landmark-driven; effective once face detection is wired in. */
    public static void setMakeupParams(float blusher) {
        nativeSetMakeupParams(blusher);
    }

    /**
     * Detect a face and update landmarks. Cheap to throttle: pass a downscaled
     * frame every Nth frame; landmarks are normalized so they still apply to the
     * full-resolution frame in {@link #processInto}.
     *
     * @param smallRgba direct ByteBuffer of RGBA bytes (length >= width*height*4)
     */
    public static void detectFace(ByteBuffer smallRgba, int width, int height) {
        nativeDetectFace(smallRgba, width, height);
    }

    /**
     * Run one RGBA frame through the pipeline, writing the filtered result into
     * {@code outRgba}. Both buffers must be {@code allocateDirect} of size
     * width*height*4; nothing is allocated per call.
     *
     * @return true on success.
     */
    public static boolean processInto(
            ByteBuffer inRgba, int width, int height, ByteBuffer outRgba) {
        return nativeProcessInto(inRgba, width, height, outRgba);
    }

    /** Attach the native preview renderer to an Android surface. */
    public static boolean setOutputSurface(Surface surface, int width, int height) {
        return nativeSetOutputSurface(surface, width, height);
    }

    /** Update the native preview viewport after a surface size change. */
    public static void resizeOutputSurface(int width, int height) {
        nativeResizeOutputSurface(width, height);
    }

    /** Detach and destroy the native preview surface. */
    public static void clearOutputSurface() {
        nativeClearOutputSurface();
    }

    /** Render one RGBA frame directly to the attached output surface. */
    public static boolean processPreview(
            ByteBuffer inRgba, int width, int height, int rotationDegrees, boolean mirror) {
        return nativeProcessPreview(inRgba, width, height, rotationDegrees, mirror);
    }

    /**
     * Native performance stats:
     * [0] preview path ms, [1] readback path ms, [2] surface width, [3] surface height.
     */
    public static float[] getPerfStats() {
        return nativeGetPerfStats();
    }

    /** Release the pipeline and GL resources. */
    public static void destroy() {
        nativeDestroy();
    }
}
