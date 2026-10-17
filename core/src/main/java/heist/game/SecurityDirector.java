package heist.game;

import heist.engine.Fnt;
import heist.engine.Gfx;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.Save;
import heist.engine.Sprite;
import heist.platform.CameraPreview;
import heist.platform.Host;

/**
 * Orchestrates everything that happens in the safe room besides picking levels: the first-launch call,
 * the progress bar level-up flicker, the padlock/unlock choreography (voice lines, lasers, cameras,
 * electricity, bars, lights), secure phone calls, and the interactive bits (cameras follow your finger,
 * electricity sparks on the bars, red alert lights).
 */
final class SecurityDirector {
    private final HubScene hub;
    private final SafeRoomView room;
    private final Random rnd = new Random();

    private static final class Timer {
        float left;
        final Runnable action;
        Timer(float left, Runnable action) { this.left = left; this.action = action; }
    }

    private final List<Timer> timers = new ArrayList<Timer>();

    private void after(float seconds, Runnable r) { timers.add(new Timer(seconds, r)); }

    private boolean sequenceCompleted;      // securitySequenceCompleted: touches reach the safe room
    private boolean animationsActive = true;
    private boolean electricityActive;
    private boolean lasersFlicker;
    private float electricityTimer;
    private float redLightsTimer = -1f;
    private float barsAnim = -1f;           // bars sliding in/out: progress 0..1
    private boolean barsToLocked;
    private float lightsAnim = -1f;         // white->green cross fade
    private boolean padlockFlicker;
    private int padlockIndex;
    private boolean padlockShown = true;
    private float padlockT;
    private int padlockPhase;               // 0 red flicker, 1 green flicker
    private float flickerTick;
    private boolean firstLaunchSequence;

    // sparks
    private static final float[][] SPARK_RECTS = {
        { 60, 67, 46, 87 }, { 93, 39, 46, 87 }, { 130, 28, 46, 87 }, { 166, 28, 46, 87 }, { 204, 36, 46, 87 }, { 240, 59, 46, 87 }
    };
    private static final class Spark {
        float x, y, t, duration = 1f;
        boolean flipH, flipV, tap;
        int frames = 10;
    }
    private final List<Spark> sparks = new ArrayList<Spark>();
    private float tapSoundStop = -1f;

    // lasers
    private float laserFlickerT = -1f;
    private float lasersFade = -1f;
    private float flickerAcc;

    // cameras
    private boolean camerasActive, camerasUnlocking;
    private float camLightBlink;
    private float dragLightsTimer = -1f;
    private boolean touching;
    /** The current touch began on one of the cameras: only then do their red status lights show. */
    private boolean touchOnCamera;

    // secure call
    private CallScreen call;
    private float callZoom = 1f;            // safe-room scale while a call is up
    private float callZoomFrom = 1f, callZoomTo = 1f, callZoomT = 1f, callZoomDur = 0.5f;
    private String pendingCall;             // delayed until the level list is back (displaySecureCallIsDelayed)
    private boolean callIsIntroduction;
    private Runnable afterCall;             // runs once the call screen is gone again

    /** Release camera and any other call resources. */
    void shutdown() {
        if (call != null) call.dispose();
    }

    SecurityDirector(HubScene hub, SafeRoomView room) {
        this.hub = hub;
        this.room = room;
    }


    void onHubShown() {
        Disarm d = Disarm.get();
        camerasActive = d.disarmLevel() < Disarm.CAMERAS;
        electricityActive = d.disarmLevel() < Disarm.ELECTRICITY && room.barsLocked;
        room.cameraTracking = false;
        boolean introDone = Save.getBool("displaySecureCallIntroductionCompleted", false);
        // flag missing in older saves → treat as played when any progress exists
        boolean powerUpPlayed = Save.getBool("animationSecuritySequenceCompleted", introDone || d.disarmLevel() > 0);
        if (!powerUpPlayed) {
            firstLaunchSequence = true;
            room.lasersAlpha = 0f;
            room.barsShift = -400f;
            room.whiteLights = 0f;
            after(0.25f, new Runnable() { public void run() { lightsOn(); } });
            after(0.25f + 1.0f, new Runnable() { public void run() { startLasers(); } });
            after(0.25f + 1.35f, new Runnable() { public void run() { slideBarsIn(true); } });
            after(0.25f + 1.35f + 0.85f, new Runnable() { public void run() {
                sequenceCompleted = true;
                Save.put("animationSecuritySequenceCompleted", true);
                startElectricity();
                after(6.0f, new Runnable() { public void run() {
                    callIsIntroduction = true;
                    showCall(Disarm.get().secureCallFor(0), true);
                } });
            } });
        } else if (!introDone && !d.hasUnlockedSafe()) {
            // power-up played but intro call was never heard; ring again after fade-in
            after(0.6f, new Runnable() { public void run() {
                sequenceCompleted = true;
                startElectricity();
            } });
            after(1.0f + 6.0f, new Runnable() { public void run() {
                callIsIntroduction = true;
                showCall(Disarm.get().secureCallFor(0), true);
            } });
        } else {
            after(0.6f, new Runnable() { public void run() {
                sequenceCompleted = true;
                startElectricity();
                hub.showDrawers();
                if (safeSequencePending()) hub.vault().beginUnlockSequence();
            } });
        }
        sparks.clear();
    }

    private void slideBarsIn(boolean sound) {
        room.barsLocked = true;
        barsToLocked = true;
        barsAnim = 0f;
        if (sound && Audio.isSfxOn()) Audio.play("bars_slide");
    }

    private void lightsOn() { lightsAnim = 0f; }

    private void startLasers() {
        room.lasersOn = true;
        room.lasersAlpha = 1f;               // floorLasers: full opacity at once, the 0.35 s flicker does the rest
        lasersFlickerSequence();
        if (Audio.isSfxOn()) Audio.play("floor_lasers_no_prefix");
    }

