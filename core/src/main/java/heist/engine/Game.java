package heist.engine;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 320x480 logical canvas, letterboxed into whatever view the port provides. Input arrives on any thread
 * and is queued; update/render are called once per frame by the port.
 */
public final class Game implements InputListener {
    public static final int W = 320, H = 480;

    // ConcurrentLinkedQueue is not in TeaVM's classlib
    private final Queue<Runnable> inputQueue = new ArrayDeque<Runnable>();

    private Scene scene, pending;
    private volatile Overlay overlay;
    private float fade;          // 0 = clear, 1 = black
    private int fadeDir;         // -1 fading in, +1 fading out
    private static final float FADE_TIME = 0.18f;

    private volatile float scale = 1, offX, offY;

    /** Switches scenes with a short fade. The first scene is shown immediately. */
    public void setScene(Scene s) {
        s.attach(this);
        if (scene == null) {
            scene = s;
            s.onEnter();
            return;
        }
        pending = s;
        fadeDir = 1;
    }

    public Scene scene() { return scene; }

    /** Puts a layer on top of the game that gets the input first (null removes it). */
    public void setOverlay(Overlay o) { overlay = o; }

    /** Returns {scale, offsetX, offsetY} to letterbox 320x480 into the given view size. */
    public static float[] letterbox(int viewW, int viewH) {
        float scale = Math.min(viewW / (float) W, viewH / (float) H);
        return new float[] { scale, (viewW - W * scale) / 2f, (viewH - H * scale) / 2f };
    }

    private float lx(float px) { return (px - offX) / scale; }
    private float ly(float py) { return (py - offY) / scale; }

    public void touchDown(float viewX, float viewY) {
        final float x = lx(viewX), y = ly(viewY);
        synchronized (inputQueue) {
            inputQueue.add(new Runnable() { public void run() {
                Overlay o = overlay;
                if (o != null && o.pointerPressed(x, y)) return;
                if (scene != null && fadeDir == 0) scene.pointerPressed(x, y);
            } });
        }
    }

    public void touchMove(float viewX, float viewY) {
        final float x = lx(viewX), y = ly(viewY);
        synchronized (inputQueue) {
            inputQueue.add(new Runnable() { public void run() {
                Overlay o = overlay;
                if (o != null && o.pointerDragged(x, y)) return;
                if (scene != null && fadeDir == 0) scene.pointerDragged(x, y);
            } });
        }
    }

    public void touchUp(float viewX, float viewY) {
        final float x = lx(viewX), y = ly(viewY);
        synchronized (inputQueue) {
            inputQueue.add(new Runnable() { public void run() {
                Overlay o = overlay;
                if (o != null && o.pointerReleased(x, y)) return;
                if (scene != null && fadeDir == 0) scene.pointerReleased(x, y);
            } });
        }
    }

    public void key(final Key key) {
        synchronized (inputQueue) {
            inputQueue.add(new Runnable() { public void run() {
                Overlay o = overlay;
                if (o != null && o.key(key)) return;
                if (scene != null) scene.keyPressed(key);
            } });
        }
    }

    public void update(float dt) {
        Runnable r;
        while (true) {
            synchronized (inputQueue) { r = inputQueue.poll(); }
            if (r == null) break;
            r.run();
        }
        Audio.update(dt);
        if (fadeDir != 0) {
            fade += fadeDir * dt / FADE_TIME;
            if (fadeDir > 0 && fade >= 1f) {
                fade = 1f;
                if (scene != null) scene.onExit();
                scene = pending;
                pending = null;
                if (scene != null) scene.onEnter();
                fadeDir = -1;
            } else if (fadeDir < 0 && fade <= 0f) {
                fade = 0f;
                fadeDir = 0;
            }
        }
        if (scene != null) scene.update(dt);
        Overlay o = overlay;
        if (o != null) o.update(dt);
    }

    /** Draws the current scene into a view of the given size, letterboxed on black. */
    public void render(Gfx view, int viewW, int viewH) {
        view.setColor(0xFF000000);
        view.fillRect(0, 0, viewW, viewH);
        float[] box = letterbox(viewW, viewH);
        scale = box[0];
        offX = box[1];
        offY = box[2];
        Gfx g = view.create();
        g.translate(offX, offY);
        g.scale(scale, scale);
        g.clipRect(0, 0, W, H);
        g.group("Scene");
        if (scene != null) scene.render(g);
        if (fade > 0f) {
            g.group("Screen fade");
            g.setColor(0, 0, 0, Math.round(Math.min(1f, fade) * 255));
            g.fillRect(0, 0, W, H);
        }
        Overlay o = overlay;
        if (o != null) {
            g.group("Overlay");
            o.render(g);
        }
        g.dispose();
    }
}
