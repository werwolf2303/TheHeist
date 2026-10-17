package heist.platform;

import heist.engine.Image;

/**
 * A live front-facing camera feed, used for the self-view during the prize video call
 * (SecureCallVideoViewController's AVCaptureSession in the original: see docs). Obtained from
 * {@link Platform#frontCamera()}, which returns null on a device/port with no usable front camera -
 * the original silently skips the self-view in that case too, it never shows an error to the player.
 */
public interface CameraPreview {
    /** Starts capturing. Safe to call once; the game calls it when the video call scene begins. */
    void start();

    /** Stops capturing and releases the camera. The game calls it as soon as the call ends. */
    void stop();

    /**
     * The most recently captured frame, or null if none has arrived yet (capture just started, or the
     * platform could not get a frame this time). The image is in the camera's native orientation; the
     * game applies the same 90-degree rotation and 0.9 scale the original used (confirmed in the
     * assembly: SecureCallVideoViewController_initFrontCameraCapture rotates the preview layer by pi/2
     * and scales it by 0.9 after inserting it behind the call UI).
     */
    Image currentFrame();
}
