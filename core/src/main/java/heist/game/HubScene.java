package heist.game;


import heist.engine.Gfx;
import heist.engine.Image;
import heist.engine.Key;
import heist.engine.Images;
import java.util.ArrayList;
import java.util.List;

import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.Save;
import heist.engine.Scene;
import heist.engine.Sprite;

/**
 * The safe room hub. A bottom drawer holds the four puzzle-selector buttons; picking one slides the drawer
 * up and shows that puzzle's level strip. Picking a level zooms into it; finishing zooms back.
 */
public final class HubScene extends Scene {
    // drawer geometry (from MainWindow.nib and the original's animation code)
    private static final float SELECTOR_Y = 387f, SETTINGS_Y = 446f, SLIDE = 104f, SLIDE_TIME = 0.3f;
    private static final float[] BUTTON_X = { 6f, 88f, 168f, 247f };
    private static final String[] BUTTON_IMG = { "gameA", "gameC", "gameD", "gameB" };   // gridlock, sudoku, sokoban, fifteen
    private static final float STRIP_Y = 59f;

    private final SafeRoomView room = new SafeRoomView();
    private final List<PuzzleType> types = PuzzleType.all();
    private final LevelStrip[] strips = new LevelStrip[4];
    private final ProgressBarView bar = new ProgressBarView();

    // drawers
    private final Anim selector = new Anim(SELECTOR_Y);
    private final Anim settings = new Anim(SETTINGS_Y);
    private final Anim introSlide = new Anim(200f);      // first-launch slide in from below
    private boolean levelSelectorShowing, settingsShowing, animatingSelector, animatingToNext;
    private int selectedType = -1, nextType = -1;
    private boolean prefsSelected;
    private float roomFade = 0f;
    private boolean introDone;
    private float pulse;
    // pulsePuzzleSelector: every 2.312 s the selected-state button art flickers (0.02 s ticks, 50% visible) for 1 s
    private static final float PULSE_PERIOD = 5.0f, PULSE_FLICKER = 1f, PULSE_TICK = 0.02f;
    private final java.util.Random pulseRnd = new java.util.Random();
    private float pulseClock, pulseTickAcc;
    private boolean pulseVisible, pulseWasOn;
    private boolean pulsing;

    // puzzle hosting / transitions
    private enum Mode { HUB, ZOOM_IN_FADE, ZOOM_IN, PLAYING, ZOOM_OUT, ZOOM_OUT_FADE, AFTER }
    private Mode mode = Mode.HUB;
    private PuzzleScene puzzle;
    private int puzzleTypeIndex, puzzleLevel;
    private float modeT;
    private float[] thumbAbs;             // thumbnail rect on screen: x, y, w, h
    private float[] zoomWindow;           // puzzle content window shown inside the thumbnail
    private boolean finishedLevel;
    private float brassLeft;                       // seconds of the level-completion brass still playing
    private boolean endPending;                    // the end animation waits for the brass to finish
    private Disarm.Result pendingResult;
    private LevelStrip pendingStrip;
    private int pendingLevel;
    private Image lastSnapshot;
    private float overlayFade;
    private float overlayOutT = -1f, overlayOutFrom;   // the back/reset bar fades out (puzzleControllerWillBeginTransitionOut)
    private float floorInT = -1f, floorOutT = -1f;
    private int exitStage;                  // 1: waiting for a walking robot to pause, 2: waiting for the level reset
    private float exitWait;   // Sokoban's floor fading in after the zoom / out before it

    // secure calls / unlock sequences are driven by the director
    private final SecurityDirector director;
    private final VaultController vault;

    public HubScene() {
        director = new SecurityDirector(this, room);
        vault = new VaultController(this, room);
        vault.showOpenedOnLaunch();
        selector.set(SELECTOR_Y);
        settings.set(SETTINGS_Y);
        Audio.setMusicOn(Save.getBool("gameMusicIsActivated", true));
        Audio.setSfxOn(Save.getBool("gameSoundFxIsActivated", true));
        boolean hintNow = HintCall.launchBookkeeping();
        if (HintCall.dueOnLaunch() || hintNow) {
            vault.setHintPending(true);          // firstLaunchHintCallWillTrigger: the vault cannot be entered until it rang
            director.ringHintAfterLaunch();
        }
        pulsing = Save.getBool("displaySecureCallIntroductionCompleted", false);   // viewDidLoad: pulsePuzzleSelector:YES
        updateBar(false, true);                  // viewDidLoad: renderProgressBars:1 withNewProgressTicks:1 (the tick marks are there from the start)
    }

    /** Test/tool helper: fast-forwards the simulation. */
    public void advance(float seconds) {
        for (float t = 0; t < seconds; t += 1f / 60f) update(1f / 60f);
    }

    // ---- accessors ------------------------------------------------------------------------------

    public boolean isLevelSelectorShowing() { return levelSelectorShowing; }

    SafeRoomView room() { return room; }

    // ---- developer tools -------------------------------------------------------------------------------------------
    // A port may offer a developer menu (see heist.engine.Overlay); it drives the safe room through these.

