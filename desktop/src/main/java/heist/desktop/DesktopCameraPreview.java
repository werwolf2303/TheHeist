package heist.desktop;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import heist.engine.Image;
import heist.platform.CameraPreview;

/**
 * Front-camera stand-in for desktop: uses the first webcam OpenCV finds.
 * start/stop open and close the OS device each call so the indicator LED isn't lit between calls.
 * The grab loop always overwrites {@link #latest}; slow grabs go stale, never backlog.
 */
final class DesktopCameraPreview implements CameraPreview {
    private final AtomicReference<Image> latest = new AtomicReference<Image>();
    private volatile boolean started;
    private volatile boolean stopRequested;
    private Thread grabThread;

    /** Null when no webcam is present (treated like "no front camera"). */
    static DesktopCameraPreview open() {
        OpenCVFrameGrabber probe = new OpenCVFrameGrabber(0);
        try {
            probe.start();
        } catch (FrameGrabber.Exception e) {
            return null;
        }
        try {
            probe.stop();
        } catch (FrameGrabber.Exception ignored) {
            // probe confirmed device opens; failure to close cleanly is not fatal
        }
        return new DesktopCameraPreview();
    }

    private DesktopCameraPreview() {
    }

    public void start() {
        if (started) return;
        started = true;
        stopRequested = false;
        grabThread = new Thread(this::runGrabLoop, "heist-camera-grab");
        grabThread.setDaemon(true);
        grabThread.start();
    }

    private void runGrabLoop() {
        OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(0);
        Java2DFrameConverter converter = new Java2DFrameConverter();
        try {
            grabber.start();
            System.err.println("[camera] grabber opened: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());
        } catch (FrameGrabber.Exception e) {
            System.err.println("[camera] could not open webcam: " + e);
            return;
        }
        try {
            int n = 0;
            while (!stopRequested) {
                try {
                    Frame frame = grabber.grab();
                    if (frame == null) { if (n++ < 20) System.err.println("[camera] grab() returned null"); continue; }
                    BufferedImage bi = converter.getBufferedImage(frame);
                    if (bi == null) { if (n++ < 20) System.err.println("[camera] converter returned null for frame " + frame.imageWidth + "x" + frame.imageHeight); continue; }
                    latest.set(AwtImage.of(bi));
                    if (n++ < 5) System.err.println("[camera] got frame " + bi.getWidth() + "x" + bi.getHeight() + " type=" + bi.getType());
                } catch (FrameGrabber.Exception e) {
                    System.err.println("[camera] frame grab failed: " + e);
                }
            }
        } finally {
            try {
                grabber.stop();
                grabber.release();
            } catch (FrameGrabber.Exception ignored) {
                // closing on the way out; nothing more to do if it fails
            }
        }
    }

    public void stop() {
        if (!started) return;
        started = false;
        stopRequested = true;
        latest.set(null);
        if (grabThread != null) {
            try {
                grabThread.join(2000);
            } catch (InterruptedException ignored) { }
            grabThread = null;
        }
    }

    public Image currentFrame() { return latest.get(); }
}
