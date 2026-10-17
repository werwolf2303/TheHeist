package heist.game;

import heist.engine.Gfx;

import heist.engine.Assets;
import heist.engine.Sprite;

/**
 * The safe room backdrop (NanoPuzzleAnimationUnlockController.nib): wall, security bars, panel with four
 * padlocks, two cameras, two floor-laser grids and the light layers. Which components are active follows
 * the disarm level exactly like the original's engageSecurityComponentsInstantly.
 */
public final class SafeRoomView {
    // state
    boolean lasersOn, camerasOn, barsLocked, greenLights, redLights;
    float barsShift;                    // 0 = in place; animates to unlock
    final boolean[] padlockUnlocked = new boolean[4];
    final boolean[] padlockHidden = new boolean[4];      // the unlock flicker hides the lock itself (setHidden:)
    float whiteLights = 1f, greenAlpha, redAlpha;
    float lasersAlpha = 1f;
    float camera1Angle, camera2Angle;
    VaultController vault;
    float time;
    float cameraTargetX = 160, cameraTargetY = 240;
    boolean cameraTracking;
    /** The cameras' red status lights: on while the player touches the room (they blink then), off otherwise. */
    boolean cameraLights;
    private float laserLatT, laserLonT;

    public SafeRoomView() { applyDisarmLevel(); }

    /** Sets every component instantly from the current disarm level. */
    public void applyDisarmLevel() {
        Disarm d = Disarm.get();
        int lvl = d.disarmLevel();
        lasersOn = lvl < Disarm.LASERS;
        camerasOn = lvl < Disarm.CAMERAS;
        barsLocked = lvl < Disarm.BARS;
        greenLights = lvl >= Disarm.BARS;
        greenAlpha = greenLights ? 1f : 0f;
        whiteLights = greenLights ? 0f : 1f;
        redAlpha = 0f;
        lasersAlpha = lasersOn ? 1f : 0f;
        barsShift = 0f;
        if (!camerasOn) {   // unlockCamerasInstantly: camerasPointAt (view centre x, FLT_MAX), i.e. straight down
            camera1Angle = pointAt1(160f, Float.MAX_VALUE);
            camera2Angle = pointAt2(160f, Float.MAX_VALUE);
        }
        int ups = d.levelUpCount();
        for (int i = 0; i < 4; i++) padlockUnlocked[i] = ups >= i + 1;
    }

    public boolean electricityOn() { return Disarm.get().disarmLevel() < Disarm.ELECTRICITY && barsLocked; }

    public void update(float dt) {
        time += dt;
        laserLatT += dt;
        laserLonT += dt;
        if (camerasOn) {
            if (cameraTracking) {
                sweeping = false;
                camera1Angle = ease(camera1Angle, pointAt1(cameraTargetX, cameraTargetY), dt);
                camera2Angle = ease(camera2Angle, pointAt2(cameraTargetX, cameraTargetY), dt);
            } else {
                // cameras: the sweep starts from wherever the camera currently points, then ping-pongs for ever
                if (!sweeping) {
                    sweeping = true;
                    sweepT = 0f;
                    sweepFrom1 = camera1Angle;
                    sweepFrom2 = camera2Angle;
                }
                sweepT += dt;
                camera1Angle = sweepAngle(sweepFrom1, 0.7854f, -0.3316f, 2.5f, 2.5f, sweepT);
                camera2Angle = sweepAngle(sweepFrom2, -0.7854f, 0.3316f, 3.0f, 3.0f, sweepT);
            }
        }
    }

    private boolean sweeping;
    private float sweepT, sweepFrom1, sweepFrom2;

    /** Ease-in-out between 0 and 1 (the media timing function the original uses). */
    private static float easeInOut(float u) { return heist.engine.Ease.inOut(u); }

    /**
     * Approaches {@code end} from the current angle, then swings between {@code end} and {@code other}
     * (autoreversing, {@code legSeconds} per leg).
     */
    private static float sweepAngle(float from, float end, float other, float approachSeconds, float legSeconds, float t) {
        if (t < approachSeconds) return from + (end - from) * easeInOut(t / approachSeconds);
        float phase = ((t - approachSeconds) % (2f * legSeconds)) / legSeconds;
        if (phase > 1f) phase = 2f - phase;
        return end + (other - end) * easeInOut(phase);
    }

    private static float ease(float current, float target, float dt) {
        return current + (target - current) * Math.min(1f, dt * 8f);
    }

    /** A signed distance reinterpreted as unsigned 32 bit, the way the original converts it before atan2. */
    private static double unsigned(float v) {
        long i = (long) v;
        return i < 0 ? i + 4294967296.0 : i;
    }

    /**
     * camerasPointAt: measured from each camera's frame corner, so the left camera turns between -45 and +45 degrees
     * (target to its right ... straight below) and cannot swing up and out of the screen.
     */
    static float pointAt1(float tx, float ty) {
        double dx = unsigned(tx - (-14)), dy = unsigned(ty - (-8));
        return (float) (Math.atan2(dy, dx) - Math.PI / 4);
    }