    /** True while the safe room is on screen (not while a puzzle is being played). */
    public boolean debugAvailable() { return mode != Mode.PLAYING; }

    /** True once the room has powered up and nothing is running. */
    public boolean debugReady() { return mode == Mode.HUB && introSlide.get() < 1f && !introSlide.isActive() && director.isReady() && !vault.isBusy(); }

    /** True when nothing is animating, calling or transitioning. */
    public boolean debugIdle() { return mode == Mode.HUB && !introSlide.isActive() && !director.isBusy() && !vault.isBusy(); }

    /** Plays the level-up that follows a filled bar. */
    public void debugLevelFilled(Disarm.Result r) { director.debugLevelFilled(r); }

    /** Developer menu "Vault cracked + smoke" scene state: see {@link VaultController#debugShowCrackedWithSmoke()}. */
    public void debugShowVaultSmoke() { vault.debugShowCrackedWithSmoke(); }

    /** Stops everything this scene started before it is replaced by a rebuilt one. */
    public void shutdown() {
        vault.stopAudio();
        director.shutdown();
        Audio.stopMusic();
        Audio.stopAllEffects();
    }

    /** showPedestalHint: the hint call rings and the pedestal appears in the vault. */
    public void showHintCall() {
        HintCall.markTriggered();
        vault.setHintPending(false);
        director.showCallThen("secureCall_hint", new Runnable() { public void run() { vault.refresh(); } });
    }

    public void keyPressed(Key key) {
        if (mode == Mode.PLAYING) { if (puzzle != null) puzzle.keyPressed(key); return; }
    }

    VaultController vault() { return vault; }

    /** Drops the two drawers off the bottom of the screen (or brings them back). */
    void slideDrawers(boolean away, float seconds) { introSlide.start(away ? 200f : 0f, seconds, 0f); }

    boolean drawersAreIn() { return introSlide.get() < 1f && !introSlide.isActive(); }
    ProgressBarView bar() { return bar; }
    boolean introDone() { return introDone; }

    /** False while the room is still fading in from black at launch. */
    boolean roomFadedIn() { return roomFade >= 1f; }

    // ---- lifecycle -------------------------------------------------------------------------------

    public void onEnter() {
        if (mode == Mode.HUB && !introDone) {
            roomFade = 0f;
            introSlide.set(200f);
            Audio.playMusic("safe_room2");
            director.onHubShown();
        }
    }

    /** Called by the director when the first-launch call (if any) is over: the drawers slide in. */
    void showDrawers() {
        introDone = true;
        pulsing = true;
        introSlide.start(0f, 1.0f, 1.0f);
    }

    // ---- bar -------------------------------------------------------------------------------------------

    /** Mirrors renderProgressBars:withNewProgressTicks:. */
    void updateBar(boolean animated, boolean ticks) {
        Disarm d = Disarm.get();
        bar.fullWidth = 231;
        bar.animate = animated;
        if (d.has100PercentCompleted()) {
            bar.drawGolden();
        } else {
            bar.setColor(d.levelUpCount() < 4 ? "blue" : "silver");
        }
        if (ticks) bar.setTicks(d.pointsRequiredForNextLevelUp());
        float pct = 1f;
        if (!d.achievedLevelUp()) {
            if (d.levelUpCount() > 3 && "blue".equals(bar.color)) bar.drawSilver();
            pct = d.percentCompletedForNextDisarmLevel();
        }
        if (bar.isSilver()) pct = d.percentCompletedFor100PercentCompletion();
        bar.update(pct);
    }

    // ---- selector / drawers ---------------------------------------------------------------------------

    private void setLevelSelectorShowing(boolean show, boolean sfx) {
        animatingSelector = true;
        selector.start(SELECTOR_Y + (show ? -SLIDE : 0f), SLIDE_TIME, 0f);
        levelSelectorShowing = show;
        if (sfx) Audio.play(show ? "level_selector_slide_open" : "level_selector_slide_close");
    }

    private void setSettingsShowing(boolean show, boolean sfx) {
        settings.start(SETTINGS_Y + (show ? -SLIDE : 0f), SLIDE_TIME, 0f);
        settingsShowing = show;
        prefsSelected = show;
        if (sfx) Audio.play(show ? "level_selector_slide_open" : "level_selector_slide_close");
    }

    private void puzzleButtonPressed(int index) {
        if (animatingSelector || animatingToNext) return;
        nextType = -1;
        boolean thisSelected = selectedType == index;
        if (!(thisSelected && levelSelectorShowing)) {
            if (levelSelectorShowing && selectedType != index) {
                // another puzzle's strip is open: close it first, then open this one
                if (settingsShowing) setSettingsShowing(false, false);
                animatingToNext = true;
                nextType = index;
                setLevelSelectorShowing(false, true);
                return;
            }
            selectedType = index;
            pulsing = false;
            if (strips[index] == null) strips[index] = new LevelStrip(types.get(index));
            else strips[index].scrollToCurrent();
            Save.put("currentGameTypePuzzleIndex", index);
            if (!levelSelectorShowing) setLevelSelectorShowing(true, true);
            if (settingsShowing) setSettingsShowing(false, true);
        } else {
            if (!settingsShowing) {
                selectedType = -1;
                setLevelSelectorShowing(false, true);
                if (!Disarm.get().achievedLevelUp()) pulsing = true;
            } else {
                setSettingsShowing(false, true);
            }
        }
    }

