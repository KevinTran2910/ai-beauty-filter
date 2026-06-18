package com.aibeauty.beautyfilter;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Range;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live beauty-filter demo.
 *
 * <p>CameraX delivers RGBA frames to {@link #analyze}, which rotates them upright
 * (and mirrors the selfie camera), runs them through the native pipeline
 * ({@link BeautyFilterNative#processInto}) and paints the filtered result onto a
 * {@link SurfaceView}. Face detection ({@link BeautyFilterNative#detectFace}) is
 * the heaviest step, so it runs on a downscaled frame every {@code DETECT_EVERY}
 * frames; landmarks persist between detections. The five sliders drive smoothing
 * / whitening
 * ({@code BeautyFaceFilter}), face-slim / eye-enlarge ({@code FaceReshapeFilter})
 * and blusher ({@code BlusherFilter}); reshape and blusher are landmark-driven, so
 * they only show on detected faces. A picked gallery image can be used instead of
 * the camera, and the current frame can be captured to the gallery.
 *
 * <p>All native access (init, per-frame processing, parameter setters, destroy) is
 * serialized on a single-thread executor, matching the native GL worker.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BeautyFilterDemo";
    private static final int MAX_PICKED_DIM = 1280;

    private SurfaceView texturePreview;
    private SurfaceHolder previewHolder;
    private TextView lblStatus;
    private TextView lblPerf;
    private TextView lblSmoothing;
    private TextView lblWhitening;
    private TextView lblSlim;
    private TextView lblEye;
    private TextView lblBlush;
    private SeekBar seekSmoothing;
    private SeekBar seekWhitening;
    private SeekBar seekSlim;
    private SeekBar seekEye;
    private SeekBar seekBlush;

    // Native GL work is single-threaded; this executor serializes init, every
    // frame, parameter pushes and teardown so the native side is touched from
    // exactly one thread.
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean initialized = false;
    private volatile boolean imageMode = false;
    private volatile boolean captureRequested = false;
    private volatile boolean previewSurfaceReady = false;

    private boolean cameraPermissionGranted = false;
    private boolean frontCamera = true;
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;

    private ActivityResultLauncher<String> pickImage;
    private ActivityResultLauncher<String> requestPermission;

    // --- cameraExecutor-only state (no synchronization needed) ---
    private static final int DETECT_EVERY = 2;     // run face detection every Nth frame
    private static final int DETECT_MAX_DIM = 320; // downscaled longest side for detection

    private Bitmap currentImage;       // source for image mode
    private long frameCount;
    private final PerfStats perfStats = new PerfStats();

    // Native I/O — direct buffers so the JNI reads/writes without copies or allocs.
    // Used by the image-mode (RGBA) path and the camera capture (YUV readback) path.
    private ByteBuffer inBuffer;
    private ByteBuffer outBuffer;
    private Bitmap resultBitmap;

    // Detection downscale — reused.
    private Bitmap detectSrcBitmap;    // grayscale camera-orientation downscale (from Y plane)
    private int[] detectSrcPixels;
    private Bitmap detectBitmap;       // upright (+mirrored) detection input
    private Canvas detectCanvas;
    private ByteBuffer detectBuffer;
    private final Matrix detectMatrix = new Matrix();

    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        texturePreview = findViewById(R.id.texturePreview);
        previewHolder = texturePreview.getHolder();
        previewHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                previewSurfaceReady = true;
                cameraExecutor.execute(() -> attachPreviewSurface(holder));
            }

            @Override
            public void surfaceChanged(
                    @NonNull SurfaceHolder holder, int format, int width, int height) {
                previewSurfaceReady = true;
                perfStats.setViewSize(width, height);
                cameraExecutor.execute(() -> {
                    if (!initialized) {
                        return;
                    }
                    BeautyFilterNative.resizeOutputSurface(width, height);
                    attachPreviewSurface(holder);
                });
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                previewSurfaceReady = false;
                cameraExecutor.execute(() -> {
                    if (initialized) {
                        BeautyFilterNative.clearOutputSurface();
                    }
                });
            }
        });
        lblStatus = findViewById(R.id.lblStatus);
        lblPerf = findViewById(R.id.lblPerf);
        lblSmoothing = findViewById(R.id.lblSmoothing);
        lblWhitening = findViewById(R.id.lblWhitening);
        lblSlim = findViewById(R.id.lblSlim);
        lblEye = findViewById(R.id.lblEye);
        lblBlush = findViewById(R.id.lblBlush);
        seekSmoothing = findViewById(R.id.seekSmoothing);
        seekWhitening = findViewById(R.id.seekWhitening);
        seekSlim = findViewById(R.id.seekSlim);
        seekEye = findViewById(R.id.seekEye);
        seekBlush = findViewById(R.id.seekBlush);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateSliderLabels();
                pushParams();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        };
        seekSmoothing.setOnSeekBarChangeListener(listener);
        seekWhitening.setOnSeekBarChangeListener(listener);
        seekSlim.setOnSeekBarChangeListener(listener);
        seekEye.setOnSeekBarChangeListener(listener);
        seekBlush.setOnSeekBarChangeListener(listener);
        updateSliderLabels();

        ((Button) findViewById(R.id.btnSwitch)).setOnClickListener(v -> switchCamera());
        ((Button) findViewById(R.id.btnCapture)).setOnClickListener(v -> {
            captureRequested = true;
            Toast.makeText(this, "Đang chụp...", Toast.LENGTH_SHORT).show();
            // Camera mode captures on the next frame; image mode has no frame
            // stream, so render the current image again to honour the request.
            cameraExecutor.execute(() -> {
                if (imageMode) {
                    reprocessImage();
                }
            });
        });
        ((Button) findViewById(R.id.btnCamera)).setOnClickListener(v -> resumeCamera());

        pickImage = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImagePicked);
        ((Button) findViewById(R.id.btnPick)).setOnClickListener(v -> pickImage.launch("image/*"));

        requestPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    cameraPermissionGranted = granted;
                    if (granted) {
                        maybeStartCamera();
                    } else {
                        lblStatus.setText("Cần quyền Camera — bạn vẫn có thể bấm \"Chọn ảnh\".");
                    }
                });

        // Initialise the native pipeline off the UI thread.
        cameraExecutor.execute(this::initNative);

        // Ask for the camera once; the analyzer guards against frames arriving
        // before native init completes.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraPermissionGranted = true;
            maybeStartCamera();
        } else {
            requestPermission.launch(Manifest.permission.CAMERA);
        }
    }

    // ----------------------------------------------------------------- native

    /** Copies assets and initialises the native pipeline. Runs on cameraExecutor. */
    private void initNative() {
        try {
            copyAssetDir("res", new File(getFilesDir(), "res"));
            if (hasAsset("models")) {
                copyAssetDir("models", new File(getFilesDir(), "models"));
            }
            int rc = BeautyFilterNative.init(getFilesDir().getAbsolutePath());
            initialized = rc >= 0;
            Log.i(TAG, "BeautyFilterNative.init returned " + rc);
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            initialized = false;
        }
        runOnUiThread(() -> {
            if (initialized) {
                lblStatus.setText("Sẵn sàng");
                pushParams(); // apply the slider defaults to the native pipeline
                cameraExecutor.execute(() -> attachPreviewSurface(previewHolder));
            } else {
                lblStatus.setText("Khởi tạo native THẤT BẠI (xem logcat)");
            }
        });
    }

    private float[] currentParams() {
        return new float[]{
                seekSmoothing.getProgress(), seekWhitening.getProgress(),
                seekSlim.getProgress(), seekEye.getProgress(), seekBlush.getProgress()};
    }

    /** Reads slider values on the UI thread, pushes them on cameraExecutor. */
    private void pushParams() {
        final float[] p = currentParams();
        cameraExecutor.execute(() -> {
            if (!initialized) {
                return;
            }
            applyParams(p);
            if (imageMode) {
                reprocessImage();
            }
        });
    }

    private void applyParams(float[] p) {
        BeautyFilterNative.setBeautyParams(p[0], p[1]);
        BeautyFilterNative.setReshapeParams(p[2], p[3]);
        BeautyFilterNative.setMakeupParams(p[4]);
    }

    private void attachPreviewSurface(SurfaceHolder holder) {
        if (!initialized || holder == null || holder.getSurface() == null
                || !holder.getSurface().isValid()) {
            return;
        }
        int width = texturePreview.getWidth();
        int height = texturePreview.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        perfStats.setViewSize(width, height);
        BeautyFilterNative.setOutputSurface(holder.getSurface(), width, height);
    }

    // ----------------------------------------------------------------- camera

    private void maybeStartCamera() {
        if (!cameraPermissionGranted || imageMode) {
            return;
        }
        if (cameraProvider != null) {
            bindCamera();
            return;
        }
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "camera provider failed", e);
                lblStatus.setText("Không mở được camera (xem logcat).");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** Must run on the main thread (CameraX requirement). */
    @ExperimentalCamera2Interop
    private void bindCamera() {
        if (cameraProvider == null || imageMode) {
            return;
        }
        // FPS test: 720p instead of 1080p. CameraX's internal YUV->RGBA conversion
        // (OUTPUT_IMAGE_FORMAT_RGBA_8888) is a per-frame cost that scales with pixel
        // count and often caps ImageAnalysis delivery at 1080p. Halving the pixels is
        // a quick check for a throughput-bound delivery rate. Revert to 1920x1080 if
        // the preview sharpness loss isn't worth the FPS gain.
        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();

        // YUV_420_888 is the camera's native format: no internal YUV->RGBA
        // conversion (that conversion was the per-frame delivery bottleneck). The
        // planes are uploaded straight to the GPU, which does YUV->RGB in-shader.
        ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution);

        Range<Integer> targetFps = selectTargetFpsRange();
        perfStats.setTargetFps(targetFps);
        // Prefer 60 fps when the selected camera advertises it.
        new Camera2Interop.Extender<>(analysisBuilder)
                .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps);

        ImageAnalysis analysis = analysisBuilder.build();
        analysis.setAnalyzer(cameraExecutor, this::analyze);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, analysis);
        } catch (Exception e) {
            Log.e(TAG, "bindToLifecycle failed", e);
            lblStatus.setText("Không gắn được camera (thiết bị thiếu cam?).");
        }
    }

    private Range<Integer> selectTargetFpsRange() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return new Range<>(30, 30);
        }
        int desiredLens = frontCamera
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
                Integer lens = c.get(CameraCharacteristics.LENS_FACING);
                if (lens == null || lens != desiredLens) {
                    continue;
                }
                Range<Integer>[] ranges =
                        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                Log.i(TAG, "AE target FPS ranges (" + (frontCamera ? "front" : "back")
                        + " cam " + cameraId + "): " + Arrays.toString(ranges));
                Range<Integer> selected = chooseBestFpsRange(ranges);
                if (selected != null) {
                    Log.i(TAG, "Selected AE target FPS range: " + selected);
                    return selected;
                }
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Could not query camera FPS ranges", e);
        }
        return new Range<>(30, 30);
    }

    /**
     * Picks the best advertised AE FPS range for a smooth, high preview rate:
     * the highest upper bound (best achievable rate), tie-broken by the highest
     * lower bound. A high floor stops auto-exposure from dropping the frame rate
     * in dim light — the trade-off is a darker image when light is low. Returns
     * null if the camera advertises no ranges.
     */
    private static Range<Integer> chooseBestFpsRange(Range<Integer>[] ranges) {
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (best == null
                    || range.getUpper() > best.getUpper()
                    || (range.getUpper().equals(best.getUpper())
                            && range.getLower() > best.getLower())) {
                best = range;
            }
        }
        return best;
    }

    private void switchCamera() {
        frontCamera = !frontCamera;
        cameraSelector = frontCamera
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;
        if (!imageMode) {
            bindCamera();
        }
    }

    private void resumeCamera() {
        imageMode = false;
        cameraExecutor.execute(() -> {
            currentImage = null;
            attachPreviewSurface(previewHolder);
        });
        maybeStartCamera();
        lblStatus.setText("Camera");
    }

    /** CameraX analyzer callback. Runs on cameraExecutor. */
    private void analyze(@NonNull ImageProxy image) {
        long frameStartNs = System.nanoTime();
        float detectMs = 0f;
        try {
            if (imageMode) {
                return;
            }
            int camW = image.getWidth();
            int camH = image.getHeight();
            perfStats.setCameraSize(camW, camH);
            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            boolean swap = (rotationDegrees == 90 || rotationDegrees == 270);
            int upW = swap ? camH : camW;   // upright (post-rotation) dimensions
            int upH = swap ? camW : camH;
            perfStats.setProcessedSize(upW, upH);

            if (initialized) {
                int rotationMode = rotationModeFor(rotationDegrees, frontCamera);
                boolean doDetect = (frameCount % DETECT_EVERY == 0);
                if (doDetect) {
                    detectMs = runDetectYuv(image, rotationDegrees, frontCamera);
                }
                if (captureRequested) {
                    Bitmap filtered = processCaptureYuv(image, rotationMode, upW, upH);
                    if (filtered != null) {
                        deliver(filtered);
                    }
                } else {
                    processPreviewYuv(image, rotationMode);
                }
                perfStats.pullNativeStats();
            }
            frameCount++;
            // Upright time is now 0 — rotation/conversion moved off the CPU to the GPU.
            perfStats.setTimings(elapsedMs(frameStartNs), 0f, detectMs);
            perfStats.onFrameComplete();
        } catch (Throwable t) {
            Log.e(TAG, "analyze failed", t);
        } finally {
            image.close();
        }
    }

    // gpupixel RotationMode ordinals (see include/gpupixel/sink/sink.h). Rotation
    // is applied on the GPU; the selfie mirror is folded in here too. THIS TABLE
    // is the single place to adjust if the live preview shows up rotated or
    // mirror-flipped wrong — it's a Java-only change (no NDK rebuild needed).
    private static final int ROT_NONE = 0;          // NoRotation
    private static final int ROT_LEFT = 1;          // RotateLeft
    private static final int ROT_RIGHT = 2;         // RotateRight
    private static final int ROT_FLIP_V = 3;        // FlipVertical
    private static final int ROT_FLIP_H = 4;        // FlipHorizontal
    private static final int ROT_RIGHT_FLIP_V = 5;  // RotateRightFlipVertical
    private static final int ROT_RIGHT_FLIP_H = 6;  // RotateRightFlipHorizontal
    private static final int ROT_180 = 7;           // Rotate180

    private static int rotationModeFor(int rotationDegrees, boolean mirror) {
        // NOTE: includes a +180° correction over the naive mapping — the GPU
        // sampling convention is rotated 180° from the camera's reported degrees,
        // so each entry is its 180°-composed equivalent.
        if (!mirror) {
            switch (rotationDegrees) {
                case 90:  return ROT_LEFT;
                case 180: return ROT_NONE;
                case 270: return ROT_RIGHT;
                default:  return ROT_180;   // 0°
            }
        }
        switch (rotationDegrees) {
            case 90:  return ROT_RIGHT_FLIP_V;
            case 180: return ROT_FLIP_H;
            case 270: return ROT_RIGHT_FLIP_H;
            default:  return ROT_FLIP_V;    // 0°
        }
    }

    /** Uploads the camera YUV planes and renders directly to the surface. */
    private void processPreviewYuv(ImageProxy image, int rotationMode) {
        if (!previewSurfaceReady) {
            return;
        }
        ImageProxy.PlaneProxy[] p = image.getPlanes();
        BeautyFilterNative.processPreviewYuv(
                p[0].getBuffer(), p[1].getBuffer(), p[2].getBuffer(),
                image.getWidth(), image.getHeight(),
                p[0].getRowStride(), p[1].getRowStride(), p[2].getRowStride(),
                p[1].getPixelStride(), rotationMode);
    }

    /** Capture: render off-screen, read the upright RGBA result into resultBitmap. */
    private Bitmap processCaptureYuv(ImageProxy image, int rotationMode, int upW, int upH) {
        ensureProcessBuffers(upW, upH);
        ImageProxy.PlaneProxy[] p = image.getPlanes();
        outBuffer.rewind();
        if (!BeautyFilterNative.processIntoYuv(
                p[0].getBuffer(), p[1].getBuffer(), p[2].getBuffer(),
                image.getWidth(), image.getHeight(),
                p[0].getRowStride(), p[1].getRowStride(), p[2].getRowStride(),
                p[1].getPixelStride(), rotationMode, outBuffer)) {
            Log.e(TAG, "processIntoYuv failed");
            return null;
        }
        outBuffer.rewind();
        resultBitmap.copyPixelsFromBuffer(outBuffer);
        return resultBitmap;
    }

    /**
     * Face detection on a small, upright (selfie-mirrored) grayscale image built
     * from the camera Y (luma) plane. Mirrors the orientation the GPU produces so
     * the normalized landmarks line up with the rendered frame. The rotation here
     * uses Android's Matrix (the same math the app used previously), so it is
     * independent of the GPU RotationMode table.
     */
    private float runDetectYuv(ImageProxy image, int rotationDegrees, boolean mirror) {
        long detectStartNs = System.nanoTime();
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer yBuf = yPlane.getBuffer();
        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int camW = image.getWidth();
        int camH = image.getHeight();

        int longest = Math.max(camW, camH);
        float scale = longest > DETECT_MAX_DIM ? (float) DETECT_MAX_DIM / longest : 1f;
        int cdw = Math.max(1, Math.round(camW * scale));
        int cdh = Math.max(1, Math.round(camH * scale));
        ensureDetectSrc(cdw, cdh);

        // Downscale the Y plane into a gray ARGB buffer (camera orientation).
        for (int j = 0; j < cdh; j++) {
            int sy = Math.min(camH - 1, Math.round(j / scale));
            int rowBase = sy * yRowStride;
            int outBase = j * cdw;
            for (int i = 0; i < cdw; i++) {
                int sx = Math.min(camW - 1, Math.round(i / scale));
                int yv = yBuf.get(rowBase + sx * yPixelStride) & 0xFF;
                detectSrcPixels[outBase + i] = 0xFF000000 | (yv << 16) | (yv << 8) | yv;
            }
        }
        detectSrcBitmap.setPixels(detectSrcPixels, 0, cdw, 0, 0, cdw, cdh);

        boolean swap = (rotationDegrees == 90 || rotationDegrees == 270);
        int dw = swap ? cdh : cdw;
        int dh = swap ? cdw : cdh;
        ensureDetectBuffers(dw, dh);

        detectMatrix.reset();
        detectMatrix.postRotate(rotationDegrees);
        switch (rotationDegrees) {
            case 90:  detectMatrix.postTranslate(cdh, 0); break;
            case 180: detectMatrix.postTranslate(cdw, cdh); break;
            case 270: detectMatrix.postTranslate(0, cdw); break;
            default:  break;
        }
        if (mirror) {
            detectMatrix.postScale(-1f, 1f);
            detectMatrix.postTranslate(dw, 0);
        }
        detectCanvas.drawColor(Color.BLACK);
        detectCanvas.drawBitmap(detectSrcBitmap, detectMatrix, drawPaint);

        detectBuffer.rewind();
        detectBitmap.copyPixelsToBuffer(detectBuffer);
        BeautyFilterNative.detectFace(detectBuffer, dw, dh);
        return elapsedMs(detectStartNs);
    }

    private static final class FrameResult {
        final Bitmap bitmap;
        final float detectMs;

        FrameResult(Bitmap bitmap, float detectMs) {
            this.bitmap = bitmap;
            this.detectMs = detectMs;
        }
    }

    /** Detect (optional) then run the pipeline; returns the reused result bitmap. */
    private FrameResult processFrame(Bitmap upright, boolean doDetect) {
        int w = upright.getWidth();
        int h = upright.getHeight();
        ensureProcessBuffers(w, h);

        float detectMs = 0f;
        if (doDetect) {
            long detectStartNs = System.nanoTime();
            runDetect(upright);
            detectMs = elapsedMs(detectStartNs);
        }

        inBuffer.rewind();
        upright.copyPixelsToBuffer(inBuffer);
        outBuffer.rewind();
        if (!BeautyFilterNative.processInto(inBuffer, w, h, outBuffer)) {
            Log.e(TAG, "processInto failed");
            return new FrameResult(null, detectMs);
        }
        outBuffer.rewind();
        resultBitmap.copyPixelsFromBuffer(outBuffer);
        perfStats.pullNativeStats();
        return new FrameResult(resultBitmap, detectMs);
    }

    /** Runs face detection on a downscaled copy of {@code upright}. */
    private void runDetect(Bitmap upright) {
        int w = upright.getWidth();
        int h = upright.getHeight();
        int longest = Math.max(w, h);
        float scale = longest > DETECT_MAX_DIM ? (float) DETECT_MAX_DIM / longest : 1f;
        int dw = Math.max(1, Math.round(w * scale));
        int dh = Math.max(1, Math.round(h * scale));
        ensureDetectBuffers(dw, dh);

        detectMatrix.reset();
        detectMatrix.postScale((float) dw / w, (float) dh / h);
        detectCanvas.drawBitmap(upright, detectMatrix, drawPaint);

        detectBuffer.rewind();
        detectBitmap.copyPixelsToBuffer(detectBuffer);
        BeautyFilterNative.detectFace(detectBuffer, dw, dh);
    }

    private void ensureProcessBuffers(int w, int h) {
        int n = w * h * 4;
        if (inBuffer == null || inBuffer.capacity() != n) {
            inBuffer = ByteBuffer.allocateDirect(n);
            outBuffer = ByteBuffer.allocateDirect(n);
        }
        if (resultBitmap == null || resultBitmap.getWidth() != w
                || resultBitmap.getHeight() != h) {
            resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
    }

    private void ensureDetectBuffers(int dw, int dh) {
        if (detectBitmap == null || detectBitmap.getWidth() != dw
                || detectBitmap.getHeight() != dh) {
            detectBitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            detectCanvas = new Canvas(detectBitmap);
            detectBuffer = ByteBuffer.allocateDirect(dw * dh * 4);
        }
    }

    /** Reused source bitmap/pixel array for the grayscale detection downscale. */
    private void ensureDetectSrc(int cdw, int cdh) {
        if (detectSrcBitmap == null || detectSrcBitmap.getWidth() != cdw
                || detectSrcBitmap.getHeight() != cdh) {
            detectSrcBitmap = Bitmap.createBitmap(cdw, cdh, Bitmap.Config.ARGB_8888);
            detectSrcPixels = new int[cdw * cdh];
        }
    }

    /** Handles a capture request and paints still-image fallback output. */
    private void deliver(Bitmap bmp) {
        if (captureRequested) {
            captureRequested = false;
            saveToGallery(bmp.copy(Bitmap.Config.ARGB_8888, false));
            if (!imageMode) {
                return;
            }
        }
        drawToTexture(bmp);
    }

    private void drawToTexture(Bitmap bmp) {
        if (!previewSurfaceReady || previewHolder == null) {
            return;
        }
        Canvas canvas = previewHolder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(Color.BLACK);
            int vw = texturePreview.getWidth();
            int vh = texturePreview.getHeight();
            // Center-crop the frame to fill the view.
            float scale = Math.max((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
            float dx = (vw - bmp.getWidth() * scale) / 2f;
            float dy = (vh - bmp.getHeight() * scale) / 2f;
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate(dx, dy);
            canvas.drawBitmap(bmp, m, drawPaint);
        } finally {
            previewHolder.unlockCanvasAndPost(canvas);
        }
    }

    // ------------------------------------------------------------ image mode

    private void onImagePicked(Uri uri) {
        if (uri == null) {
            return;
        }
        imageMode = true;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        lblStatus.setText("Ảnh đã chọn");
        cameraExecutor.execute(() -> {
            if (initialized) {
                BeautyFilterNative.clearOutputSurface();
            }
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap decoded = BitmapFactory.decodeStream(in);
                if (decoded == null) {
                    return;
                }
                if (decoded.getConfig() != Bitmap.Config.ARGB_8888) {
                    decoded = decoded.copy(Bitmap.Config.ARGB_8888, false);
                }
                currentImage = scaleDown(decoded, MAX_PICKED_DIM);
                reprocessImage();
            } catch (Exception e) {
                Log.e(TAG, "decode picked image failed", e);
            }
        });
    }

    /** Re-runs the current still image through the pipeline. Runs on cameraExecutor. */
    private void reprocessImage() {
        if (!initialized || currentImage == null) {
            return;
        }
        long frameStartNs = System.nanoTime();
        perfStats.setCameraSize(currentImage.getWidth(), currentImage.getHeight());
        perfStats.setProcessedSize(currentImage.getWidth(), currentImage.getHeight());
        FrameResult result = processFrame(currentImage, true);
        perfStats.setTimings(elapsedMs(frameStartNs), 0f, result.detectMs);
        perfStats.onFrameComplete();
        if (result.bitmap != null) {
            deliver(result.bitmap);
        }
    }

    private static Bitmap scaleDown(Bitmap src, int maxDim) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= maxDim) {
            return src.getConfig() == Bitmap.Config.ARGB_8888
                    ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        }
        float scale = (float) maxDim / longest;
        Bitmap scaled = Bitmap.createScaledBitmap(
                src, Math.round(src.getWidth() * scale), Math.round(src.getHeight() * scale), true);
        return scaled.getConfig() == Bitmap.Config.ARGB_8888
                ? scaled : scaled.copy(Bitmap.Config.ARGB_8888, false);
    }

    // -------------------------------------------------------------- capture

    /** Writes a JPEG to the gallery (API 29+) or app external storage. */
    private void saveToGallery(Bitmap bmp) {
        String name = "beauty_" + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/BeautyFilter");
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new Exception("MediaStore insert returned null");
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }
                toast("Đã lưu vào Thư viện");
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                File file = new File(dir, name);
                try (OutputStream os = new FileOutputStream(file)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }
                toast("Đã lưu: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
            toast("Lưu ảnh thất bại");
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ----------------------------------------------------------------- misc

    private static float elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000f;
    }

    private final class PerfStats {
        private static final long UI_UPDATE_NS = 500_000_000L;

        private long windowStartNs = System.nanoTime();
        private long lastUiUpdateNs = windowStartNs;
        private long lastCpuMs = android.os.Process.getElapsedCpuTime();
        private int completedFrames;
        private float fps;
        private float cpuPercent;

        private int cameraWidth;
        private int cameraHeight;
        private int processedWidth;
        private int processedHeight;
        private int viewWidth;
        private int viewHeight;
        private String targetFps = "n/a";

        private float frameMs;
        private float uprightMs;
        private float detectMs;
        private float nativePreviewMs;
        private float readbackMs;
        private float intervalMs;   // avg wall time between consecutive frames

        void setTargetFps(Range<Integer> range) {
            targetFps = range == null ? "n/a" : range.getLower() + "-" + range.getUpper();
        }

        void setCameraSize(int width, int height) {
            cameraWidth = width;
            cameraHeight = height;
        }

        void setProcessedSize(int width, int height) {
            processedWidth = width;
            processedHeight = height;
        }

        void setViewSize(int width, int height) {
            viewWidth = width;
            viewHeight = height;
        }

        void setTimings(float frame, float upright, float detect) {
            frameMs = frame;
            uprightMs = upright;
            detectMs = detect;
        }

        void pullNativeStats() {
            if (!initialized) {
                return;
            }
            float[] nativeStats = BeautyFilterNative.getPerfStats();
            if (nativeStats == null || nativeStats.length < 4) {
                return;
            }
            nativePreviewMs = nativeStats[0];
            readbackMs = nativeStats[1];
            if (nativeStats[2] > 0 && nativeStats[3] > 0) {
                viewWidth = Math.round(nativeStats[2]);
                viewHeight = Math.round(nativeStats[3]);
            }
        }

        void onFrameComplete() {
            completedFrames++;
            long nowNs = System.nanoTime();
            long elapsedNs = nowNs - windowStartNs;
            if (elapsedNs <= 0 || nowNs - lastUiUpdateNs < UI_UPDATE_NS) {
                return;
            }

            fps = completedFrames * 1_000_000_000f / elapsedNs;
            intervalMs = elapsedNs / 1_000_000f / completedFrames;
            long cpuMs = android.os.Process.getElapsedCpuTime();
            long wallMs = elapsedNs / 1_000_000L;
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            cpuPercent = wallMs > 0 ? ((cpuMs - lastCpuMs) * 100f) / (wallMs * cores) : 0f;
            lastCpuMs = cpuMs;
            windowStartNs = nowNs;
            lastUiUpdateNs = nowNs;
            completedFrames = 0;
            pullNativeStats();

            String text = format();
            runOnUiThread(() -> lblPerf.setText(text));
        }

        private String format() {
            Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memoryInfo);
            int memMb = memoryInfo.getTotalPss() / 1024;
            return String.format(Locale.US,
                    "FPS %.1f / target %s\n"
                            + "Cam %dx%d -> Proc %dx%d | View %dx%d\n"
                            + "CPU app %.0f%% | Mem %d MB\n"
                            + "Interval %.1f ms (idle %.1f) | Frame %.1f ms\n"
                            + "Upright %.1f | Detect %.1f/%df\n"
                            + "Native %.1f ms | Readback %.1f ms",
                    fps, targetFps,
                    cameraWidth, cameraHeight, processedWidth, processedHeight,
                    viewWidth, viewHeight,
                    cpuPercent, memMb,
                    intervalMs, Math.max(0f, intervalMs - frameMs), frameMs,
                    uprightMs, detectMs, DETECT_EVERY,
                    nativePreviewMs, readbackMs);
        }
    }

    private void updateSliderLabels() {
        lblSmoothing.setText("Làm mịn da: " + seekSmoothing.getProgress());
        lblWhitening.setText("Làm trắng: " + seekWhitening.getProgress());
        lblSlim.setText("Thon mặt: " + seekSlim.getProgress());
        lblEye.setText("To mắt: " + seekEye.getProgress());
        lblBlush.setText("Má hồng: " + seekBlush.getProgress());
    }

    private boolean hasAsset(String path) {
        try {
            String[] children = getAssets().list(path);
            return children != null && children.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Recursively copy an assets subdirectory to a real directory. */
    private void copyAssetDir(String assetPath, File outDir) throws Exception {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            if (!outDir.getParentFile().exists()) {
                outDir.getParentFile().mkdirs();
            }
            try (InputStream in = getAssets().open(assetPath);
                 OutputStream os = new FileOutputStream(outDir)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return;
        }
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        for (String child : children) {
            copyAssetDir(assetPath + "/" + child, new File(outDir, child));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.execute(BeautyFilterNative::destroy);
        cameraExecutor.shutdown();
    }
}
