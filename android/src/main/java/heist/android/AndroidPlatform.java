package heist.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

import heist.engine.Fnt;
import heist.engine.Image;
import heist.platform.AssetSource;
import heist.platform.AudioBackend;
import heist.platform.CameraPreview;
import heist.platform.Platform;
import heist.platform.Storage;

/** The Android port's {@link Platform}: Bitmaps and Canvas, AudioTrack, shared preferences, the APK's assets. */
final class AndroidPlatform implements Platform {
    private static final String TAG = "heist";

    private final Context app;
    private final AssetManager assetManager;
    private final AndroidAssetSource assets;
    private final AndroidStorage storage;
    private final AndroidAudio audio = new AndroidAudio();
    private final boolean hasProximitySensor;

    AndroidPlatform(Context context) {
        app = context.getApplicationContext();
        assetManager = app.getAssets();
        assets = new AndroidAssetSource(assetManager);
        storage = new AndroidStorage(app);
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        hasProximitySensor = sm != null && sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null;
        startCameraProbe();   // start early so it resolves before any call screen needs it
    }

    public Image createImage(int width, int height) { return AndroidImage.create(width, height); }

    public Image decodeImage(byte[] encoded) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;                       // art keeps its own pixel size, whatever the screen density
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (android.os.Build.VERSION.SDK_INT < 21) {
            //noinspection deprecation — inPurgeable lets the OS reclaim bitmap native memory on low-RAM devices
            o.inPurgeable = true;
            //noinspection deprecation
            o.inInputShareable = true;
        }
        Bitmap b = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, o);
        return b == null ? null : new AndroidImage(b);
    }

    public Fnt sansFont(boolean bold, float size) {
        return new AndroidFnt(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL), size);
    }

    public Fnt loadFont(String name, float size) throws IOException {
        return new AndroidFnt(Typeface.createFromAsset(assetManager, AndroidAssetSource.ROOT + name + ".ttf"), size);
    }

    public AssetSource assets() { return assets; }

    public Storage storage() { return storage; }

    public AudioBackend audio() { return audio; }

    public void warn(String message) { Log.w(TAG, message); }

    public void error(String message) { Log.e(TAG, message); }

    public boolean hasProximitySensor() { return hasProximitySensor; }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cameraProbeStarted;
    private volatile CameraPreview camera;

    // ProcessCameraProvider.getInstance blocks; AndroidCameraPreview.open() is async via onReady.
    // LifecycleRegistry must only be touched on the main thread, so the callback runs there.
    private void startCameraProbe() {
        if (cameraProbeStarted) return;
        cameraProbeStarted = true;
        if (android.os.Build.VERSION.SDK_INT < 21) return;
        AndroidCameraPreview.open(app, new java.util.concurrent.Executor() {
            public void execute(Runnable r) { mainHandler.post(r); }
        }, new Callback<CameraPreview>() { public void call(CameraPreview c) { camera = c; } });
    }

    public CameraPreview frontCamera() {
        startCameraProbe();
        return camera;
    }
}
