package heist.desktop;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import heist.engine.Fnt;
import heist.engine.Image;
import heist.platform.AssetSource;
import heist.platform.AudioBackend;
import heist.platform.CameraPreview;
import heist.platform.Platform;
import heist.platform.Storage;

/** The desktop port's {@link Platform}: Java2D pictures, javax.sound, game files and progress in folders on disk. */
public final class DesktopPlatform implements Platform {
    private final FileAssetSource assets;
    private final PlistStorage storage;
    private final ClipAudio audio = new ClipAudio();

    public DesktopPlatform(File assetsDir, File dataDir) {
        this.assets = new FileAssetSource(assetsDir);
        this.storage = new PlistStorage(dataDir);
        startCameraProbe();   // start early so it resolves before any call screen needs it
    }

    File assetsDir() { return assets.root(); }

    public Image createImage(int width, int height) { return AwtImage.create(width, height); }

    public Image decodeImage(byte[] encoded) {
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(encoded));
            return bi == null ? null : AwtImage.of(bi);
        } catch (IOException e) {
            return null;
        }
    }

    public void imageLoaded(Image image, String assetName) {
        if (image instanceof AwtImage) ((AwtImage) image).name = assetName;
    }

    public Fnt sansFont(boolean bold, float size) {
        return new AwtFnt(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 12).deriveFont(size));
    }

    public Fnt loadFont(String name, float size) throws IOException {
        try {
            return new AwtFnt(Font.createFont(Font.TRUETYPE_FONT, new File(assets.root(), name + ".ttf")).deriveFont(size));
        } catch (FontFormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public AssetSource assets() { return assets; }

    public Storage storage() { return storage; }

    public AudioBackend audio() { return audio; }

    public void warn(String message) { System.err.println(message); }

    public void error(String message) { System.err.println(message); }

    public boolean hasProximitySensor() { return false; }

    private volatile boolean cameraProbeStarted;
    private volatile CameraPreview camera;

    /** Camera enumeration can block for seconds; probe on a daemon thread. */
    private void startCameraProbe() {
        if (cameraProbeStarted) return;
        cameraProbeStarted = true;
        Thread t = new Thread(new Runnable() { public void run() { camera = DesktopCameraPreview.open(); } },
                "heist-camera-probe");
        t.setDaemon(true);
        t.start();
    }

    public CameraPreview frontCamera() {
        startCameraProbe();
        return camera;
    }
}
