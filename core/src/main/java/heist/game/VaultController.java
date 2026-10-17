package heist.game;

import heist.engine.Image;
import heist.platform.Host;

import heist.engine.Gfx;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;


import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.ParticleEmitter;
import heist.engine.Save;
import heist.engine.Sprite;

/**
 * The safe itself (UnlockVaultController + TorchViewController + SmokeViewController + VaultInnerView):
 * the cutting sequence, the open-door view in the safe room, and the inner vault flythrough.
 * Prize redemption (web-based in the original) is omitted; shelf items appear but are not tappable.
 */
final class VaultController {
    private final HubScene hub;
    private final SafeRoomView room;
    private final Random rnd = new Random();

    // ---- tiny timeline ------------------------------------------------------------------------------
    private static final class Timer {
        float left;
        final Runnable action;
        Timer(float left, Runnable action) { this.left = left; this.action = action; }
    }

    private final List<Timer> timers = new ArrayList<Timer>();

    private void after(float seconds, Runnable r) { timers.add(new Timer(seconds, r)); }

    // ---- constants (screen points) ---------------------------------------------------------------------
    private static final float HOLE_X = 160f, HOLE_Y = 145f;          // centre of the opening in the open-door art
    private static final float INNER_SMALL = 0.45f, DOOR_ZOOM = 3.15f;
    private static final float ENTER_TIME = 3.0f, EXIT_TIME = 1.4f, DRAWER_TIME = 0.5f;   // true values from the binary's double constants

    // ---- state ------------------------------------------------------------------------------------------
    private boolean sequenceRunning;        // cutting sequence in progress
    private boolean roomHidden;             // safe room faded to black
    private float roomAlpha = 1f;
    private boolean openShown;              // open door layer visible in the room
    private boolean openFlickerHidden;      // flicker toggles the whole vault layer
    private boolean glowShown;
    private boolean unlockSequenceCompleted;
    private boolean insideInner, transition;
    private float doorZoomT = -1f;          // seconds since the fly-in / fly-out started (-1: idle)
    private boolean entering;
    private float whiteLights = 1f;
    private float whiteLightsTarget = 1f;
    private boolean innerOpen;              // inner view exists (it is created together with the open door)
    private boolean innerInteractive;
    private Audio.Handle safeLoop;
    private Image layer;
    private Gfx layerGfx;

    // torch
    private boolean torchShown;
    private float torchAlpha;
    private float cutT = -1f;               // 0..1 over CUT_TIME
    private static final float CUT_TIME = 12.0f;
    private ParticleEmitter flame, sparks;
    private boolean sparksOut;
    private float sparksOutT;
    private float flameLife0, flameVar0, sparksLife0, sparksVar0;
    private float torchFadeT = -1f;
    private static final float TORCH_R = 134f, TORCH_CX = 164f, TORCH_CY = 255f;

    // smoke
    private ParticleEmitter smoke1, smoke2;
    private boolean smokeShown, smokeRemoving;
    private float smokeRemoveT;
    private float s1Life0, s1Var0, s2Life0, s2Var0;
    private int smokePrewarmRemaining;
    private static final int SMOKE_PREWARM_PER_FRAME = 12;   // 300 steps / 12 per frame = 0.42 s to catch up, well inside the 4.5 s before it is shown

    // flicker of the whole vault layer
    private float flickerLeft = -1f, flickerTick;
    private Runnable flickerDone;

    // inner vault
    private boolean propShown;
    private float arrowFlashing = -1f;      // time since the arrow started flashing
    private boolean arrowVisible = true;
    private float arrowTick;
    private float arrowPause;
    private boolean arrowInFlickerBurst;
    private float arrowBurstLeft;
    private boolean showShelf1, showShelf2, showShelf3, showCertificate, showBriefcase;
    private boolean pedestalEnabled;
    private float pedestalX = 59f;
    private float pedestalFrom, pedestalTo, pedestalT = -1f;
    private boolean pedestalDragging;
    private float pedestalDragOff;