    private void lasersFlickerSequence() { laserFlickerT = 0f; }

    private void startElectricity() {
        if (Disarm.get().disarmLevel() < Disarm.ELECTRICITY && room.barsLocked) {
            electricityActive = true;
            electricityTimer = 0.5f + (rnd.nextInt(5000) / 1000f);
        }
    }


    /** Pauses electricity (and its sound) while a puzzle is open. */
    void setAnimationsActive(boolean on) {
        animationsActive = on;
        if (!on) sparks.clear();
    }

    boolean blocksInput() { return call != null || callZoomT < 1f; }

    /** True once the room has powered up and nothing is playing (the debug menu waits for this). */
    boolean isReady() { return sequenceCompleted && !isBusy(); }

    /** True while any scripted sequence, call or room animation is still running. */
    boolean isBusy() {
        return !timers.isEmpty() || call != null || callZoomT < 1f || padlockFlicker || lasersFading || barsAnim >= 0f
                || lightsAnim >= 0f || greenFade >= 0f || lasersFade >= 0f || laserFlickerT >= 0f || redFade != 0
                || redLightsTimer >= 0f || camerasUnlocking;
    }

    /** Debug: plays the bar-full level-up exactly as after a solved puzzle. */

    /** True when the bars are gone but the safe-cutting sequence has not played yet (quit part-way through). */
    boolean safeSequencePending() { return Disarm.get().disarmLevel() == Disarm.BARS && Disarm.get().levelUpCount() >= 4; }

    // ---- update ----------------------------------------------------------------------------------------------

