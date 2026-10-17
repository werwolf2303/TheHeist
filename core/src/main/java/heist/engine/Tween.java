package heist.engine;

/** A float animated from a start to an end value over a duration with easing. */
public final class Tween {
    private float from, to, t, dur;
    private boolean active;

    public Tween(float initial) { from = to = initial; }

    public void start(float target, float duration) {
        from = get();
        to = target;
        dur = Math.max(0.0001f, duration);
        t = 0;
        active = true;
    }

    public void set(float v) { from = to = v; active = false; }

    public void update(float dt) {
        if (!active) return;
        t += dt;
        if (t >= dur) { t = dur; active = false; }
    }

    public boolean isActive() { return active; }

    public float get() {
        if (!active && t >= dur) return to;
        if (!active && dur == 0) return to;
        float k = Math.min(1f, t / Math.max(dur, 0.0001f));
        k = 1f - (1f - k) * (1f - k); // ease-out quad
        return from + (to - from) * k;
    }
}