    VaultController(HubScene hub, SafeRoomView room) {
        this.hub = hub;
        this.room = room;
        room.vault = this;
    }

    // ---- queries ----------------------------------------------------------------------------------------------

    /** True while the vault takes over the whole screen (the room underneath is not drawn). */
    boolean coversRoom() { return openShown || insideInner || (transition && entering) || (transition && !entering); }

    /** True while the vault owns the input (everything but tapping the opened door in the room). */
    boolean blocksInput() { return sequenceRunning || transition || insideInner; }

    float roomAlpha() { return roomHidden ? 0f : roomAlpha; }

    boolean isInside() { return insideInner; }

    private boolean hintPending;

    /** firstLaunchHintCallWillTrigger: entering or leaving the inner vault is refused until the hint call has rung. */
    void setHintPending(boolean pending) { hintPending = pending; }

    /** True while the unlock sequence or a fly transition is still running. */
    boolean isBusy() { return sequenceRunning || transition || !timers.isEmpty() || flickerLeft >= 0f; }

    /** Stops the looping safe-cracking music (the scene is being thrown away). */
    void stopAudio() {
        if (safeLoop != null) { Audio.stopLoop(safeLoop); safeLoop = null; }
    }

    boolean isOpenInRoom() { return openShown && !insideInner && !transition; }

    /** Shows the opened vault straight away (relaunch after the safe was already opened). */
    void showOpenedOnLaunch() {
        if (!Disarm.get().hasUnlockedSafe()) return;
        unlockSequenceCompleted = true;
        showOpenVault(false);
        updateCompletionLevel();
        innerInteractive = true;
    }

    /**
     * Debug-only (developer menu "Vault cracked + smoke" scene state): shows the opened vault the way it
     * looks right after the real crack finishes - glow and the settling smoke still up - without replaying
     * the whole unlock timeline. The smoke is pre-warmed the same way {@link #clang()} does, so it is already
     * at visible density instead of starting from nothing.
     */
    void debugShowCrackedWithSmoke() {
        if (!Disarm.get().hasUnlockedSafe()) return;
        unlockSequenceCompleted = true;
        showOpenVault(false);
        updateCompletionLevel();
        innerInteractive = true;
        glowShown = true;
        smokeShown = true;
        smoke1 = ParticleEmitter.load("SmokePointShortDarkRed2.pex");
        smoke1.setSourcePosition(57f, 78f);
        smoke2 = ParticleEmitter.load("SmokePointShortDarkRed3.pex");
        smoke2.setSourcePosition(256f, 118f);
        smokePrewarmRemaining = 300;
    }

    // ---- unlock sequence ------------------------------------------------------------------------------------

    /** UnlockVaultController beginUnlockSequence: the drawers drop away, the lights die, the torch cuts the door out. */
    void beginUnlockSequence() {
        if (sequenceRunning || openShown) return;
        sequenceRunning = true;
        Audio.stopMusic();
        hub.slideDrawers(true, DRAWER_TIME);
        after(DRAWER_TIME, new Runnable() { public void run() {
            Audio.play("enough_puzzles");
            after(12.5f, new Runnable() { public void run() { preCutting(); } });
        } });
    }

    private void preCutting() {
        flame = ParticleEmitter.load("Cutting_16.pex");
        sparks = ParticleEmitter.load("Cutting-Mod-Sparks-6.pex");
        torchShown = true;
        torchAlpha = 0f;
        Audio.play("lights_shutdown_safe_cracking_music");
        dimRoom();
        after(2f, new Runnable() { public void run() { powerDown(); } });
    }

    // the room view pulses between full and the dimmed alpha eight times (autoreversing), then rests there
    private float dimT = -1f;
    private static final float DIM_ALPHA = 0.025f, DIM_PULSE = 0.05f;