    private void prefsPressed() {
        if (animatingSelector) return;
        if (!settingsShowing) {
            pulsing = false;
            setSettingsShowing(true, true);
            if (!levelSelectorShowing) setLevelSelectorShowing(true, false);
        } else {
            setSettingsShowing(false, true);
            if (selectedType < 0) {
                setLevelSelectorShowing(false, false);
                pulsing = true;
            }
        }
    }

    // ---- input ------------------------------------------------------------------------------------------

    private LevelStrip pressedStrip;

    private boolean safeTouch;

    public void pointerPressed(float x, float y) {
        if (mode == Mode.PLAYING) { if (puzzle != null) puzzle.pointerPressed(x, y); return; }
        if (director.callPress(x, y)) return;
        if (mode == Mode.HUB && vault.pointerPressed(x, y)) return;
        if (mode != Mode.HUB || director.blocksInput()) return;
        if (!hitsDrawerUi(x, y)) { safeTouch = true; director.touchBegan(x, y); }
        float off = introSlide.get();
        float sy = selector.get() + off, ty = settings.get() + off;
        // game selector buttons
        if (y >= sy && y < sy + 54) {
            for (int i = 0; i < 4; i++) {
                if (x >= BUTTON_X[i] && x < BUTTON_X[i] + 68) { buttonDown = i; return; }
            }
        }
        // prefs button
        if (x >= 273 && x < 320 && y >= ty && y < ty + 34) { prefsDown = true; return; }
        // settings panel buttons
        if (settingsShowing && y >= ty + 34 && y < ty + 34 + 103) {
            settingsPress(x, y - ty - 34);
            return;
        }
        // level strip
        if (selectedType >= 0 && levelSelectorShowing && strips[selectedType] != null
                && y >= sy + STRIP_Y && y < sy + STRIP_Y + LevelStrip.VIEW_H) {
            pressedStrip = strips[selectedType];
            pressedStrip.press(x, y - (sy + STRIP_Y));
        }
    }

    /** True if a press at (x, y) lands on a drawer (so it is not a safe-room touch). */
    private boolean hitsDrawerUi(float x, float y) {
        float off = introSlide.get();
        float sy = selector.get() + off, ty = settings.get() + off;
        return y >= sy || y >= ty;
    }

    private int buttonDown = -1;
    private boolean prefsDown;

    public void pointerDragged(float x, float y) {
        if (mode == Mode.PLAYING) { if (puzzle != null) puzzle.pointerDragged(x, y); return; }
        if (mode == Mode.HUB && vault.pointerDragged(x, y)) return;
        if (safeTouch) director.touchMoved(x, y);
        if (pressedStrip != null) pressedStrip.drag(x);
    }

    public void pointerReleased(float x, float y) {
        if (mode == Mode.PLAYING) { if (puzzle != null) puzzle.pointerReleased(x, y); return; }
        if (director.callRelease(x, y)) return;
        if (mode == Mode.HUB && vault.pointerReleased(x, y)) { safeTouch = false; return; }
        if (safeTouch) { safeTouch = false; director.touchEnded(x, y); }
        if (mode != Mode.HUB) return;
        float off = introSlide.get();
        float sy = selector.get() + off, ty = settings.get() + off;
        if (buttonDown >= 0) {
            int b = buttonDown;
            buttonDown = -1;
            if (y >= sy && y < sy + 54 && x >= BUTTON_X[b] && x < BUTTON_X[b] + 68) puzzleButtonPressed(b);
            return;
        }
        if (prefsDown) {
            prefsDown = false;
            if (x >= 273 && x < 320 && y >= ty && y < ty + 34) prefsPressed();
            return;
        }
        if (settingsPressed >= 0) {
            settingsRelease(x, y - ty - 34);
            return;
        }
        if (pressedStrip != null) {
            LevelStrip s = pressedStrip;
            pressedStrip = null;
            int level = s.release(x, y - (sy + STRIP_Y));
            if (level != 0) levelTapped(selectedType, level, s);
        }
    }

    // settings panel (SafeRoomPrefs): music (-3,22 87x63), sound fx (89,22), game center (178,14), review (250,19)
    private int settingsPressed = -1;

    private void settingsPress(float x, float y) {
        if (x >= -3 && x < 84 && y >= 22 && y < 85) settingsPressed = 0;
        else if (x >= 89 && x < 176 && y >= 22 && y < 85) settingsPressed = 1;
        else if (x >= 178 && x < 254 && y >= 14 && y < 94) settingsPressed = 2;     // Game Center: online, intentionally inert
        else if (x >= 250 && x < 323 && y >= 19 && y < 87) settingsPressed = 3;     // review: online, intentionally inert
    }

