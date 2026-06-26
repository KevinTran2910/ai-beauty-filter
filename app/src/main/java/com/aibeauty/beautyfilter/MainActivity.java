package com.aibeauty.beautyfilter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

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
 * <p>CameraX delivers YUV frames to {@link #analyze}, which uploads them to the
 * GPU (rotated upright and selfie-mirrored there), runs them through the native
 * pipeline and paints the filtered result onto a {@link SurfaceView}. Face
 * detection ({@link BeautyFilterNative#detectFace}) is the heaviest step, so it
 * runs on a downscaled frame every {@code DETECT_EVERY} frames; landmarks persist
 * between detections. The five sliders drive smoothing / whitening
 * ({@code BeautyFaceFilter}), face-slim / eye-enlarge ({@code FaceReshapeFilter})
 * and blusher ({@code BlusherFilter}); reshape and blusher are landmark-driven, so
 * they only show on detected faces.
 *
 * <p>All native access (init, per-frame processing, parameter setters, destroy) is
 * serialized on a single-thread executor, matching the native GL worker.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BeautyFilterDemo";

    private SurfaceView texturePreview;
    private SurfaceHolder previewHolder;
    private TextView lblStatus;
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
    private volatile boolean previewSurfaceReady = false;

    private boolean cameraPermissionGranted = false;
    private boolean frontCamera = true;
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;

    private ActivityResultLauncher<String> requestPermission;

    // --- cameraExecutor-only state (no synchronization needed) ---
    private static final int DETECT_EVERY = 2;     // run face detection every Nth frame
    private static final int DETECT_MAX_DIM = 320; // downscaled longest side for detection

    private long frameCount;

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

        requestPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    cameraPermissionGranted = granted;
                    if (granted) {
                        maybeStartCamera();
                    } else {
                        showStatus("Need camera privilege to use this app");
                    }
                });

        // Initialize the native pipeline off the UI thread.
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

    /** Copies assets and initializes the native pipeline. Runs on cameraExecutor. */
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
                lblStatus.setText("");
                lblStatus.setVisibility(android.view.View.GONE);
                pushParams(); // apply the slider defaults to the native pipeline
                cameraExecutor.execute(() -> attachPreviewSurface(previewHolder));
            } else {
                showStatus("Native initialization failed (check logcat)");
            }
        });
    }

    private float[] currentParams() {
        // SeekBars run 0..100 (step 0.1 on a 0..10 scale); the native pipeline
        // expects a 0..10 range, so scale down.
        return new float[]{
                seekSmoothing.getProgress() / 10f, seekWhitening.getProgress() / 10f,
                seekSlim.getProgress() / 10f, seekEye.getProgress() / 10f,
                seekBlush.getProgress() / 10f};
    }

    /** Reads slider values on the UI thread, pushes them on cameraExecutor. */
    private void pushParams() {
        final float[] p = currentParams();
        cameraExecutor.execute(() -> {
            if (!initialized) {
                return;
            }
            applyParams(p);
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
        BeautyFilterNative.setOutputSurface(holder.getSurface(), width, height);
    }

    // ----------------------------------------------------------------- camera

    private void maybeStartCamera() {
        if (!cameraPermissionGranted) {
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
                Log.e(TAG, "Camera provider failed", e);
                showStatus("Cannot open camera (check logcat)");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** Must run on the main thread (CameraX requirement). */
    @ExperimentalCamera2Interop
    private void bindCamera() {
        if (cameraProvider == null) {
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
            lblStatus.setText("Cannot attach camera (lack of devices)");
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
     * the highest upper bound, tie-broken by the highest
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
        bindCamera();
    }

    /** CameraX analyzer callback. Runs on cameraExecutor. */
    private void analyze(@NonNull ImageProxy image) {
        try {
            if (initialized) {
                int rotationDegrees = image.getImageInfo().getRotationDegrees();
                int rotationMode = rotationModeFor(rotationDegrees, frontCamera);
                if (frameCount % DETECT_EVERY == 0) {
                    runDetectYuv(image, rotationDegrees, frontCamera);
                }
                processPreviewYuv(image, rotationMode);
            }
            frameCount++;
        } catch (Throwable t) {
            Log.e(TAG, "analyze failed", t);
        } finally {
            image.close();
        }
    }

    // RotationMode ordinals (see include/gpupixel/sink/sink.h). Rotation
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
        // Back camera (no mirror): rotate the camera buffer upright by the
        // reported degrees. The naive mapping is correct here — an earlier +180
        // "correction" left the back-camera preview upside down after switching.
        if (!mirror) {
            switch (rotationDegrees) {
                case 90:  return ROT_RIGHT;
                case 180: return ROT_180;
                case 270: return ROT_LEFT;
                default:  return ROT_NONE;  // 0°
            }
        }
        // Front camera: same rotation plus the selfie mirror. This branch carries
        // a +180° offset relative to the naive mapping to match the GPU sampling
        // convention for the mirrored path.
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

    /**
     * Face detection on a small, upright (selfie-mirrored) grayscale image built
     * from the camera Y plane. Mirrors the orientation the GPU produces so
     * the normalized landmarks line up with the rendered frame. The rotation here
     * uses Android's Matrix (the same math the app used previously), so it is
     * independent of the GPU RotationMode table.
     */
    private void runDetectYuv(ImageProxy image, int rotationDegrees, boolean mirror) {
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

    // ----------------------------------------------------------------- misc

    /** Shows a status/error message on the centred preview label. */
    private void showStatus(String msg) {
        lblStatus.setText(msg);
        lblStatus.setVisibility(android.view.View.VISIBLE);
    }

    private void updateSliderLabels() {
        lblSmoothing.setText(fmtValue(seekSmoothing));
        lblWhitening.setText(fmtValue(seekWhitening));
        lblSlim.setText(fmtValue(seekSlim));
        lblEye.setText(fmtValue(seekEye));
        lblBlush.setText(fmtValue(seekBlush));
    }

    private static String fmtValue(SeekBar sb) {
        return String.format(Locale.US, "%.1f", sb.getProgress() / 10f);
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