    private void dimRoom() { dimT = 0f; }

    private void powerDown() {
        torchAlpha = 1f;
        cutT = 0f;
        Audio.play("blowtorch");
    }

    private void cuttingFinished() {
        // the torch view fades together with the room after a short pause
        torchFadeT = -1f;
        after(1.35f, new Runnable() { public void run() { torchFadeT = 0f; } });
    }

    private void blackout() {
        roomHidden = true;
        torchShown = false;
        flame = null;
        sparks = null;
        after(1.5f, new Runnable() { public void run() { clang(); } });
    }

    private void clang() {
        smoke1 = ParticleEmitter.load("SmokePointShortDarkRed2.pex");
        smoke1.setSourcePosition(57f, 78f);
        smoke2 = ParticleEmitter.load("SmokePointShortDarkRed3.pex");
        smoke2.setSourcePosition(256f, 118f);
        // The smoke should look already-settled the moment it becomes visible (4.5 s from here, see the timers
        // below), so it needs 300 steps (5 s) of a head start. Doing that in one go here stalls a frame; instead
        // it is spread over the following frames (smokePrewarm below), invisible either way since smokeShown is
        // still false and nothing draws it yet.
        smokePrewarmRemaining = 300;
        smokeShown = false;
        Audio.play("safe_destroy");
        after(2.5f, new Runnable() { public void run() {
            Audio.play("cymbal_fade_in");
            after(2f, new Runnable() { public void run() { cymbalDone(); } });
        } });
    }

    private void cymbalDone() {
        safeLoop = Audio.playLoop("safe_cracking_loop");
        showOpenVault(true);
    }

    /** showOpenVault:withUnlockSequence: */
    private void showOpenVault(boolean withSequence) {
        openShown = true;
        innerOpen = true;
        propShown = withSequence;
        whiteLights = 1f;
        whiteLightsTarget = 1f;
        if (withSequence) {
            glowShown = true;
            smokeShown = true;
            roomHidden = true;
            roomAlpha = 1f;
            innerInteractive = false;
            startFlicker(0.7f, new Runnable() { public void run() { flickerFinished(); } });
        } else {
            unlockSequenceCompleted = true;
        }
    }

    private void startFlicker(float duration, Runnable done) {
        flickerLeft = duration;
        flickerTick = 0f;
        flickerDone = done;
    }

    /** flickerControllerDidFinish: the disarm level moves on and the vault is ready. */
    private void flickerFinished() {
        openFlickerHidden = false;
        Disarm d = Disarm.get();
        d.setDisarmLevelDirect(Disarm.SAFE_SEQUENCE);
        propShown = false;
        sequenceRunning = false;
        after(0.5f, new Runnable() { public void run() { updateCompletionLevel(); } });
        hub.enableAfterLevelUp();
        innerInteractive = true;
        Audio.play("finally_were_in");
        room.applyDisarmLevel();
        hub.updateBar(false, true);
    }

    // ---- inner vault -----------------------------------------------------------------------------------------------

    /** VaultInnerView updateCompletionLevel (driven by the locally saved collected flags). */
    private void updateCompletionLevel() {
        showShelf1 = showShelf2 = showShelf3 = showCertificate = showBriefcase = false;
        pedestalEnabled = false;
        pedestalX = 59f;
        Disarm d = Disarm.get();
        if (!d.getFlag("hasCollectedPrize")) { showBriefcase = true; return; }
        showShelf1 = true;
        int lvl = d.disarmLevel();
        if (lvl > 11 && lvl >= 13) {
            showCertificate = true;
            if (d.getFlag("hasCollectedPrize3")) showShelf3 = true;
        }
        if (d.getFlag("hasReceivedHint")) {
            if (!d.getFlag("hasCollectedPrize2")) {
                pedestalEnabled = true;
                int pos = Save.getInt("PedestalPosition", 0);
                pedestalX = pos == 1 ? 59f - 118f : pos == 2 ? 59f + 118f : 59f;
            } else {
                showShelf2 = true;
            }
        }
    }