    void update(float dt) {
        for (Iterator<Timer> it = new ArrayList<Timer>(timers).iterator(); it.hasNext();) {
            Timer t = it.next();
            t.left -= dt;
            if (t.left <= 0f) { timers.remove(t); t.action.run(); }
        }
        // bars slide
        if (barsAnim >= 0f) {
            barsAnim += dt / 0.85f;
            float k = Math.min(1f, barsAnim);
            float e = 1f - (1f - k) * (1f - k);
            room.barsShift = barsToLocked ? -400f * (1f - e) : -400f * e;
            if (barsAnim >= 1f) {
                barsAnim = -1f;
                room.barsShift = barsToLocked ? 0f : -400f;
                if (!barsToLocked) room.barsLocked = false;
                barsFinished();
            }
        }
        // lights cross fade
        if (lightsAnim >= 0f) {
            lightsAnim += dt / 1.0f;          // securityLightsLocked: 1.0 s
            room.whiteLights = Math.min(1f, lightsAnim);
            if (lightsAnim >= 1f) lightsAnim = -1f;
        }
        // lasers
        if (lasersFade >= 0f) {
            lasersFade += dt / 1.875f;
            room.lasersAlpha = Math.min(1f, lasersFade);
            if (lasersFade >= 1f) lasersFade = -1f;
        }
        if (laserFlickerT >= 0f) {
            laserFlickerT += dt;
            flickerAcc += dt;
            while (flickerAcc >= 0.01f) { flickerAcc -= 0.01f; if (laserFlickerT < 0.35f) room.lasersAlpha = rnd.nextBoolean() ? 1f : 0.2f; }
            if (laserFlickerT >= 0.35f) { laserFlickerT = -1f; if (lasersFade < 0f && !lasersFading) room.lasersAlpha = 1f; }
        }
        if (lasersFading) {
            lasersFadeOutT += dt / 2f;
            room.lasersAlpha = Math.max(0f, 1f - lasersFadeOutT);
            if (lasersFadeOutT >= 1f) { lasersFading = false; room.lasersOn = false; room.lasersAlpha = 0f; lasersGone(); }
        }
        // lights green
        if (greenFade >= 0f) {
            greenFade += dt / 1.0f;
            float k = Math.min(1f, greenFade);
            room.greenAlpha = k;
            room.whiteLights = 1f - k;
            room.redAlpha = Math.min(room.redAlpha, 1f - k);
            if (greenFade >= 1f) greenFade = -1f;
        }
        // red alert lights
        if (redFade != 0) {
            room.redAlpha = Math.max(0f, Math.min(1f, room.redAlpha + redFade * dt / 1.0f));
            room.whiteLights = 1f - room.redAlpha;
            if (room.redAlpha <= 0f || room.redAlpha >= 1f) redFade = 0;
        }
        if (redLightsTimer >= 0f) {
            redLightsTimer -= dt;
            if (redLightsTimer < 0f) { redLightsTimer = -1f; redFade = -1; }
        }
        // electricity
        if (electricityActive && animationsActive && sequenceCompleted) {
            electricityTimer -= dt;
            if (electricityTimer <= 0f) {
                addRandomSpark();
                electricityTimer = 0.5f + (rnd.nextInt(5000) / 1000f);
            }
        }
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext();) {
            Spark s = it.next();
            s.t += dt;
            if (s.t >= s.duration) it.remove();
        }
        if (tapSoundStop >= 0f) { tapSoundStop -= dt; if (tapSoundStop < 0f) tapSoundStop = -1f; }
        // cameras
        room.cameraTracking = touching && camerasActive;
        // the lights blink (0.5 s, camLightToggle) while a touch that began on a camera is held, and are off otherwise
        room.cameraLights = touching && touchOnCamera && camerasActive && ((int) (camLightBlink / 0.5f)) % 2 == 0;
        if (touching && camerasActive) {
            camLightBlink += dt;
            if (dragLightsTimer >= 0f) {
                dragLightsTimer -= dt;
                if (dragLightsTimer < 0f) { engageRedLights(); dragLightsTimer = 3.0f; }
            }
        }
        // padlock flicker
        if (padlockFlicker) updatePadlock(dt);
        // call zoom
        if (callZoomT < 1f) {
            callZoomT = Math.min(1f, callZoomT + dt / callZoomDur);
            float k = callZoomT;
            float e = 1f - (1f - k) * (1f - k);
            callZoom = callZoomFrom + (callZoomTo - callZoomFrom) * e;
        }
        if (call != null) call.update(dt);
    }

    // hooks completed later in the file
    private boolean lasersFading;
    private float lasersFadeOutT;
    private float greenFade = -1f;
    private int redFade;

    private void barsFinished() {
        if (!barsToLocked) {
            // bars dropped away: finish the safe unlock sequence
            if (Audio.isSfxOn()) { }
            afterBarsUnlocked();
        } else if (firstLaunchSequence) {
            startElectricity();
        }
    }

    // ---- electricity -----------------------------------------------------------------------------------------------

    private void addRandomSpark() {
        float[] r = SPARK_RECTS[rnd.nextInt(SPARK_RECTS.length - 1)];
        Spark s = new Spark();
        s.x = r[0];
        int range = Math.max(1, (int) (245f - r[3] - r[1]));
        s.y = r[1] + rnd.nextInt(range);
        s.flipV = rnd.nextInt(3) == 1;
        s.flipH = rnd.nextBoolean();
        s.duration = 1f;
        sparks.add(s);
        if (Audio.isSfxOn() && soundFx) Audio.play("electrical_" + (1 + rnd.nextInt(6)));
    }

    private boolean soundFx = true;

    // ---- level completion -------------------------------------------------------------------------------------------

    /** The puzzle is back in its thumbnail: show the badge, grow the bar, then run any level-up. */
    void debugLevelFilled(Disarm.Result r) { afterLevelCompleted(r, null); }

    void afterLevelCompleted(Disarm.Result r, LevelStrip strip) {
        if (r == null || !r.firstCompletion) { hub.updateBar(false, false); return; }
        Disarm d = Disarm.get();
        final boolean levelUp = d.achievedLevelUp();
        // badge fades in over 0.25s, glow runs, then the bar fills over 2s
        after(0.25f, new Runnable() { public void run() {
            hub.updateBar(true, false);
        } });
        if (levelUp) {
            after(0.25f + 2.0f, new Runnable() { public void run() { levelUpSequence(); } });
        } else if (r.promoted) {
            final String file = d.secureCallFor(d.disarmLevel());
            if (file != null) pendingCall = file;
            after(1.0f, new Runnable() { public void run() { flushPendingCall(); } });
        }
    }

    private void flushPendingCall() {
        if (pendingCall == null) return;
        String f = pendingCall;
        pendingCall = null;
        showCall(f, false);
    }

    // ---- level up sequence ---------------------------------------------------------------------------------------------

    private int ups() { return Disarm.get().levelUpCount(); }

    private void levelUpSequence() {
        Disarm d = Disarm.get();
        soundFx = false;                         // setSoundFXEnabled:0
        if (Audio.isSfxOn()) Audio.play("level_up_progress_bar");
        float flick = 1.0f;
        if (!d.has100PercentCompleted()) {
            flick += 0.5f;
        } else {
            flick = 2.5f;
        }
        after(flick, new Runnable() { public void run() { beginPadlockUnlockSequence(); } });
    }

    private void beginPadlockUnlockSequence() {
        final int n = ups();
        Disarm d = Disarm.get();
        if (n > 4 || n < 1) {
            if (d.has100PercentCompleted()) { showGoldProgressBar(); return; }
        }
        beginPadlockFlicker(Math.max(0, Math.min(3, n - 1)));
        if (Audio.isSfxOn()) Audio.play("padlock_unlock");
        if (n != 4) {
            after(0.84375f, new Runnable() { public void run() { hub.scrollActiveToNextLockedLevel(); } });
            after(2.0f, new Runnable() { public void run() {
                hub.unlockLevelsAfterDisarm();
                after(3.25f, new Runnable() { public void run() { padlockUnlockSequenceDidFinish(); } });
            } });
            after(4.5f, new Runnable() { public void run() { hub.returnActiveToPreviousLevel(); } });
        } else {
            // red flicker 1.0 s, green flicker 1.0 s (stopPadlockFlickerGreen), then 1.0 s more
            after(3.0f, new Runnable() { public void run() { padlockUnlockSequenceDidFinish(); } });
        }
    }

    private void padlockUnlockSequenceDidFinish() {
        int n = ups();
        String voice = null;
        if (n == 1) { hub.hideLevelSelectorWithButton(); voice = "laser_defences"; }
        else if (n == 2) voice = "security_cameras_disabled";
        else if (n == 3) voice = "electric_bars_disabled";
        else if (n == 4) { securityComponentVoiceDidFinishPlaying(); return; }
        if (voice != null && Audio.isSfxOn()) Audio.play(voice);
        after(3.0f, new Runnable() { public void run() { securityComponentVoiceDidFinishPlaying(); } });
    }

    private void securityComponentVoiceDidFinishPlaying() {
        int n = ups();
        String fx = n == 1 ? "level_reset_5" : n == 2 ? "wrong_wrong_wrong" : n == 3 ? "shake_wrench_extra_long" : null;
        if (fx != null && Audio.isSfxOn()) Audio.play(fx);
        soundFx = true;
        after(1.75f, new Runnable() { public void run() { unlockSecurityForCurrentDisarmLevel(); } });
    }

    // ---- padlocks --------------------------------------------------------------------------------------------------------

    private void beginPadlockFlicker(int index) {
        padlockIndex = index;
        padlockFlicker = true;
        padlockPhase = 0;
        padlockT = 0f;
        flickerTick = 0f;
        padlockShown = true;
        room.padlockHidden[index] = false;
    }

    private void updatePadlock(float dt) {
        padlockT += dt;
        flickerTick += dt;
        while (flickerTick >= 0.02f) { flickerTick -= 0.02f; padlockShown = rnd.nextInt(2) != 1; }
        room.padlockHidden[padlockIndex] = !padlockShown;
        if (padlockT >= 1.0f) {
            if (padlockPhase == 0) {
                // first flicker ends: padlock unlocks, then flickers green
                room.padlockUnlocked[padlockIndex] = true;
                padlockPhase = 1;
                padlockT = 0f;
            } else {
                padlockFlicker = false;
                padlockShown = true;
                room.padlockHidden[padlockIndex] = false;
            }
        }
    }

    // ---- component unlocks ---------------------------------------------------------------------------------------------------

    private void unlockSecurityForCurrentDisarmLevel() {
        int n = ups();
        Disarm d = Disarm.get();
        if (d.disarmLevel() == 0) return;
        switch (n) {
            case 1:
                unlockFloorLasers();
                break;
            case 2:
                unlockCameras();
                break;
            case 3:
                unlockElectricity();
                break;
            case 4:
                greenFade = 0f;
                lightsGreen = true;
                unlockBars();
                break;
            default:
                break;
        }
    }

    private boolean lightsGreen;

    private void unlockFloorLasers() {
        laserFlickerT = 0f;
        lasersFading = true;
        lasersFadeOutT = 0f;
    }

    private void lasersGone() {
        hub.showLastLevelSelectorWithButton();
        callAfterUnlock(Disarm.LASERS);
    }

    private void unlockCameras() {
        camerasActive = false;
        camerasUnlocking = true;
        room.cameraTargetX = 160f;
        room.cameraTargetY = Float.MAX_VALUE;   // unlockCameras: camerasPointAt (view centre x, FLT_MAX) = straight down
        room.cameraTracking = true;
        after(1.5f, new Runnable() { public void run() {
            camerasUnlocking = false;
            room.cameraTracking = false;
            room.camerasOn = false;
            callAfterUnlock(Disarm.CAMERAS);
        } });
    }

    private void unlockElectricity() {
        electricityActive = false;
        sparks.clear();
        callAfterUnlock(Disarm.ELECTRICITY);
    }

    private void unlockBars() {
        barsToLocked = false;
        barsAnim = 0f;
        if (Audio.isSfxOn()) Audio.play("security_bars_open");
    }

    private void afterBarsUnlocked() {
        hub.vaultUnlocked();
    }

    /** Called once the bars are gone and the lights are green: the safe can be opened. */
    void afterSafeUnlocked() {
        hub.vault().beginUnlockSequence();
    }

    private void callAfterUnlock(int securityLevel) {
        String f = Disarm.get().secureCallFor(securityLevel);
        if (f != null) showCall(f, false);
        else hub.enableAfterLevelUp();
    }

    private void showGoldProgressBar() {
        hub.bar().setTicks(0);
        hub.bar().drawGolden();
        hub.vault().refresh();                 // RefreshUnlockVaultController
        hub.enableAfterLevelUp();              // setAchievedLevelUp:0
        Audio.playMusicFadeIn("safe_room2");   // startSafeRoomMusic: fadeInPlayer
    }

    // ---- touches -------------------------------------------------------------------------------------------------------------

    /** Safe-room interaction: x, y in screen coordinates. */
    void touchBegan(float x, float y) {
        if (!sequenceCompleted) return;
        touching = true;
        camLightBlink = 0f;
        touchOnCamera = camerasActive && room.hitsCamera(x, y);
        float bx = x - 60, by = y - (23 + room.barsShift);
        boolean onBars = room.barsLocked && bx >= 0 && bx < 193 && by >= 0 && by < 393;
        if (electricityActive && onBars && by <= 227) {
            Spark s = new Spark();
            s.tap = true;
            s.frames = 5;
            s.x = x;
            s.y = y;
            s.duration = 1f;
            sparks.add(s);
            if (Audio.isSfxOn()) { Audio.play("electrical_tap"); tapSoundStop = 0.25f; }
        } else if (!electricityActive && onBars && by <= 227) {
            if (Audio.isSfxOn()) Audio.play("security_bars_clink");
        }
        if (camerasActive) {
            room.cameraTargetX = x;
            room.cameraTargetY = y;
            dragLightsTimer = 2.0f;
        }
    }

    void touchMoved(float x, float y) {
        if (!sequenceCompleted || !camerasActive) return;
        room.cameraTargetX = x;
        room.cameraTargetY = y;
    }

    void touchEnded(float x, float y) {
        if (!sequenceCompleted) { touching = false; touchOnCamera = false; return; }
        touching = false;
        touchOnCamera = false;
        float bx = x - 60, by = y - (23 + room.barsShift);
        boolean onBars = room.barsLocked && bx >= 0 && bx < 193 && by >= 0 && by < 393 && by <= 227;
        boolean onLasers = room.lasersOn && ((x >= 5 && x < 320 && y >= 262 && y < 444) || (x >= -24 && x < 319 && y >= 249 && y < 447));
        if ((electricityActive && onBars) || (!electricityActive && onLasers) || (electricityActive && !onBars && onLasers)) {
            engageRedLights();
        }
        dragLightsTimer = -1f;
    }

    private void engageRedLights() {
        if (redLightsTimer < 0f && room.redAlpha == 0f && !lightsGreen && room.greenAlpha == 0f) {
            redFade = 1;
            redLightsTimer = 1.15f;
        }
    }

    // ---- secure calls ------------------------------------------------------------------------------------------------------------

    /** Brings up the phone call screen with the given call file ("secureCall_2.0"). */
    void showCall(String file, boolean introduction) {
        if (file == null || call != null) return;
        callIsIntroduction = introduction;
        Audio.fadeOutMusic();                  // displaySecureCallDelayed: fadeOutPlayer on the playing music
        Runnable end = new Runnable() { public void run() { hideCall(); } };
        call = file.equals("secureCallVideo") ? new VideoCallFlow(end) : new SecureCallView(file, introduction, end);
        // showSecureCall: the safe room shrinks to a hundredth over 0.5 s while the call fades in (the video call leaves
        // the room alone and only fades the open vault)
        callZoomFrom = 1f; callZoomTo = call instanceof VideoCallFlow ? 1f : 0.01f; callZoomT = 0f; callZoomDur = 0.5f;
        animationsActive = false;
    }

    /** Shows a call and runs {@code then} after it has been hidden again. */
    /** The hint call after the five minute break: rings 6.0 s after launch (triggerHintCall), then the pedestal shows up. */
    void ringHintAfterLaunch() {
        after(6.0f, new Runnable() { public void run() { hub.showHintCall(); } });
    }

    void showCallThen(String file, Runnable then) {
        if (call != null) return;
        afterCall = then;
        showCall(file, false);
    }

    /** Debug: jump straight to the SecureVideoCallView (the FaceTime screen) without the prelude audio call. */
    void showVideoCallDirectly() {
        if (call != null) return;
        Audio.fadeOutMusic();
        Runnable end = new Runnable() { public void run() { hideCall(); } };
        call = new SecureVideoCallView(end);
        callZoomFrom = 1f; callZoomTo = 1f; callZoomT = 0f; callZoomDur = 0.5f;
        animationsActive = false;
    }

    private void hideCall() {
        final boolean wasVideo = call instanceof VideoCallFlow;
        if (wasVideo) {
            // hideSecureCallAnimationDidFinish: 0.25 s later the "Backstabbed" flag is stored locally
            after(0.25f, new Runnable() { public void run() { Save.put(HintCall.BACKSTABBED, true); } });
        }
        // hideSecureCall: 0.5 s (0.25 s inside the inner vault); the video call already shrank away by itself
        final float dur = wasVideo ? 0f : (hub.vault().isInside() ? 0.25f : 0.5f);
        if (!wasVideo) {
            callZoomFrom = callZoom; callZoomTo = 1f; callZoomT = 0f; callZoomDur = dur;
            call.beginHide(dur);
        }
        animationsActive = true;
        final boolean intro = callIsIntroduction;
        after(dur, new Runnable() { public void run() {
            call = null;
            Audio.playMusic("safe_room2");
            if (intro) { callIsIntroduction = false; hub.showDrawers(); }
            hub.enableAfterLevelUp();
            Runnable r = afterCall;
            afterCall = null;
            if (r != null) r.run();
        } });
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------------------------

    /** Draws the electricity sparks between the room layers and the hub drawers. */
    void drawBehindDrawers(Gfx g) {
        for (Spark s : sparks) drawSpark(g, s);
        if (touching && camerasActive) { /* camera status lights blink handled in SafeRoomView */ }
    }

    private void drawSpark(Gfx g, Spark s) {
        int frame = Math.min(s.frames - 1, (int) (s.t / s.duration * s.frames));
        String name = s.tap ? "electric_tap" + (frame + 1) + ".png" : "electric_barA" + (frame + 1) + ".png";
        Sprite sp = Assets.image(name);
        Gfx gs = g.create();
        if (s.tap) {
            gs.translate(s.x - sp.w / 2f, s.y - sp.h / 2f);
        } else {
            gs.translate(s.x + sp.w / 2f, s.y + sp.h / 2f);
            gs.scale(s.flipH ? -1 : 1, s.flipV ? -1 : 1);
            gs.translate(-sp.w / 2f, -sp.h / 2f);
        }
        sp.draw(gs, 0, 0);
        gs.dispose();
    }

    /** Draws the phone call over everything. */
    void drawOverlay(Gfx g) {
        if (call != null) call.draw(g);
    }

    /** Scale applied to the whole hub while a call is up (the safe room zooms away into the phone). */
    float hubScale() { return callZoom; }

    /** Pointer events for the call screen (consumed when a call is up). */
    boolean callPress(float x, float y) { if (call == null) return false; call.press(x, y); return true; }
    boolean callRelease(float x, float y) { if (call == null) return false; call.release(x, y); return true; }

    // ======================================================================================================================================

    /**
     * The black status bar the original shows during calls (the app hides it everywhere else). The clock is the only
     * content drawn; carrier and battery are device specific.
     */
    static void drawStatusBar(Gfx g) {
        g.setColor(0xFF000000);
        g.fillRect(0, 0, 320, 20);
        g.setColor(0xFFFFFFFF);
        g.setFont(Fnt.sans(true, 12));
        String t = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date());
        g.drawString(t, 160 - g.stringWidth(t) / 2f, 15);
    }

    /** Anything the director can show as a phone call. */
    interface CallScreen {
        void update(float dt);
        void draw(Gfx g);
        void press(float x, float y);
        void release(float x, float y);
        /** hideSecureCall started: the call screen fades out over this many seconds. */
        void beginHide(float seconds);
        /** The scene is being thrown away (e.g. HubScene.shutdown) while this screen was still up: release
         *  anything that must not be left running, such as the video call's camera. Most screens need nothing. */
        default void dispose() {}
    }

    /**
     * The whole prize call: first the normal call screen rings and plays secureCallVideo.m4a (decline and end are
     * disabled), then the video screen fades in over it (displaySecureCallVideo) and takes over.
     */
    static final class VideoCallFlow implements CallScreen {
        private final Runnable onEnd;
        private final SecureCallView voice;
        private SecureVideoCallView video;

        VideoCallFlow(Runnable onEnd) {
            this.onEnd = onEnd;
            this.voice = SecureCallView.videoPrelude("secureCallVideo", new Runnable() { public void run() { startVideo(); } }, onEnd);
        }

        private void startVideo() { video = new SecureVideoCallView(onEnd); }

        public void update(float dt) {
            if (video == null) voice.update(dt); else video.update(dt);
        }

        public void draw(Gfx g) {
            // the voice screen stays underneath until the video screen has fully faded in
            if (video == null || video.fadeProgress() < 1f) voice.draw(g);
            if (video != null) video.draw(g);
            // the status bar stays up until the video connects (FTCallConnected hides it)
            if (video == null || video.statusBarVisible()) drawStatusBar(g);
        }

        public void press(float x, float y) { if (video == null) voice.press(x, y); else video.press(x, y); }
        public void release(float x, float y) { if (video == null) voice.release(x, y); else video.release(x, y); }
        public void beginHide(float seconds) { }
        public void dispose() { voice.dispose(); if (video != null) video.dispose(); }
    }

    /** The FaceTime-style video call (SecureCallVideoViewController): ring, connect, play the recorded video. */
    static final class SecureVideoCallView implements CallScreen {
        private static final String VIDEO_AUDIO = "secureCallVideo-483";
        private static final float FPS = 12f;
        private static final int PH_RING = 0, PH_CONNECT = 1, PH_PLAY = 2, PH_CLOSE = 3;
        /** Set by DebugMenu to show camera diagnostics overlay during the call (phase, ready state, frame size). */
        static boolean debugCamera = false;
        private final Runnable onEnd;
        private int phase = PH_RING;
        private float alpha;                 // the whole call view fades in over 1.75s
        private float phaseT;
        private float videoT;
        private int frames;
        private boolean ringStarted, ended;
        private boolean answerDown;
        // SecureCallVideoViewController_initFrontCameraCapture: a live self-view, shown full-screen (behind
        // the ring/connecting chrome) while waiting to connect - confirmed in the assembly, including the
        // pi/2 rotation and 0.9 scale already used below for the recorded footage (the two use the same
        // transform). What is NOT confirmed is what happens to it once the scripted "other side" footage
        // starts playing (shrinkPreview suggests it becomes a small picture-in-picture, but its exact size
        // and position were not read from the assembly) - rather than guess at that geometry, the camera is
        // simply stopped the moment PH_PLAY begins, which is also the more privacy-conscious choice (the
        // camera is only ever live for as long as this port positively knows where to put it).
        private CameraPreview camera;
        private boolean cameraStarted;

        public void beginHide(float seconds) { }

        boolean statusBarVisible() { return phase == PH_RING || phase == PH_CONNECT; }

        float fadeProgress() { return phase == PH_CLOSE ? 1f : alpha; }

        SecureVideoCallView(Runnable onEnd) {
            this.onEnd = onEnd;
            int n = 0;
            while (Assets.exists(String.format("secureCallVideo/%04d.jpg", n + 1))) n++;
            frames = n;
        }

        public void update(float dt) {
            if (phase != PH_CLOSE) alpha = Math.min(1f, alpha + dt / 0.5f);   // displaySecureCallVideo: 0.5 s fade
            phaseT += dt;
            if (phase == PH_RING) {
                if (!ringStarted) { ringStarted = true; Audio.playMusic("call_ft_ring"); }
                if (camera == null && !cameraStarted) camera = Host.get().frontCamera();
                if (camera != null && !cameraStarted) { cameraStarted = true; camera.start(); }
            } else if (phase == PH_CONNECT) {
                if (camera == null && !cameraStarted) camera = Host.get().frontCamera();
                if (camera != null && !cameraStarted) { cameraStarted = true; camera.start(); }
                if (phaseT >= 2.5f) {
                    phase = PH_PLAY; phaseT = 0f; videoT = 0f;
                    Audio.play(VIDEO_AUDIO);
                }
            } else if (phase == PH_PLAY) {
                videoT += dt;
                if (frames == 0 || videoT >= frames / FPS) finishVideo();
            } else if (phase == PH_CLOSE) {
                if (phaseT >= 0.5f && !ended) { ended = true; stopCamera(); onEnd.run(); }
            }
        }

        private void stopCamera() {
            if (cameraStarted) { cameraStarted = false; camera.stop(); }
        }

        public void dispose() { stopCamera(); debugCamera = false; }

        private void finishVideo() {
            phase = PH_CLOSE;
            phaseT = 0f;
        }

        public void press(float x, float y) {
            if (phase == PH_RING && alpha > 0.8f && y >= 414 && x >= 160) answerDown = true;
        }

        public void release(float x, float y) {
            boolean was = answerDown;
            answerDown = false;
            if (was && phase == PH_RING && y >= 414 && x >= 160) {
                Audio.stopRingMusic();
                phase = PH_CONNECT;
                phaseT = 0f;
            }
        }

        public void draw(Gfx g) {
            Gfx gc = g.create();
            float a = alpha, scale = 1f;
            if (phase == PH_CLOSE) {
                float k = Math.min(1f, phaseT / 0.5f);
                a = 1f - k;
                scale = 1f - 0.9f * k;
            }
            gc.translate(160, 240);
            gc.scale(scale, scale);
            gc.translate(-160, -240);
            gc.setAlpha(Math.max(0f, Math.min(1f, a)));
            gc.setColor(0xFF000000);
            gc.fillRect(0, 0, 320, 480);
            // the status bar is visible (black) until the call connects; the view sits below it
            if (phase == PH_PLAY || phase == PH_CLOSE) {
                int f = Math.max(1, Math.min(frames, (int) (videoT * FPS) + 1));
                if (frames > 0) {
                    Sprite v = Assets.image(String.format("secureCallVideo/%04d.jpg", f));
                    // the footage was recorded sideways; it is turned upright and shown at 0.9 of aspect-fill
                    Gfx gv = gc.create();
                    gv.translate(160, 250);
                    gv.rotate((float) (Math.PI / 2));
                    float fill = 1.5f * 0.9f;
                    gv.scale(fill, fill);
                    gv.drawImage(v.img, -Math.round(v.w / 2), -Math.round(v.h / 2), Math.round(v.w), Math.round(v.h));
                    gv.dispose();
                }
                Assets.image("call_ft_controls.png").draw(gc, 0, 436);
            } else if (phase == PH_CONNECT) {
                drawCamera(gc);
                Assets.image("call_ft_connecting_top.jpg").draw(gc, 0, 20);
                Assets.image("call_ft_connecting_bottom.jpg").draw(gc, 0, 414);
            } else {
                drawCamera(gc);
                Assets.image("call_ft_top.jpg").draw(gc, 0, 20);
                Assets.image("call_ft_bottom.jpg").draw(gc, 0, 414);
                Assets.image("call_answer_ft.png").draw(gc, 160, 414, 160, 66, answerDown ? 0.7f : 1f);
            }
            gc.dispose();
        }

        // the self-view is only ever seen between the top/bottom chrome bars (y 115..414, see call_ft_top.jpg
        // and call_ft_bottom.jpg), so that is the box the live frame is scaled to cover
        private static final float CAMERA_VIEW_W = 320f, CAMERA_VIEW_H = 414f - 115f;

        /** The live self-view, while there is nothing else to show in its place (see the field comment on
         *  {@link #camera}). The recorded footage needs the pi/2 rotation because it was captured sideways
         *  by the original device's camera; a desktop webcam frame already comes out upright, so this just
         *  covers the visible area between the call chrome bars, cropping whichever axis overshoots instead
         *  of reusing the recorded footage's rotation/shrink numbers (tuned for a different source entirely). */
        private void drawCamera(Gfx gc) {
            heist.engine.Image frame = camera == null ? null : camera.currentFrame();
            if (frame != null) {
                Gfx gv = gc.create();
                gv.translate(160, 115 + CAMERA_VIEW_H / 2);
                float fill = Math.max(CAMERA_VIEW_W / frame.getWidth(), CAMERA_VIEW_H / frame.getHeight());
                gv.scale(fill, fill);
                int w = frame.getWidth(), h = frame.getHeight();
                gv.drawImage(frame, -w / 2, -h / 2, w, h);
                gv.dispose();
            }
            if (debugCamera) drawCameraDebug(gc, frame);
        }

        private void drawCameraDebug(Gfx gc, heist.engine.Image frame) {
            String[] lines = {
                "camera: " + (camera == null ? "NULL" : "present"),
                "phase: " + new String[]{"RING","CONNECT","PLAY","CLOSE"}[phase],
                "started: " + cameraStarted,
                "frame: " + (frame == null ? "null" : frame.getWidth() + "x" + frame.getHeight()),
            };
            Gfx gd = gc.create();
            gd.setFont(heist.engine.Fnt.sans(true, 11));
            int lh = 15, pad = 6;
            int bw = 200, bh = lines.length * lh + pad * 2;
            int bx = 160 - bw / 2, by = 200;
            gd.setColor(0xCC000000);
            gd.fillRoundRect(bx, by, bw, bh, 8, 8);
            gd.setColor(0xFF5AC8FF);
            gd.strokeRoundRect(bx, by, bw, bh, 8, 8);
            gd.setColor(0xFFFFFFFF);
            for (int i = 0; i < lines.length; i++) {
                gd.drawString(lines[i], bx + pad, by + pad + (i + 1) * lh - 2);
            }
            if (frame != null) {
                int tw = 80, th = 60, tx = 160 - tw / 2, ty = by + bh + 8;
                gd.setColor(0xFF000000);
                gd.fillRect(tx - 1, ty - 1, tw + 2, th + 2);
                gd.drawImage(frame, tx, ty, tw, th);
                gd.setColor(0xFF5AC8FF);
                gd.strokeRoundRect(tx - 1, ty - 1, tw + 2, th + 2, 0, 0);
            }
            gd.dispose();
        }
    }

    /** The phone call screen (SecureCallViewController + nib). */
    static final class SecureCallView implements CallScreen {
        private final String file;
        private final boolean introduction;
        private Runnable videoNext;            // set for the prelude of the video call: runs when the audio ends
        private boolean lockButtons;           // decline and end stay disabled (intro and video calls)
        private final Runnable onEnd;
        private float alpha;
        private float ringDelay = 0.75f;       // initPlaySecureCall: the ringtone starts 0.75 s after the call is shown
        private float hideSeconds = -1f;
        private boolean ringing, answered, finished, ended;
        private float timer;
        private int seconds;
        private int pressed;               // 1 decline, 2 answer, 3 terminate, 4 speaker
        private boolean speakerSelected;   // the speaker button was tapped on: it stays lit (setSelected:)
        // answerCallPressed: on a device with a proximity sensor, answering the very first call (introduction)
        // schedules a "secureCall_speakerphone" nudge line 2 s later, played only if the player still hasn't
        // turned speakerphone on by then (and the main call line isn't itself still playing).
        private float speakerNudgeT = -1f;

        /** The speaker button of the call nib: (205, 143), 95 x 108. */
        private boolean onSpeaker(float x, float y) {
            return Host.get().hasProximitySensor() && x >= 205 && x < 300 && y >= 143 && y < 251;
        }

        /**
         * answerCallPressed: with a proximity sensor (iPhone) the speaker button is enabled with the white picture and toggles to
         * blue; without one (the iPod touch 3G) it stays disabled and its disabled picture, the blue lit speaker, is shown.
         * Host.get().hasProximitySensor() reports which case a given port/device is.
         */
        private float callLength;
        private float elapsed;
        private boolean audioRequested;
        private float audioLeft = -1f;
        private float endDelay = -1f;

        /** The first stage of the video call: the normal call screen with the voice track, then the video screen. */
        static SecureCallView videoPrelude(String file, Runnable onAudioDone, Runnable onEnd) {
            SecureCallView v = new SecureCallView(file, false, onEnd);
            v.lockButtons = true;
            v.videoNext = onAudioDone;
            return v;
        }

        SecureCallView(String file, boolean introduction, Runnable onEnd) {
            this.file = file;
            this.introduction = introduction;
            this.lockButtons = introduction;
            this.onEnd = onEnd;
        }

        public void update(float dt) {
            if (hideSeconds > 0f) alpha = Math.max(0f, alpha - dt / hideSeconds);
            else alpha = Math.min(1f, alpha + dt / 0.5f);         // showSecureCall: 0.5 s fade
            if (!answered && !ringing && !ended) {
                ringDelay -= dt;
                if (ringDelay <= 0f) { ringing = true; startRing(); }
            }
            if (answered && !ended) {
                timer += dt;
                while (timer >= 1f) { timer -= 1f; seconds++; }
                if (audioLeft > 0f) {
                    audioLeft -= dt;
                    if (audioLeft <= 0f) {
                        if (videoNext != null) { ended = true; Runnable r = videoNext; videoNext = null; r.run(); }
                        else endCall(true);
                    }
                }
                if (speakerNudgeT >= 0f) {
                    speakerNudgeT -= dt;
                    if (speakerNudgeT <= 0f) {
                        speakerNudgeT = -1f;
                        // playSpeakerPhoneSound: skipped if the player already turned speakerphone on, or the
                        // main call line is somehow still going (e.g. a very long "secureCall_1.0" recording)
                        if (!ended && !speakerSelected && audioLeft <= 0f && Audio.isSfxOn()) Audio.play("secureCall_speakerphone");
                    }
                }
            }
            if (endDelay >= 0f) {
                endDelay -= dt;
                if (endDelay < 0f) { endDelay = -1f; onEnd.run(); }
            }
        }

        public void beginHide(float seconds) { hideSeconds = seconds; }

        private void startRing() { Audio.playMusic("ringtone2"); }

        public void press(float x, float y) {
            if (ended || alpha < 0.8f) return;
            if (!answered) {
                if (y >= 416 && x < 160 && !lockButtons) pressed = 1;
                else if (y >= 416 && x >= 160 && ringing) pressed = 2;
            } else if (y >= 416 && !lockButtons) pressed = 3;
            else if (onSpeaker(x, y)) pressed = 4;
        }

        public void release(float x, float y) {
            int p = pressed;
            pressed = 0;
            if (p == 0 || ended) return;
            if (p == 1 && y >= 416 && x < 160) endCall(false);
            else if (p == 2 && y >= 416 && x >= 160) answer();
            else if (p == 3 && y >= 416) endCall(false);
            else if (p == 4 && onSpeaker(x, y)) speakerSelected = !speakerSelected;   // speakerPhoneButtonPressed: toggles selected
        }

        private void answer() {
            answered = true;
            Audio.stopRingMusic();
            seconds = 0;
            timer = 0f;
            audioRequested = true;
            // the call audio plays as a sound effect; its length decides when the call ends
            audioLeft = Audio.lengthSeconds(file);
            if (audioLeft <= 0f) audioLeft = 5f;
            Audio.play(file);
            if (introduction && Host.get().hasProximitySensor()) speakerNudgeT = 2.0f;
        }

        private void endCall(boolean natural) {
            if (ended) return;
            ended = true;
            // saveCallFlags: the introduction call never rings again once it has been heard
            if (introduction) Save.put("displaySecureCallIntroductionCompleted", true);
            Audio.stopRingMusic();
            if (!natural) Audio.stopAllEffects();
            if (Audio.isSfxOn()) Audio.play("end_call_sound");
            endDelay = 0f;                      // hideSecureCall follows the end sound immediately
        }

        public void draw(Gfx g) {
            Gfx gc = g.create();
            gc.setAlpha(Math.max(0f, Math.min(1f, alpha)));
            Assets.image("call_base.jpg").draw(gc, 0, 0);
            if (answered) {
                // answerCallPressed: the button covers the greyed speaker of the call background (see onSpeaker)
                boolean lit = !Host.get().hasProximitySensor() || pressed == 4 || speakerSelected;
                Assets.image(lit ? "call_speaker.jpg" : "call_speaker_enabled.jpg").draw(gc, 205, 143);
                gc.setFont(Fnt.sans(true, 21));
                gc.setColor(0xFFFFFFFF);
                String t = String.format("%02d:%02d", seconds / 60, seconds % 60);
                gc.drawString(t, 18 + (60 - gc.stringWidth(t)) / 2f, 78 + (26 - gc.fontHeight()) / 2f + gc.fontAscent());
                Assets.image(pressed == 3 ? "call_terminate_pressed.jpg" : "call_terminate.jpg").draw(gc, 0, 416, 320, 66,
                        lockButtons ? 0.5f : 1f);
            } else {
                Assets.image(pressed == 1 ? "call_decline_pressed.jpg" : "call_decline.jpg").draw(gc, 0, 416, 160, 66,
                        lockButtons ? 0.5f : 1f);
                Assets.image(pressed == 2 ? "call_answer_pressed.jpg" : "call_answer.jpg").draw(gc, 160, 416, 160, 66,
                        ringing ? 1f : 0.5f);
            }
            gc.dispose();
            // viewWillAppear shows the status bar; prepareForEndSecureCall hides it again when the call ends
            if (!ended && videoNext == null) drawStatusBar(g);
        }
    }
}
