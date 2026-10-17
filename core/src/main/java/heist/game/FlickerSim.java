package heist.game;

import java.util.Random;

import heist.engine.Audio;

/**
 * The original's FlickerController, tick for tick. Started, the view is hidden. Every {@code interval} seconds a random
 * integer is taken modulo {@code modulus} (as a signed 32 bit value, so only about one draw in 2 x modulus gives 1): on 1 the
 * view is hidden, otherwise it is shown, and a change to shown plays the first flicker sound. After {@code duration} seconds
 * it stops: the view is shown for good and, when sound is on, the second flicker sound plays and the delegate is told 0.6 s
 * later; without sound the delegate is told at once. The flicker itself is {@link #STRETCH} seconds longer than the controller's
 * duration (see there).
 */
final class FlickerSim {
    private static final Random RND = new Random();

    /**
     * Extra seconds the flicker takes. In the original the screen sits on the final lit frame while transitionOutBegin's
     * applyLevelMask (a screenshot of the view and a mask made from it) keeps the iPod touch 3G's main thread busy: the flicker
     * seems to last about 2.1 s until the brass (1.0 s of timers, the 0.6 s hold and that stall). Here the whole flicker is
     * slowed down to fill that time, every tick and its pause longer in the same proportion, so it has the same number of
     * flashes. -Dheist.flickerStretch=0 gives the plain timers.
     */
    static final float STRETCH = Float.parseFloat(System.getProperty("heist.flickerStretch", "0.5"));

    private final float interval, duration;
    private final int modulus;
    private final boolean sound;
    private boolean hidden;
    private float t, nextTick, sinceStop;
    private boolean stopped, done;

    FlickerSim(float interval, float duration, int modulus, boolean startHidden) {
        float k = (duration + STRETCH) / duration;      // the whole timeline is stretched, ticks included
        this.interval = interval * k;
        this.duration = duration * k;
        this.modulus = modulus;
        this.sound = false;
        this.hidden = startHidden;
        this.nextTick = this.interval;
    }

    /** @param withSound the controller was created with the sound effects (gameSoundFxIsActivated) */
    FlickerSim(float interval, float duration, int modulus, boolean startHidden, boolean withSound) {
        float k = (duration + STRETCH) / duration;      // the whole timeline is stretched, ticks included
        this.interval = interval * k;
        this.duration = duration * k;
        this.modulus = modulus;
        this.sound = withSound;
        this.hidden = startHidden;
        this.nextTick = this.interval;
    }

    void update(float dt) {
        if (done) return;
        if (!stopped) {
            t += dt;
            while (nextTick < duration && nextTick <= t) {
                tick();
                nextTick += interval;
            }
            if (t >= duration) stop();
        } else {
            sinceStop += dt;
            if (sinceStop >= 0.6f) done = true;
        }
    }

    private void tick() {
        boolean target = RND.nextInt() % modulus == 1;        // ___modsi3(arc4random(), modulus) == 1
        if (target != hidden) {
            hidden = target;
            if (!hidden && sound) Audio.play("level_completion_flicker_3");
        }
    }

    private void stop() {
        stopped = true;
        hidden = false;
        if (sound) Audio.play("level_completion_flicker_4");
        else done = true;
    }

    boolean visible() { return !hidden; }

    /** How long the flicker runs before it stops (the stretched duration). */
    float duration() { return duration; }

    /** The delegate has been told: the flicker and its closing hold are over. */
    boolean finished() { return done; }
}
