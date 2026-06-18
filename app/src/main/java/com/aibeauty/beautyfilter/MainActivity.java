package com.aibeauty.beautyfilter;

import android.Manifest;
import android.content.ContentValues;
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
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live beauty-filter demo.
 *
 * <p>CameraX delivers RGBA frames to {@link #analyze}, which rotates them upright
 * (and mirrors the selfie camera), runs them through the native pipeline
 * ({@link BeautyFilterNative#processInto}) and paints the filtered result onto a
 * {@link TextureView}. Face detection ({@link BeautyFilterNative#detectFace}) is
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

    private TextureView texturePreview;
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
    private volatile boolean imageMode = false;
    private volatile boolean captureRequested = false;

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

    // Frame ingest (camera) — reused across frames, recreated only on size change.
    private Bitmap paddedBitmap;       // receives the CameraX RGBA buffer (may be padded)
    private Bitmap uprightBitmap;      // rotated/mirrored upright frame
    private Canvas uprightCanvas;
    private final Matrix frameMatrix = new Matrix();

    // Native I/O — direct buffers so the JNI reads/writes without copies or allocs.
    private ByteBuffer inBuffer;
    private ByteBuffer outBuffer;
    private Bitmap resultBitmap;

    // Detection downscale — reused.
    private Bitmap detectBitmap;
    private Canvas detectCanvas;
    private ByteBuffer detectBuffer;
    private final Matrix detectMatrix = new Matrix();

    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        texturePreview = findViewById(R.id.texturePreview);
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
    private void bindCamera() {
        if (cameraProvider == null || imageMode) {
            return;
        }
        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyze);

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, analysis);
        } catch (Exception e) {
            Log.e(TAG, "bindToLifecycle failed", e);
            lblStatus.setText("Không gắn được camera (thiết bị thiếu cam?).");
        }
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
        cameraExecutor.execute(() -> currentImage = null);
        maybeStartCamera();
        lblStatus.setText("Camera");
    }

    /** CameraX analyzer callback. Runs on cameraExecutor. */
    private void analyze(@NonNull ImageProxy image) {
        try {
            if (imageMode) {
                return;
            }
            Bitmap upright = buildUpright(image);
            Bitmap toDraw = upright;
            if (initialized) {
                boolean doDetect = (frameCount % DETECT_EVERY == 0);
                Bitmap filtered = processFrame(upright, doDetect);
                if (filtered != null) {
                    toDraw = filtered;
                }
            }
            frameCount++;
            deliver(toDraw);
        } catch (Throwable t) {
            Log.e(TAG, "analyze failed", t);
        } finally {
            image.close();
        }
    }

    /** Draws a CameraX RGBA frame into the reused, upright (selfie-mirrored) bitmap. */
    private Bitmap buildUpright(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int w = image.getWidth();
        int h = image.getHeight();
        int rowPadding = rowStride - pixelStride * w;
        int paddedW = w + rowPadding / pixelStride;

        if (paddedBitmap == null || paddedBitmap.getWidth() != paddedW
                || paddedBitmap.getHeight() != h) {
            paddedBitmap = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888);
        }
        paddedBitmap.copyPixelsFromBuffer(buffer);
        // Padding is 0 on virtually all devices for RGBA; only then allocate a crop.
        Bitmap src = (rowPadding == 0)
                ? paddedBitmap : Bitmap.createBitmap(paddedBitmap, 0, 0, w, h);

        int rotation = image.getImageInfo().getRotationDegrees();
        boolean swap = (rotation == 90 || rotation == 270);
        int uw = swap ? h : w;
        int uh = swap ? w : h;
        ensureUpright(uw, uh);

        // Map the w x h source rect upright, then mirror horizontally for selfie.
        frameMatrix.reset();
        frameMatrix.postRotate(rotation);
        switch (rotation) {
            case 90:  frameMatrix.postTranslate(h, 0); break;
            case 180: frameMatrix.postTranslate(w, h); break;
            case 270: frameMatrix.postTranslate(0, w); break;
            default:  break;
        }
        if (frontCamera) {
            frameMatrix.postScale(-1f, 1f);
            frameMatrix.postTranslate(uw, 0);
        }
        uprightCanvas.drawBitmap(src, frameMatrix, drawPaint);
        return uprightBitmap;
    }

    /** Detect (optional) then run the pipeline; returns the reused result bitmap. */
    private Bitmap processFrame(Bitmap upright, boolean doDetect) {
        int w = upright.getWidth();
        int h = upright.getHeight();
        ensureProcessBuffers(w, h);

        if (doDetect) {
            runDetect(upright);
        }

        inBuffer.rewind();
        upright.copyPixelsToBuffer(inBuffer);
        outBuffer.rewind();
        if (!BeautyFilterNative.processInto(inBuffer, w, h, outBuffer)) {
            Log.e(TAG, "processInto failed");
            return null;
        }
        outBuffer.rewind();
        resultBitmap.copyPixelsFromBuffer(outBuffer);
        return resultBitmap;
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

    private void ensureUpright(int uw, int uh) {
        if (uprightBitmap == null || uprightBitmap.getWidth() != uw
                || uprightBitmap.getHeight() != uh) {
            uprightBitmap = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888);
            uprightCanvas = new Canvas(uprightBitmap);
        }
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

    /** Handles a capture request then paints the bitmap to the TextureView. */
    private void deliver(Bitmap bmp) {
        if (captureRequested) {
            captureRequested = false;
            saveToGallery(bmp.copy(Bitmap.Config.ARGB_8888, false));
        }
        drawToTexture(bmp);
    }

    private void drawToTexture(Bitmap bmp) {
        if (!texturePreview.isAvailable()) {
            return;
        }
        Canvas canvas = texturePreview.lockCanvas();
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
            texturePreview.unlockCanvasAndPost(canvas);
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
        Bitmap filtered = processFrame(currentImage, true);
        if (filtered != null) {
            deliver(filtered);
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