    private void settingsRelease(float x, float y) {
        int b = settingsPressed;
        settingsPressed = -1;
        boolean inside = (b == 0 && x >= -3 && x < 84 && y >= 22 && y < 85) || (b == 1 && x >= 89 && x < 176 && y >= 22 && y < 85)
                || (b == 2 && x >= 178 && x < 254 && y >= 14 && y < 94) || (b == 3 && x >= 250 && x < 323 && y >= 19 && y < 87);
        if (!inside) return;
        if (b == 0) {
            boolean on = !Audio.isMusicOn();
            Save.put("gameMusicIsActivated", on);
            if (Audio.isSfxOn()) Audio.play("button_click_a");
            Audio.setMusicOn(on);
            if (on) Audio.playMusic("safe_room2");
        } else if (b == 1) {
            boolean on = !Audio.isSfxOn();
            Save.put("gameSoundFxIsActivated", on);
            Audio.setSfxOn(on);
            Audio.play("button_click_a");
        }
    }

    // ---- launching a level -----------------------------------------------------------------------------------

    private void levelTapped(int typeIndex, int level, LevelStrip strip) {
        PuzzleType t = types.get(typeIndex);
        if (strip.isLocked(level)) {
            Audio.play("lock_clink");
            strip.rattle(level);
            return;
        }
        Audio.play("button_click_a");
        startPuzzle(typeIndex, level, strip);
    }

    private void startPuzzle(int typeIndex, int level, LevelStrip strip) {
        PuzzleType t = types.get(typeIndex);
        puzzleTypeIndex = typeIndex;
        puzzleLevel = level;
        puzzle = Nav.createForDisplayed(t, level);
        puzzle.attach(game);
        puzzle.onEnter();
        puzzle.interactive = false;
        puzzle.overlayAlpha = 0f;
        puzzle.zoomFloor = puzzle.fadesFloorInZoom() ? 0f : 1f;
        puzzle.floorRising = true;
        floorInT = floorOutT = -1f;
        overlayOutT = -1f;
        exitStage = 0;
        float[] r = strip.thumbRect(level);
        float sy = selector.get() + introSlide.get() + STRIP_Y;
        thumbAbs = new float[] { strip.contentToView(r[0]), sy + r[1], r[2], r[3] };
        zoomWindow = puzzle.snapshotRegion();
        if (endPending) runEnd();              // never leave the previous end animation waiting
        finishedLevel = false;
        director.setAnimationsActive(false);   // levelTransitionWillBegin
        mode = Mode.ZOOM_IN_FADE;
        modeT = 0f;
    }

    /** Back button inside a puzzle. */
    public void puzzleBack(PuzzleScene p) {
        if (mode != Mode.PLAYING || p != puzzle) return;
        Audio.play("movement_3");
        // leaving during the celebration of a solved puzzle still ends the level (badge, bar, level-up)
        boolean solved = p.completionResult() != null;
        finishedLevel = solved;
        beginZoomOut(solved);
    }

    /** The completion brass starts (the puzzle calls this as its end fade begins). */
    public void beginBrass() {
        Audio.play("level_completion_brass");
        // the end animation (badge, bar, level-up) starts 0.3 s before the brass has finished
        brassLeft = Math.max(Audio.lengthSeconds("level_completion_brass") - 0.3f, 0f);
    }

    /** A puzzle's celebration finished (it recorded its completion itself). */
    public void levelFinished(PuzzleScene p) {
        if (mode != Mode.PLAYING || p != puzzle) return;
        finishedLevel = true;
        beginZoomOut(true);
    }

    private void beginZoomOut(boolean completed) {
        puzzle.interactive = false;
        // puzzleControllerWillBeginTransitionOut: leaving without finishing stops the game track at once; a finished level's
        // track was already stopped when its celebration began
        if (!completed) Audio.stopGameMusic();
        if (completed) lastSnapshot = snapshot();
        overlayFade = 1f;
        if (puzzle.fadesFloorInZoom()) {
            // transitionOutBegin: pause a walking robot, reset the level if it was played, then fade the floor out
            exitWait = puzzle.exitPause();
            exitStage = 1;
            return;
        }
        startOverlayOut();
        mode = Mode.ZOOM_OUT;
        modeT = 0f;
    }

    /** puzzleControllerWillBeginTransitionOut: the back/reset bar fades out over UIView's default 0.2 s (ease-in-out). */
    private void startOverlayOut() {
        if (overlayOutT >= 0f) return;
        overlayOutT = 0f;
        overlayOutFrom = puzzle.overlayAlpha;
    }

    private void updateOverlayOut(float dt) {
        if (overlayOutT < 0f) return;
        overlayOutT += dt;
        puzzle.overlayAlpha = overlayOutFrom * (1f - ease(Math.min(1f, overlayOutT / 0.2f)));
    }

    /**
     * The level's icon picture and the puzzle view cross-fade (levelZoomIn, levelZoomOutPreCrossfade...): opening, the icon
     * goes 1 -> 0 while the puzzle goes 0 -> 1 over 0.25 s; it stays hidden while the level is open; closing, it comes back
     * 0 -> 1 as the puzzle goes 1 -> 0. A solved level swaps at once, to its new picture.
     */
    private void updateIconAlpha() {
        if (puzzle == null) return;
        float a;
        switch (mode) {
            case ZOOM_IN_FADE: a = 1f - ease(modeT / 0.25f); break;
            case ZOOM_IN:
            case PLAYING:
            case ZOOM_OUT: a = 0f; break;
            case ZOOM_OUT_FADE: a = ease(modeT / 0.25f); break;
            default: a = 1f;
        }
        stripFor(puzzleTypeIndex).setIconAlpha(puzzleLevel, a);
    }