    /** Tap in the room on the opened door: fly into the vault. */
    private void enterInner() {
        if (hintPending) return;
        if (transition || insideInner || !openShown || !innerInteractive) return;
        if (hub.drawersAreIn()) {
            // hideLevelSelectorAndSettingsAndEnterInnerVault: drawers drop away first
            transition = true;
            hub.slideDrawers(true, DRAWER_TIME);
            after(DRAWER_TIME, new Runnable() { public void run() { transition = false; flyIn(); } });
        } else {
            flyIn();
        }
    }

    private void flyIn() {
        transition = true;
        entering = true;
        doorZoomT = 0f;
        whiteLightsTarget = 0f;
        if (!unlockSequenceCompleted) {
            smokeRemoving = true;
            smokeRemoveT = 0f;
            if (smoke1 != null) { s1Life0 = smoke1.getLife(); s1Var0 = smoke1.getLifeVariance(); }
            if (smoke2 != null) { s2Life0 = smoke2.getLife(); s2Var0 = smoke2.getLifeVariance(); }
            Audio.stopLoop(safeLoop);
            safeLoop = null;
            Audio.play("outro");
            after(7.0f, new Runnable() { public void run() { restoreMusic(); } });
        }
        after(3.0f, new Runnable() { public void run() { startArrowFlashing(); } });
        after(ENTER_TIME, new Runnable() { public void run() { enterFinished(); } });
    }

    private void restoreMusic() {
        Audio.playMusic("safe_room2");
    }

    private void enterFinished() {
        if (!unlockSequenceCompleted) {
            // removeSmoke (ESSmokeRenderer_removeSmoke) runs on its own 250-tick/8.33 s timer, started back in
            // flyIn() - it is not tied to the 3 s enter transition and is left to finish on its own (the smoke
            // is not drawn anyway once insideInner is true, see drawOpenRoom's early return; it just keeps
            // quietly ageing out in the background instead of being yanked away the instant the screen cuts
            // over, which is what made it look like it was abruptly cut off rather than fading).
            glowShown = false;
            unlockSequenceCompleted = true;
        }
        transition = false;
        entering = false;
        doorZoomT = -1f;
        insideInner = true;
        openShown = false;
    }

    private void exitInner() {
        if (hintPending) return;
        if (transition || !insideInner) return;
        stopArrowFlashing();
        insideInner = false;
        openShown = true;
        transition = true;
        entering = false;
        doorZoomT = 0f;
        whiteLightsTarget = 1f;
        after(EXIT_TIME, new Runnable() { public void run() { exitFinished(); } });
    }

    private void exitFinished() {
        transition = false;
        doorZoomT = -1f;
        hub.slideDrawers(false, DRAWER_TIME);
    }

    private void startArrowFlashing() {
        arrowFlashing = 0f;
        arrowVisible = true;
        arrowBurstLeft = 0f;
        arrowPause = 0f;
        arrowInFlickerBurst = true;
        arrowBurstLeft = 0.8f;
    }

    private void stopArrowFlashing() { arrowFlashing = -1f; arrowVisible = true; }

    // ---- update ---------------------------------------------------------------------------------------------------------

