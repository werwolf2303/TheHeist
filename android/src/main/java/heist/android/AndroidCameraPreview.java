package heist.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;

import androidx.annotation.RequiresApi;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import heist.engine.Image;
import heist.platform.CameraPreview;

/**
 * Front camera preview backed by CameraX.
 * On foldables with multiple LENS_FACING_FRONT lenses the highest-resolution one is picked as a heuristic
 * (the inner/main selfie camera tends to be the higher-spec one); falls back to CameraX default.
 */
@RequiresApi(21)
final class AndroidCameraPreview implements CameraPreview {
    private final Context app;
    private final CameraSelector selector;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<Image> latest = new AtomicReference<Image>();
    private final LifecycleRegistry lifecycle;
    private final LifecycleOwner lifecycleOwner;
    private ProcessCameraProvider provider;
    private boolean started;
    private int loggedFrames;

    // Uses addListener with a main-thread executor: LifecycleRegistry must only be touched on main thread.
    // Returns null via onReady if no camera permission or no usable front camera.
    static void open(Context app, java.util.concurrent.Executor mainExecutor, final Callback<CameraPreview> onReady) {
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            onReady.call(null);
            return;
        }
        final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(app);
        future.addListener(new Runnable() {
            public void run() {
                ProcessCameraProvider provider;
                        try {
                    provider = future.get();
                } catch (Exception e) {
                    android.util.Log.w("heist", "[camera] could not get a camera provider: " + e);
                    onReady.call(null);
                    return;
                }
                CameraSelector selector = pickFrontCamera(provider);
                onReady.call(selector == null ? null : new AndroidCameraPreview(app, provider, selector));
            }
        }, mainExecutor);
    }

    /** Picks the highest-resolution LENS_FACING_FRONT camera; falls back to CameraX default. */
    private static CameraSelector pickFrontCamera(ProcessCameraProvider provider) {
        List<CameraInfo> all;
        try {
            all = CameraSelector.DEFAULT_FRONT_CAMERA.filter(provider.getAvailableCameraInfos());
        } catch (IllegalArgumentException e) {
            return null;   // no front camera at all
        }
        if (all.isEmpty()) return null;
        if (all.size() == 1) return CameraSelector.DEFAULT_FRONT_CAMERA;

        CameraInfo best = null;
        long bestPixels = -1;
        for (CameraInfo info : all) {
            try {
                android.hardware.camera2.CameraCharacteristics c = Camera2CameraInfo.extractCameraCharacteristics(info);
                Size[] sizes = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                long pixels = 0;
                if (sizes != null) {
                    for (Size s : sizes) pixels = Math.max(pixels, (long) s.getWidth() * s.getHeight());
                }
                if (pixels > bestPixels) { bestPixels = pixels; best = info; }
            } catch (RuntimeException e) {
                // characteristics unavailable for this lens - skip it rather than guess
            }
        }
        if (best == null) return CameraSelector.DEFAULT_FRONT_CAMERA;
        final String bestId = Camera2CameraInfo.from(best).getCameraId();
        return new CameraSelector.Builder()
                .addCameraFilter(cameras -> {
                    for (CameraInfo info : cameras) {
                        if (bestId.equals(Camera2CameraInfo.from(info).getCameraId())) {
                            return java.util.Collections.singletonList(info);
                        }
                    }
                    return cameras;
                })
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();
    }

    private AndroidCameraPreview(Context app, ProcessCameraProvider provider, CameraSelector selector) {
        this.app = app;
        this.provider = provider;
        this.selector = selector;
        // javac rejects reading a blank final from within the lambda that initialises it; box it
        final LifecycleRegistry[] box = new LifecycleRegistry[1];
        this.lifecycleOwner = () -> box[0];
        this.lifecycle = new LifecycleRegistry(lifecycleOwner);
        box[0] = lifecycle;
        // INITIALIZED is the LifecycleRegistry construction default — no call needed
    }

    // start()/stop() are called from the game thread, not the main thread — post to main for LifecycleRegistry
    public void start() {
        if (started) return;
        started = true;
        new Handler(Looper.getMainLooper()).post(this::startOnMainThread);
    }

    private void startOnMainThread() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::onFrame);

        try {
            provider.unbindAll();
            Camera camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis);
            if (camera == null) started = false;
        } catch (RuntimeException e) {
            android.util.Log.w("heist", "[camera] could not start the front camera: " + e);
            started = false;
        }
    }

    private void onFrame(ImageProxy proxy) {
        try {
            ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
            java.nio.ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int w = proxy.getWidth(), h = proxy.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (rowStride == w * 4) {
                bmp.copyPixelsFromBuffer(buf);
            } else {
                // padded rows: copy line by line into a tightly packed buffer first
                java.nio.ByteBuffer packed = java.nio.ByteBuffer.allocate(w * h * 4);
                byte[] row = new byte[rowStride];
                for (int y = 0; y < h; y++) {
                    buf.position(y * rowStride);
                    buf.get(row, 0, rowStride);
                    packed.put(row, 0, w * 4);
                }
                packed.rewind();
                bmp.copyPixelsFromBuffer(packed);
            }
            // Sensor delivers native orientation; rotate then mirror separately.
            // A combined matrix produced a stretched result — two sequential Bitmap.createBitmap calls are unambiguous.
            int rotation = proxy.getImageInfo().getRotationDegrees();
            if (loggedFrames < 5) { loggedFrames++; android.util.Log.i("heist", "[camera] raw " + w + "x" + h + " rotation=" + rotation); }
            Bitmap upright = bmp;
            if (rotation != 0) {
                android.graphics.Matrix rotate = new android.graphics.Matrix();
                rotate.postRotate(rotation);
                upright = Bitmap.createBitmap(bmp, 0, 0, w, h, rotate, true);
            }
            // front camera preview is conventionally mirrored
            android.graphics.Matrix mirror = new android.graphics.Matrix();
            mirror.postScale(-1f, 1f);
            Bitmap oriented = Bitmap.createBitmap(upright, 0, 0, upright.getWidth(), upright.getHeight(), mirror, true);
            latest.set(new AndroidImage(oriented));
        } catch (RuntimeException e) {
            // frame glitch; currentFrame() keeps the previous one
        } finally {
            proxy.close();
        }
    }

    public void stop() {
        if (!started) return;
        started = false;
        latest.set(null);
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
            // CREATED not DESTROYED: LifecycleRegistry is terminal once DESTROYED, but this object
            // is reused for the app's lifetime — CREATED is the lowest revivable state.
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
            provider.unbindAll();
        } });
    }

    public Image currentFrame() { return latest.get(); }
}