    /** Saved picture size relative to the 64 pt icon: 3x, so the icon stays sharp on large windows and retina screens. */
    private static final int THUMB_SUPERSAMPLE = 3;

    /** Picture of the finished puzzle (the original saves LevelCompleted-G#L#.png), rendered at 3x and filtered down. */
    private Image snapshot() {
        try {
            Image full = heist.platform.Host.get().createImage(320 * 4, 480 * 4);
            Gfx g = Gfx.of(full);
            g.scale(4, 4);
            float floor = puzzle.zoomFloor;
            if (puzzle.fadesFloorInZoom()) puzzle.zoomFloor = 0f;       // the icon shows the level on its own, without the floor
            puzzle.renderContent(g);
            puzzle.zoomFloor = floor;
            puzzle.renderThumbnailOverlay(g);
            g.dispose();
            float[] w = zoomWindow;
            int sx = Math.max(0, Math.round(w[0] * 4)), sy = Math.max(0, Math.round(w[1] * 4));
            int sw = Math.min(full.getWidth() - sx, Math.round(w[2] * 4)), sh = Math.min(full.getHeight() - sy, Math.round(w[3] * 4));
            Image window = Images.crop(full, sx, sy, sw, sh);
            float k = puzzle.thumbScale(w);
            int tw = Math.max(1, Math.round(w[2] * k * THUMB_SUPERSAMPLE));
            int th = Math.max(1, Math.round(w[3] * k * THUMB_SUPERSAMPLE));
            return smoothScale(window, tw, th);
        } catch (OutOfMemoryError e) {
            // snapshot allocates a large offscreen bitmap; skip the thumbnail rather than crash on low-memory systems
            return null;
        }
    }

