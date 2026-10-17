package heist.android;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import heist.engine.Game;
import heist.engine.Key;

/** The game's surface: a render thread that steps the game at the display rate and draws it letterboxed. */
final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    /** Surface is capped near 2x game pixels; plenty for 320x480 art. */
    private static final float TARGET_SCALE = 2f;

    private final Game game;
    private final boolean debugGestureEnabled;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;
    private final Object lock = new Object();

    /** debugGestureEnabled: enable 3-finger tap to open the developer menu (debug builds). */
    GameView(Context context, Game game, boolean debugGestureEnabled) {
        super(context);
        this.game = game;
        this.debugGestureEnabled = debugGestureEnabled;
        getHolder().addCallback(this);
        setFocusable(true);
    }

    // ---- surface ---------------------------------------------------------------------------------------------------

    public void surfaceCreated(SurfaceHolder h) { }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
        int vw = getWidth(), vh = getHeight();
        if (vw > 0 && vh > 0) {
            // pick a surface size so the letterboxed picture is ~2x the logical size
            float fit = Math.min(vw / (float) Game.W, vh / (float) Game.H);
            if (fit > TARGET_SCALE) {
                int tw = Math.round(vw * TARGET_SCALE / fit), th = Math.round(vh * TARGET_SCALE / fit);
                if (tw != w || th != hgt) {
                    h.setFixedSize(tw, th);
                    return;
                }
            }
        }
        surfaceReady = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    // ---- thread ----------------------------------------------------------------------------------------------------

    void resumeLoop() {
        if (running) return;
        running = true;
        thread = new Thread(this, "game");
        thread.start();
    }

    void pauseLoop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

    public void run() {
        long last = System.nanoTime();
        while (running) {
            if (!surfaceReady) {
                synchronized (lock) {
                    try { lock.wait(100); } catch (InterruptedException e) { return; }
                }
                last = System.nanoTime();
                continue;
            }
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            try { game.update(dt); } catch (RuntimeException e) { android.util.Log.e("heist", "update failed", e); }
            SurfaceHolder holder = getHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) game.render(new AndroidGfx(c), c.getWidth(), c.getHeight());
            } catch (RuntimeException e) {
                android.util.Log.e("heist", "frame failed", e);
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (RuntimeException ignored) { }
                }
            }
            long spent = System.nanoTime() - now;
            long wait = 16_000_000L - spent;  // lockCanvas already paces to vsync; this just prevents a spin
            if (wait > 1_000_000L) {
                try { Thread.sleep(wait / 1_000_000L); } catch (InterruptedException e) { return; }
            }
        }
    }

    // ---- touch -----------------------------------------------------------------------------------------------------

    private float toSurfaceX(float x) { return x * getHolder().getSurfaceFrame().width() / Math.max(1f, getWidth()); }

    private float toSurfaceY(float y) { return y * getHolder().getSurfaceFrame().height() / Math.max(1f, getHeight()); }

    private boolean tracking;

    // 3-finger tap opens the developer menu; extra pointers are never forwarded as game input.
    // "tap" = all 3 down together, all lifted within TAP_MAX_MS, centroid < TAP_MAX_MOVE px from start.
    private static final long TAP_MAX_MS = 400;
    private static final float TAP_MAX_MOVE = 40f;   // view pixels
    private long threeFingerDownAt = -1;
    private float gestureStartX, gestureStartY;
    private boolean gestureBroken;

    public boolean onTouchEvent(MotionEvent e) {
        if (debugGestureEnabled) trackDebugGesture(e);

        // always pointer 0; extra fingers are for the gesture above only
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                game.touchDown(toSurfaceX(e.getX(0)), toSurfaceY(e.getY(0)));
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking) game.touchMove(toSurfaceX(e.getX(0)), toSurfaceY(e.getY(0)));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracking) game.touchUp(toSurfaceX(e.getX(0)), toSurfaceY(e.getY(0)));
                tracking = false;
                return true;
            default:
                return true;
        }
    }

    private void trackDebugGesture(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            threeFingerDownAt = -1;
            gestureBroken = false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 3) {
            threeFingerDownAt = System.currentTimeMillis();
            gestureStartX = avgX(e);
            gestureStartY = avgY(e);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() > 3) {
            gestureBroken = true;
        } else if (action == MotionEvent.ACTION_MOVE && threeFingerDownAt >= 0) {
            float dx = avgX(e) - gestureStartX, dy = avgY(e) - gestureStartY;
            if (dx * dx + dy * dy > TAP_MAX_MOVE * TAP_MAX_MOVE) gestureBroken = true;
        } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            // pointer count still includes the lifting finger at this point
            if (threeFingerDownAt >= 0 && !gestureBroken && e.getPointerCount() == 3
                    && System.currentTimeMillis() - threeFingerDownAt <= TAP_MAX_MS) {
                game.key(Key.DEBUG);
            }
            threeFingerDownAt = -1;
        }
    }

    private static float avgX(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += e.getX(i);
        return sum / e.getPointerCount();
    }

    private static float avgY(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += e.getY(i);
        return sum / e.getPointerCount();
    }
}