    static float pointAt2(float tx, float ty) {
        double dx = Math.abs((long) (tx - (280 + 57))), dy = unsigned(ty - (-8));
        return (float) (Math.PI / 4 - Math.atan2(dy, dx));
    }

    public void draw(Gfx g) { draw(g, null); }

    /** @param beforeLights run after the lasers and before the light layers (where the electricity sits). */
    public void draw(Gfx g, Runnable beforeLights) {
        if (vault != null && vault.coversRoom()) {
            vault.drawOpenRoom(g);
            return;
        }
        g.group("Room: walls and floor");
        Assets.image("background_wall.jpg").draw(g, 0, 0);
        g.group("Room: security bars");
        if (barsLocked || barsShift != 0f) {
            Assets.image("securitybars.png").draw(g, 60, 23 + barsShift);
        }
        g.group("Room: walls and floor");
        Assets.image("background_nowall_cropped.png").draw(g, 0, 0);
        g.group("Room: padlock panel");
        Assets.image("panel_blank.png").draw(g, 257, 109);
        float[] py = { 141, 159, 176, 194 };
        for (int i = 0; i < 4; i++) {
            if (padlockHidden[i]) continue;
            Assets.image(padlockUnlocked[i] ? "panel_padlock_unlocked.png" : "panel_padlock.png").draw(g, 260, py[i]);
        }
        g.group("Room: cameras");
        drawCamera(g, "camera_left.png", -14, -8, 17, 18, camera1Angle);
        drawCamera(g, "camera_right.png", 280, -8, 57 - 17, 18, camera2Angle);
        g.group("Room: floor lasers");
        if (lasersAlpha > 0f) drawLasers(g);
        g.group("Room: electricity");
        if (beforeLights != null) beforeLights.run();
        g.group("Room: lights");
        if (whiteLights > 0f) Assets.image("lights_white.png").drawAlpha(g, 14, 0, whiteLights);
        if (redAlpha > 0f) Assets.image("lights_locked.png").drawAlpha(g, 0, 0, redAlpha);
        if (greenAlpha > 0f) Assets.image("lights_unlocked.png").drawAlpha(g, 0, 0, greenAlpha);
    }

    /** True if the point (screen coordinates) is on one of the two cameras, as they are turned right now. */
    boolean hitsCamera(float x, float y) {
        return hitsCamera("camera_left.png", -14, -8, 17, 18, camera1Angle, x, y)
                || hitsCamera("camera_right.png", 280, -8, 57 - 17, 18, camera2Angle, x, y);
    }

    private boolean hitsCamera(String img, float cx, float cy, float ax, float ay, float angle, float x, float y) {
        Sprite s = Assets.image(img);
        // undo the camera's rotation about its pivot, then test against the picture's rectangle (a little generous: the
        // cameras are small and half out of the screen)
        float dx = x - (cx + ax), dy = y - (cy + ay);
        double c = Math.cos(-angle), sn = Math.sin(-angle);
        float lx = (float) (dx * c - dy * sn), ly = (float) (dx * sn + dy * c);
        float m = 8f;
        return lx >= -ax - m && lx < -ax + s.w + m && ly >= -ay - m && ly < -ay + s.h + m;
    }

    /** Draws just the two cameras (the vault layer shows them above the opened door). */
    void drawCamerasOnly(Gfx g) {
        // showOpenVault: unlockCamerasInstantly, the switched-off cameras point straight down
        drawCamera(g, "camera_left.png", -14, -8, 17, 18, pointAt1(160f, Float.MAX_VALUE));
        drawCamera(g, "camera_right.png", 280, -8, 57 - 17, 18, pointAt2(160f, Float.MAX_VALUE));
    }

    private void drawCamera(Gfx g, String img, float x, float y, float ax, float ay, float angle) {
        Sprite s = Assets.image(img);
        Gfx gc = g.create();
        gc.translate(x + ax, y + ay);
        gc.rotate(angle);
        s.draw(gc, -ax, -ay);
        if (cameraLights && camerasOn) {
            // securityCamera1Light / 2Light: a 7 pt picture at (16, 18) on the left camera and (34, 18) on the right one
            Assets.image("camera_light.png").draw(gc, -ax + (img.contains("left") ? 16 : 34), -ay + 18);
        }
        gc.dispose();
    }

    private void drawLasers(Gfx g) {
        Gfx gl = g.create();
        if (lasersAlpha < 1f) gl.setAlpha(lasersAlpha);
        // the two grids drift back and forth: latitude along y, longitude along x (autoreversing)
        float latY = -15f * tri(laserLatT / 4f), lonX = -8f * tri(laserLonT / 5f);
        Assets.image("floorlasers_latitude.png").draw(gl, 5, 262 + latY);
        Assets.image("floorlasers_longitude.png").draw(gl, -24 + lonX, 249);
        gl.dispose();
    }

    /** 0..1..0 ease-in/out wave with period 1 (autoreverse). */
    private static float tri(float t) {
        float f = t % 2f;
        if (f > 1f) f = 2f - f;
        return f * f * (3 - 2 * f);
    }
}