    /** Halves the picture step by step (each step averages the pixels) and finishes with a filtered step. */
    private static Image smoothScale(Image src, int tw, int th) {
        Image cur = src;
        while (cur.getWidth() >= tw * 2 && cur.getHeight() >= th * 2) {
            cur = Images.scale(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return Images.scale(cur, tw, th);
    }

    // ---- update ----------------------------------------------------------------------------------------------------

    public void update(float dt) {
        room.update(dt);
        bar.tick(dt);
        selector.update(dt);
        settings.update(dt);
        introSlide.update(dt);
        pulse += dt;
        updatePulse(dt);
        if (roomFade < 1f) roomFade = Math.min(1f, roomFade + dt / 1.0f);
        if (animatingSelector && !selector.isActive()) {
            animatingSelector = false;
            if (animatingToNext && nextType >= 0) {
                int n = nextType;
                animatingToNext = false;
                nextType = -1;
                selectedType = -1;
                puzzleButtonPressed(n);
            } else {
                animatingToNext = false;
            }
        }
        for (LevelStrip s : strips) if (s != null) s.update(dt);
        director.update(dt);
        vault.update(dt);
        if (brassLeft > 0f) { brassLeft -= dt; if (brassLeft <= 0f && endPending) runEnd(); }

        if (mode != Mode.HUB)        switch (mode) {
            case ZOOM_IN_FADE:
                modeT += dt;
                if (modeT >= 0.25f) { mode = Mode.ZOOM_IN; modeT = 0f; startGameMusic(); }
                break;
            case ZOOM_IN:
                modeT += dt;
                puzzle.update(dt);
                if (modeT >= 0.75f) {
                    mode = Mode.PLAYING;
                    overlayFade = 0f;
                    modeT = 0f;
                    if (puzzle.fadesFloorInZoom()) floorInT = 0f;   // addCompleteBackground: the outer background fades in over 0.5 s
                    if (!puzzle.fadesFloorInZoom() || !puzzle.holdsInputWhileFading()) puzzle.interactive = true;
                }
                break;
            case PLAYING:
                puzzle.update(dt);
                if (floorInT >= 0f) {
                    floorInT += dt;
                    float u = Math.min(1f, floorInT / 0.5f);
                    puzzle.zoomFloor = heist.engine.Ease.out(u);     // ease-out curve
                    if (floorInT >= 0.5f) { floorInT = -1f; puzzle.zoomFloor = 1f; puzzle.floorRising = false; puzzle.interactive = true; puzzle.beginTiming(); }
                }
                if (exitStage > 0) {
                    exitWait -= dt;
                    if (exitWait <= 0f) {
                        if (exitStage == 1) {
                            exitStage = 2;
                            exitWait = finishedLevel ? 0f : puzzle.exitReset();
                        }
                        if (exitStage == 2 && exitWait <= 0f) { exitStage = 0; floorOutT = 0f; startOverlayOut(); }
                    }
                }
                if (floorOutT >= 0f) {
                    floorOutT += dt;
                    float u = Math.min(1f, floorOutT / 0.35f);
                    puzzle.zoomFloor = 1f - heist.engine.Ease.out(u);
                    if (floorOutT >= 0.35f) {
                        floorOutT = -1f;
                        puzzle.zoomFloor = 0f;
                        mode = Mode.ZOOM_OUT;
                        modeT = 0f;
                        break;
                    }
                }
                if (overlayFade < 1f) {
                    modeT += dt;
                    overlayFade = Math.min(1f, modeT / 0.25f);
                    puzzle.overlayAlpha = ease(overlayFade);
                }
                updateOverlayOut(dt);
                break;
            case ZOOM_OUT:
                modeT += dt;
                updateOverlayOut(dt);
                puzzle.update(dt);
                if (modeT >= 0.75f) {
                    if (finishedLevel && lastSnapshot != null) {
                        LevelStrip s = stripFor(puzzleTypeIndex);
                        s.saveCompletedThumbnail(puzzleLevel, lastSnapshot);
                    }
                    if (finishedLevel) { afterZoomOut(); break; }
                    mode = Mode.ZOOM_OUT_FADE;
                    modeT = 0f;
                }
                break;
            case ZOOM_OUT_FADE:
                modeT += dt;
                if (modeT >= 0.25f) afterZoomOut();
                break;
            case AFTER:
                break;
            default:
                break;
        }
        updateIconAlpha();
    }

    private void updatePulse(float dt) {
        if (!pulsing) { pulseWasOn = false; pulseVisible = false; return; }
        if (!pulseWasOn) { pulseWasOn = true; pulseClock = 0f; pulseTickAcc = 0f; }
        pulseClock += dt;
        boolean inBurst = pulseClock >= PULSE_PERIOD && (pulseClock % PULSE_PERIOD) < PULSE_FLICKER;
        if (!inBurst) { pulseVisible = false; pulseTickAcc = 0f; return; }
        pulseTickAcc += dt;
        while (pulseTickAcc >= PULSE_TICK) {
            pulseTickAcc -= PULSE_TICK;
            pulseVisible = pulseRnd.nextInt(2) != 1;
        }
    }

    private LevelStrip stripFor(int typeIndex) {
        if (strips[typeIndex] == null) strips[typeIndex] = new LevelStrip(types.get(typeIndex));
        return strips[typeIndex];
    }

    private void startGameMusic() {
        // startGameMusic: a new game player; the safe room track fades out while it fades in
        Audio.startGameMusic(puzzle.type().name.toLowerCase() + "_music");
    }

    private void afterZoomOut() {
        stripFor(puzzleTypeIndex).setIconAlpha(puzzleLevel, 1f);
        PuzzleScene finished = puzzle;
        // the scene's onExit would only stop the music; the hub crossfades it back to the safe room track instead
        puzzle = null;
        mode = Mode.HUB;
        director.setAnimationsActive(true);    // viewDidAppear
        if (finishedLevel) {
            Disarm.Result r = finished.completionResult();
            LevelStrip strip = stripFor(puzzleTypeIndex);
            strip.refreshCompleted(puzzleLevel);
            // levelTransitionOutComplete: the music and the end animation (badge, bar, level-up) come after the brass
            pendingResult = r;
            pendingStrip = strip;
            pendingLevel = puzzleLevel;
            endPending = true;
            if (brassLeft <= 0f) runEnd();
        } else {
            Audio.startSafeRoomMusic();            // levelTransitionOutComplete: startSafeRoomMusic
        }
        finishedLevel = false;
    }

    private void runEnd() {
        endPending = false;
        Audio.startSafeRoomMusic();
        pendingStrip.applyCompletedBadge(pendingLevel);
        director.afterLevelCompleted(pendingResult, pendingStrip);
    }

    private int lastSelectedType = -1;
    private float savedScroll;

    /** Closes the level selector (the way the unlock sequence does) and remembers which puzzle was open. */
    void hideLevelSelectorWithButton() {
        if (selectedType >= 0) lastSelectedType = selectedType;
        if (levelSelectorShowing) {
            selectedType = -1;
            setLevelSelectorShowing(false, true);
        }
    }

    /** Re-opens the puzzle strip that hideLevelSelectorWithButton closed. */
    void showLastLevelSelectorWithButton() {
        if (lastSelectedType < 0 || levelSelectorShowing) return;
        selectedType = lastSelectedType;
        if (strips[selectedType] == null) strips[selectedType] = new LevelStrip(types.get(selectedType));
        setLevelSelectorShowing(true, true);
    }

    /** Scrolls the open strip to the first level of the tier that is about to unlock. */
    void scrollActiveToNextLockedLevel() {
        if (selectedType < 0 || strips[selectedType] == null) return;
        LevelStrip s = strips[selectedType];
        savedScroll = s.scroll();
        PuzzleType t = types.get(selectedType);
        int tier = Disarm.get().levelUpCount() + 1;
        for (int i = 1; i <= t.levels; i++) {
            if (t.difficulty(i) == tier) { s.scrollTo(s.levelX(i) - 10f, true); return; }   // scrollToNextLockedLevel: x - 10, clamped
        }
    }

    void returnActiveToPreviousLevel() {
        if (selectedType < 0 || strips[selectedType] == null) return;
        strips[selectedType].scrollTo(savedScroll, true);
    }

    /** Unlocks the tier opened by the latest level-up (with the chain animation where visible). */
    void unlockLevelsAfterDisarm() { unlockTierEverywhere(Disarm.get().levelUpCount() + 1); }

    /** enablePostLevelUpInteractionAndRemainderPoints. */
    void enableAfterLevelUp() {
        Disarm d = Disarm.get();
        if (!d.achievedLevelUp()) return;
        d.clearLevelUpFlag();
        d.matchDisarmLevelToDifficultyPoints();
        updateBar(false, true);
    }

    /** The bars dropped away: the safe is open. */
    void vaultUnlocked() { director.afterSafeUnlocked(); }

    /** Rings a secure call from outside the director (the vault's prize calls). */
    public void showCallThen(String file, Runnable then) { director.showCallThen(file, then); }

    /** Debug: jump straight to the video call screen, no prelude. */
    public void showVideoCallDirectly() { director.showVideoCallDirectly(); }

    /** Like showCallThen("secureCallVideo", …) but enables the camera diagnostic overlay for debugging. */
    public void showVideoCallWithCameraDebug() {
        SecurityDirector.SecureVideoCallView.debugCamera = true;
        director.showCallThen("secureCallVideo", null);
    }

    // ---- unlocking / director callbacks ----------------------------------------------------------------------------

    /** Opens the strips' newly available difficulty tier (with the chains animation if its strip is on screen). */
    void unlockTierEverywhere(int tier) {
        boolean visible = levelSelectorShowing;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            LevelStrip s = strips[i];
            if (s == null) continue;
            boolean animated = visible && i == selectedType;
            if (s.unlockTier(tier, animated) > 0) any = true;
        }
        if (any) Audio.play("unlock_with_chains");
    }

    // ---- rendering -------------------------------------------------------------------------------------------------

    public void render(Gfx g) {
        if (mode == Mode.PLAYING) {
            // while the puzzle's outer background fades in or out the room shows through behind it
            if (puzzle.fadesFloorInZoom() && puzzle.zoomFloor < 1f) renderHub(g);
            g.group("Puzzle");
            puzzle.render(g);
            return;
        }
        renderHub(g);
        if (mode == Mode.ZOOM_IN_FADE || mode == Mode.ZOOM_IN || mode == Mode.ZOOM_OUT || mode == Mode.ZOOM_OUT_FADE) drawZoom(g);
    }

    /** The safe room with its drawers, the torch and the call overlays. */
    private void renderHub(Gfx g) {
        // hub underneath
        g.group("Safe room");
        Gfx gh = g.create();
        float roomAlpha = vault.coversRoom() ? roomFade : roomFade * vault.roomAlpha();
        if (roomAlpha < 1f) {
            gh.setColor(0xFF000000);
            gh.fillRect(0, 0, 320, 480);
            gh.setAlpha(Math.max(0f, roomAlpha));
        }
        float zoom = director.hubScale();
        if (zoom < 1f) {
            gh.translate(160, 240);
            gh.scale(zoom, zoom);
            gh.translate(-160, -240);
        }
        final Gfx ghf = gh;
        room.draw(gh, new Runnable() { public void run() { director.drawBehindDrawers(ghf); } });
        gh.dispose();
        Gfx gd = g.create();
        if (zoom < 1f) {
            gd.translate(160, 240);
            gd.scale(zoom, zoom);
            gd.translate(-160, -240);
        }
        g.group("Drawers (level selector and settings)");
        if (!vault.isInside()) drawDrawers(gd);
        gd.dispose();
        g.group("Cutting torch");
        vault.drawTorch(g);
        g.group("Overlays (calls, status bar)");
        director.drawOverlay(g);
        g.group("Level zoom transition");
    }

    private void drawDrawers(Gfx g) {
        float off = introSlide.get();
        float sy = selector.get() + off, ty = settings.get() + off;

        // game selector drawer
        Gfx gd = g.create();
        gd.translate(0, sy);
        Assets.image("level_background.jpg").draw(gd, 0, 64);
        // the strip's scroll area starts at y = 59: carry the background up under the base's translucent lip
        Gfx gb = gd.create();
        gb.clipRect(0, 58, 320, 6);
        Assets.image("level_background.jpg").draw(gb, 0, 58);
        gb.dispose();
        Assets.image("GameSelection_base.png").draw(gd, 0, 0);
        if (selectedType >= 0 && strips[selectedType] != null) {
            Gfx gl = gd.create();
            gl.translate(0, STRIP_Y);
            strips[selectedType].draw(gl);
            gl.dispose();
        }
        for (int i = 0; i < 4; i++) {
            boolean on = (selectedType == i && levelSelectorShowing) || buttonDown == i;
            boolean lit = on || (pulsing && pulseVisible);
            Assets.image(BUTTON_IMG[i] + (lit ? "_on.png" : "_off.png")).draw(gd, BUTTON_X[i], 0);
        }
        gd.dispose();
        // settings drawer (progress meter + prefs)
        Gfx gs = g.create();
        gs.translate(0, ty);
        Assets.image("level_background.jpg").draw(gs, 0, 34);
        if (settingsShowing || settings.get() < SETTINGS_Y - 1) drawPrefsPanel(gs);
        Assets.image("game_meter.png").draw(gs, 0, 0);
        bar.draw(gs, 29, 14);
        Assets.image(prefsSelected ? "prefs_on.png" : "prefs.png").draw(gs, 273, 0);
        gs.dispose();

    }

    private void drawPrefsPanel(Gfx g) {
        Gfx gp = g.create();
        gp.translate(0, 34);
        Assets.image(Audio.isMusicOn() ? "settings_music_on.png" : "settings_music_off.png").draw(gp, -3, 22);
        Assets.image(Audio.isSfxOn() ? "settings_soundfx_on.png" : "settings_soundfx_off.png").draw(gp, 89, 22);
        Assets.image("settings_game_center.png").draw(gp, 178, 14);
        Assets.image("settings_review.png").draw(gp, 250, 19);
        gp.dispose();
    }

    /** Draws the puzzle zoomed between its thumbnail and full screen. */
    private void drawZoom(Gfx g) {
        float fadeIn = 1f, fadeOut = 1f, t = 1f;
        float[] w = zoomWindow;
        float thumbScale = puzzle.thumbScale(w);
        float cx0 = thumbAbs[0] + thumbAbs[2] / 2f, cy0 = thumbAbs[1] + thumbAbs[3] / 2f;
        float bx0 = w[0] + w[2] / 2f, by0 = w[1] + w[3] / 2f;
        float alpha = 1f;
        switch (mode) {
            case ZOOM_IN_FADE:
                t = 0f; alpha = ease(modeT / 0.25f);          // displayPuzzleLevelAlpha: 0.25 s, UIView's default curve
                break;
            case ZOOM_IN:
                t = ease(modeT / 0.75f);
                break;
            case ZOOM_OUT:
                t = 1f - ease(modeT / 0.75f);
                break;
            case ZOOM_OUT_FADE:
                t = 0f; alpha = 1f - ease(modeT / 0.25f);     // displayPuzzleLevelAlphaOut: 0.25 s, UIView's default curve
                break;
            default:
                break;
        }
        float s = thumbScale + (1f - thumbScale) * t;
        float cx = cx0 + (160f - cx0) * t, cy = cy0 + (240f - cy0) * t;
        float bx = bx0 + (160f - bx0) * t, by = by0 + (240f - by0) * t;
        if (alpha < 1f) {
            // the original fades the puzzle's whole view (UIView alpha): its layers are flattened first and the result is faded.
            // Fading every layer on its own would show the icon through the gaps and darken it several times over.
            final int k = THUMB_SUPERSAMPLE;
            float ox = Math.round(thumbAbs[0]), oy = Math.round(thumbAbs[1]);
            Image flat = heist.platform.Host.get().createImage(Math.round(thumbAbs[2] * k), Math.round(thumbAbs[3] * k));
            Gfx gf = Gfx.of(flat);
            gf.scale(k, k);
            gf.translate(-ox, -oy);
            gf.translate(cx, cy);
            gf.scale(s, s);
            gf.translate(-bx, -by);
            puzzle.renderContent(gf);
            gf.dispose();
            Gfx gd = g.create();
            gd.setAlpha(Math.max(0f, alpha));
            gd.drawImage(flat, ox, oy, Math.round(thumbAbs[2]), Math.round(thumbAbs[3]));
            gd.dispose();
            return;
        }
        Gfx gz = g.create();
        // the window of the puzzle that maps onto the thumbnail
        if (t < 0.02f) gz.clipRect(Math.round(thumbAbs[0]), Math.round(thumbAbs[1]), Math.round(thumbAbs[2]), Math.round(thumbAbs[3]));
        gz.translate(cx, cy);
        gz.scale(s, s);
        gz.translate(-bx, -by);
        if (alpha < 1f) gz.setAlpha(Math.max(0f, alpha));
        puzzle.renderContent(gz);
        gz.dispose();
    }

    private static float ease(float t) {
        return heist.engine.Ease.inOut(Math.max(0f, Math.min(1f, t)));
    }

    // ---- tiny property animator -------------------------------------------------------------------------------------

    /** A float that animates with ease-in/out after an optional delay (like UIView animations). */
    static final class Anim {
        private float from, to, value;
        private float t, dur, delay;
        private boolean active;

        Anim(float v) { value = from = to = v; }

        void set(float v) { value = from = to = v; active = false; }

        void start(float target, float duration, float startDelay) {
            from = value;
            to = target;
            dur = Math.max(0.0001f, duration);
            delay = startDelay;
            t = 0f;
            active = true;
        }

        boolean isActive() { return active; }

        void update(float dt) {
            if (!active) return;
            if (delay > 0f) { delay -= dt; return; }
            t += dt;
            float k = Math.min(1f, t / dur);
            float e = heist.engine.Ease.inOut(k);
            value = from + (to - from) * e;
            if (t >= dur) { value = to; active = false; }
        }

        float get() { return value; }
    }
}