    void update(float dt) {
        for (Timer t : new ArrayList<Timer>(timers)) {
            t.left -= dt;
            if (t.left <= 0f) { timers.remove(t); t.action.run(); }
        }
        // room dim pulse (alpha 1 -> dimmed, 0.05 s each way, 8 half-cycles, then it rests dimmed)
        if (dimT >= 0f) {
            dimT += dt;
            float cycle = dimT / DIM_PULSE;
            if (cycle >= 8f) { roomAlpha = 0f; dimT = -1f; }   // the room rests fully black: only the torch is drawn
            else {
                int k = (int) cycle;
                float f = cycle - k;
                float toward = (k % 2 == 0) ? f : 1f - f;
                roomAlpha = 1f + (DIM_ALPHA - 1f) * toward;
            }
        }
        // torch
        if (torchShown) {
            if (torchAlpha > 0f && flame != null) {
                flame.update(dt);
                sparks.update(dt);
            }
            if (cutT >= 0f && cutT < 1f) {
                cutT = Math.min(1f, cutT + dt / CUT_TIME);
                if (cutT >= 1f) {
                    sparksOut = true;
                    sparksOutT = 0f;
                    flameLife0 = flame.getLife(); flameVar0 = flame.getLifeVariance();
                    sparksLife0 = sparks.getLife(); sparksVar0 = sparks.getLifeVariance();
                    after(1.0f, new Runnable() { public void run() { cuttingFinished(); } });
                }
            }
            if (sparksOut) {
                sparksOutT = Math.min(1f, sparksOutT + dt / 1.0f);
                float k = 1f - sparksOutT;
                flame.setLife(flameLife0 * k); flame.setLifeVariance(flameVar0 * k);
                sparks.setLife(sparksLife0 * k); sparks.setLifeVariance(sparksVar0 * k);
            }
            if (torchFadeT >= 0f) {
                torchFadeT += dt;
                float k = Math.min(1f, torchFadeT / 0.2f);
                torchAlpha = 1f - k;
                roomAlpha = DIM_ALPHA * (1f - k);
                if (torchFadeT >= 0.2f) { torchFadeT = -1f; blackout(); }
            }
        }
        // smoke
        if (smokePrewarmRemaining > 0) {
            int steps = Math.min(smokePrewarmRemaining, SMOKE_PREWARM_PER_FRAME);
            for (int i = 0; i < steps; i++) { smoke1.update(1f / 60f); smoke2.update(1f / 60f); }
            smokePrewarmRemaining -= steps;
        }
        if (smoke1 != null) {
            if (smokeRemoving) {
                smokeRemoveT = Math.min(1f, smokeRemoveT + dt / (250f / 30f));   // removeSmoke: 250 steps at 1/30 s
                float k = 1f - smokeRemoveT;
                smoke1.setLife(s1Life0 * k); smoke1.setLifeVariance(s1Var0 * k);
                smoke2.setLife(s2Life0 * k); smoke2.setLifeVariance(s2Var0 * k);
            }
            smoke1.update(dt);
            smoke2.update(dt);
        }
        // whole-layer flicker
        if (flickerLeft >= 0f) {
            flickerLeft -= dt;
            flickerTick += dt;
            while (flickerTick >= 0.03f) {
                flickerTick -= 0.03f;
                openFlickerHidden = rnd.nextInt(2) == 0;
            }
            if (flickerLeft < 0f) {
                flickerLeft = -1f;
                openFlickerHidden = false;
                Runnable r = flickerDone;
                flickerDone = null;
                if (r != null) r.run();
            }
        }
        // door zoom
        if (doorZoomT >= 0f) doorZoomT += dt;
        // lights
        if (whiteLights != whiteLightsTarget) {
            float step = dt / 0.5f;
            whiteLights = whiteLights < whiteLightsTarget ? Math.min(whiteLightsTarget, whiteLights + step)
                    : Math.max(whiteLightsTarget, whiteLights - step);
        }
        // arrow flashing: bursts of random flicker every ~2.3 s
        if (arrowFlashing >= 0f) {
            arrowFlashing += dt;
            if (arrowInFlickerBurst) {
                arrowTick += dt;
                while (arrowTick >= 0.06f) { arrowTick -= 0.06f; arrowVisible = rnd.nextInt(2) == 0; }
                arrowBurstLeft -= dt;
                if (arrowBurstLeft <= 0f) { arrowInFlickerBurst = false; arrowVisible = true; arrowPause = 5.0f; }
            } else {
                arrowPause -= dt;
                if (arrowPause <= 0f) { arrowInFlickerBurst = true; arrowBurstLeft = 0.8f; }
            }
        }
        // pedestal slide
        if (pedestalT >= 0f) {
            pedestalT = Math.min(1f, pedestalT + dt / 0.5f);
            float e = 1f - (1f - pedestalT) * (1f - pedestalT);
            pedestalX = pedestalFrom + (pedestalTo - pedestalFrom) * e;
            if (pedestalT >= 1f) pedestalT = -1f;
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------------

    /** Drawn by the safe room instead of the room whenever the vault covers it. */
    void drawOpenRoom(Gfx g) {
        g.group("Vault: black backdrop");
        g.setColor(0xFF000000);
        g.fillRect(0, 0, 320, 480);
        if (insideInner) {
            g.group("Vault: inside");
            drawInner(g);
            return;
        }
        // vaultInnerViewTapped: fly-in = door container scales to 3.15 and drops 315 pt over 3.0 s (linear) while the inner
        // view grows over 2.15 s and moves down 95 pt over 2.35 s (both ease-out); fly-out = inner view back over 1.4 s
        // (ease in/out), door back over 1.0 s (ease-in)
        float pDoor = 0f, pScale = 0f, pPos = 0f;
        if (doorZoomT >= 0f) {
            float t = doorZoomT;
            if (entering) {
                pDoor = Math.min(1f, t / 3.0f);
                pScale = easeOut(Math.min(1f, t / 2.15f));
                pPos = easeOut(Math.min(1f, t / 2.35f));
            } else {
                pDoor = 1f - easeIn(Math.min(1f, t / 1.0f));
                pScale = pPos = 1f - easeInOut(Math.min(1f, t / 1.4f));
            }
        }
        float zoom = 1f + (DOOR_ZOOM - 1f) * pDoor;
        // the door container is scaled about its centre (160, 240) and shifted down by 315 pt * progress
        float doorCy = (240f + 315f * pDoor) + (HOLE_Y - 240f) * zoom;
        // vaultInnerViewTapped inserts the door/inner-view container above the torch view (which carries the
        // smoke) right as the fly starts, so while actually flying in the smoke is drawn first here and gets
        // covered as the zooming door grows to fill the screen, instead of sitting on top of it for the whole
        // pan. The rest of the time (including flying back out) vaultOpen.png is a full-screen background
        // image, not a small door cutout, so drawing smoke first then would just hide it completely instead -
        // it stays drawn last, on top, exactly as before.
        boolean smokeBehindDoor = transition && entering;
        if (smokeBehindDoor) { g.group("Vault: smoke"); if (smokeShown && smoke1 != null && !openFlickerHidden) drawSmoke(g); }
        // the inner view, seen through (or flying out of) the hole
        g.group("Vault: inside seen through the door");
        if (innerOpen && !openFlickerHidden) {
            float s = INNER_SMALL + (1f - INNER_SMALL) * pScale;
            if (hub.roomFadedIn()) {
                Gfx gi = g.create();
                gi.translate(160, HOLE_Y + 95f * pPos);
                gi.scale(s, s);
                gi.translate(-160, -240);
                drawInner(gi);
                gi.dispose();
            } else {
                // while the room fades in the inside must only show through the door's opening, not as its own rectangle
                if (layer == null) {
                    layer = Host.get().createImage(320, 480);
                    layerGfx = Gfx.of(layer);
                }
                layer.clear(0);
                Gfx gi = layerGfx.create();
                gi.translate(160, HOLE_Y + 95f * pPos);
                gi.scale(s, s);
                gi.translate(-160, -240);
                drawInner(gi);
                gi.dispose();
                Gfx gm = layerGfx.create();
                gm.setBlend(Gfx.DST_OUT);
                gm.translate(160, doorCy);
                gm.scale(zoom, zoom);
                gm.translate(-160, -HOLE_Y);
                Assets.image("vaultOpen.png").draw(gm, 0, 0);
                gm.dispose();
                g.drawImage(layer, 0, 0);
            }
        }
        g.group("Vault: open door and cameras");
        if (!openFlickerHidden) {
            Gfx gd = g.create();
            gd.translate(160, doorCy);
            gd.scale(zoom, zoom);
            gd.translate(-160, -HOLE_Y);
            Assets.image("vaultOpen.png").draw(gd, 0, 0);
            room.drawCamerasOnly(gd);
            if (glowShown) Assets.image("vaultOpenGlow.png").draw(gd, 0, 0);
            gd.dispose();
            if (whiteLights > 0f) Assets.image("lights_white.png").drawAlpha(g, 14, 0, whiteLights);
        }
        if (!smokeBehindDoor) { g.group("Vault: smoke"); if (smokeShown && smoke1 != null && !openFlickerHidden) drawSmoke(g); }
    }

    private static float easeOut(float u) { return heist.engine.Ease.out(u); }
    private static float easeIn(float u) { return heist.engine.Ease.in(u); }
    private static float easeInOut(float u) { return heist.engine.Ease.inOut(u); }

    private boolean flickering() { return flickerLeft >= 0f; }

    private void drawSmoke(Gfx g) {
        // positions are in the emitter files' y-up space: the 480 pt tall view maps them directly
        smoke1.draw(g, 57f, 480f - 78f);
        smoke2.draw(g, 256f, 480f - 118f);
    }

    /**
     * The cutting torch (sparks and flame travelling around the door). The assembly's "animateArcDegrees" /
     * growing vaultCut.png reveal (what this used to draw here) only runs behind a device-model check for the
     * literal string "iPhone Simulator" ({@code TorchViewController_initWithFrame} /
     * {@code TorchViewController_beginCutting}): it is a simulator-only fallback that never ran on real
     * hardware, so it is not reproduced here - on a real device there is only the moving torch, no separate
     * glowing trail.
     */
    void drawTorch(Gfx g) {
        if (!torchShown || torchAlpha <= 0f) return;
        Gfx gt = g.create();
        if (torchAlpha < 1f) gt.setAlpha(Math.max(0f, torchAlpha));
        if (cutT >= 0f && flame != null) {
            double a = Math.toRadians(138.0 + (400.0 - 138.0) * Math.min(1f, cutT));
            float tx = (float) (TORCH_CX + TORCH_R * Math.cos(a)) - 3.5f;
            float ty = (float) (TORCH_CY + TORCH_R * Math.sin(a)) - 94f;
            flame.draw(gt, tx, ty);
            sparks.draw(gt, tx, ty);
        }
        gt.dispose();
    }

    /** Draws the inner vault view in its own 320x480 space. */
    private void drawInner(Gfx g) {
        Assets.image(Assets.exists("vaultBackground.jpg") ? "vaultBackground.jpg" : "vaultBackground_568.jpg").draw(g, -80, 0);
        // outside the vault room the arrow stays unlit; only inside does it flash blue (arrowVisible toggles)
        // the original's addSubview order: certificate, back arrow, shelves, then the pedestal (which carries the briefcase)
        if (showCertificate) Assets.image("certificate_vault.jpg").draw(g, 119, 136);
        if (!insideInner || arrowVisible) Assets.image("arrow.jpg").draw(g, 77, 358);
        if (showShelf1) Assets.image("prize1.jpg").draw(g, 43, 15);
        if (showShelf2) Assets.image("prize2.jpg").draw(g, 121, 35);
        if (showShelf3) Assets.image("prize3.jpg").draw(g, 200, 35.5f);
        Assets.image("pedestal.png").draw(g, pedestalX, 244);   // hideAllPrizes only disables it: it is always in the safe
        if (propShown) Assets.image("prize_prop.jpg").draw(g, 114, 199);
        if (showBriefcase) Assets.image("briefcase.png").draw(g, BRIEFCASE_X, BRIEFCASE_Y);
    }

    // ---- touch ------------------------------------------------------------------------------------------------------------------

    /** Returns true if the vault consumed the press. */
    boolean pointerPressed(float x, float y) {
        if (insideInner) {
            if (pedestalEnabled && x >= pedestalX && x < pedestalX + 199 && y >= 244 && y < 244 + 115) {
                pedestalDragging = true;
                pedestalDragOff = x - pedestalX;
                pedestalT = -1f;
            }
            return true;
        }
        return sequenceRunning || transition;
    }

    boolean pointerDragged(float x, float y) {
        if (!insideInner) return sequenceRunning || transition;
        if (pedestalDragging) {
            pedestalX = Math.max(-59f, Math.min(177f, x - pedestalDragOff));
        }
        return true;
    }

    boolean pointerReleased(float x, float y) {
        if (insideInner) {
            if (pedestalDragging) {
                pedestalDragging = false;
                dropPedestal();
                return true;
            }
            if (showBriefcase && x >= BRIEFCASE_X && x < BRIEFCASE_X + 112 && y >= BRIEFCASE_Y && y < BRIEFCASE_Y + 97) {
                claimPrize("eets");
                return true;
            }
            // the floor spot revealed once the pedestal has been slid aside
            if (pedestalEnabled && x >= 111 && x < 111 + 96 && y >= 299 && y < 299 + 49) {
                claimPrize("prize3");
                return true;
            }
            if (x >= 77 && x < 77 + 160 && y >= 358 && y < 358 + 119) exitInner();
            return true;
        }
        if (sequenceRunning || transition) return true;
        if (isOpenInRoom() && innerInteractive) {
            // the scaled inner view sits behind the hole in the door art
            float cx = 160f, cy = HOLE_Y, w = 320 * INNER_SMALL, h = 480 * INNER_SMALL;
            if (x >= cx - w / 2 && x < cx + w / 2 && y >= cy - h / 2 && y < cy + h / 2) {
                enterInner();
                return true;
            }
        }
        return false;
    }

    private static final float BRIEFCASE_X = 103f, BRIEFCASE_Y = 163f;   // VaultInnerView initWithFrame: the briefcase frame

    /**
     * prizeClaimControllerDidFinish with the web page left out: the claim always succeeds right away, then the rest of the
     * original flow runs (chime, shelf update, the video call after the first prize, the hint call that unlocks the pedestal).
     */
    private void claimPrize(String name) {
        final Disarm d = Disarm.get();
        if (name.equals("eets") && !d.getFlag("hasCollectedPrize")) {
            d.setFlag("hasCollectedPrize", true);
            Audio.play("achievementChime");
            updateCompletionLevel();
            after(6.0f, new Runnable() { public void run() {
                // displayVideoCallDelayed
                hub.showCallThen("secureCallVideo", null);
            } });
        } else if (name.equals("prize3") && d.getFlag("hasReceivedHint") && !d.getFlag("hasCollectedPrize2")) {
            d.setFlag("hasCollectedPrize2", true);
            Audio.play("achievementChime");
            updateCompletionLevel();
        }
    }

    /** Re-reads the saved prize flags after something outside the vault changed them (the hint call). */
    void refresh() { updateCompletionLevel(); }

    private void dropPedestal() {
        float[] stops = { 59f - 118f, 59f, 59f + 118f };
        int best = 1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            float d = Math.abs(pedestalX - stops[i]);
            if (d < bd) { bd = d; best = i; }
        }
        Save.put("PedestalPosition", best == 1 ? 0 : best == 0 ? 1 : 2);
        pedestalFrom = pedestalX;
        pedestalTo = stops[best];
        pedestalT = 0f;
    }
}
