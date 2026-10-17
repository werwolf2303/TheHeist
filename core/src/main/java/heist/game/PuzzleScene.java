package heist.game;

import heist.engine.Gfx;
import heist.engine.Key;
import java.io.IOException;

import heist.engine.Scene;
import heist.engine.ui.NibLoader;
import heist.engine.ui.UiInput;
import heist.engine.ui.UiView;

/**
 * Base for the four puzzle screens. It owns the GameUIOverlay.nib bar (back and reset buttons)
 * that the original draws over every puzzle.
 */
public abstract class PuzzleScene extends Scene {
    protected final PuzzleType type;
    protected final int level;
    private final UiInput overlayInput = new UiInput();
    private UiView overlay;
    /** The level number shown in the level list (the data level is {@link #level}, see PuzzleType.dataLevel). */
    protected int displayLevel;
    private Disarm.Result completion;
    private long startedAt;
    private boolean finished;
    /** Overlay fade (0..1), driven by the hub while zooming in/out. */
    public float overlayAlpha = 1f;
    public boolean interactive = true;
    /** 0..1 visibility of the full-screen floor while the hub zooms the puzzle in and out (only used when {@link #fadesFloorInZoom()}). */
    public float zoomFloor = 1f;
    /** True while the outer background is still to fade in (and during the zoom in); false once it is in, and while it fades out. */
    public boolean floorRising = true;

    protected PuzzleScene(PuzzleType type, int level) {
        this.type = type;
        this.level = level;
        this.displayLevel = level;
    }

    public void setDisplayLevel(int n) { this.displayLevel = n; }
    public int displayLevel() { return displayLevel; }
    public PuzzleType type() { return type; }

    /** Records the completion (points, disarm promotion) once; returns the progression result. */
    protected Disarm.Result markCompleted() {
        if (completion == null) {
            double secs = startedAt == 0 ? 0 : (System.nanoTime() - startedAt) / 1e9;
            completion = Progress.setCompleted(type, displayLevel, secs);
        }
        return completion;
    }

    public Disarm.Result completionResult() { return completion; }

    private boolean brassStarted;

    /** levelCompleted: the game music stops as the celebration (the flicker) starts. */
    protected void celebrationStarted() { heist.engine.Audio.stopGameMusic(); }

    /** puzzleControllerWillBeginTransitionOutLevelCompleted: the brass plays once, as the end fade begins. */
    protected void startBrass() {
        if (brassStarted) return;
        brassStarted = true;
        if (Nav.hub() != null) Nav.hub().beginBrass();
        else heist.engine.Audio.play("level_completion_brass");
    }

    /** The puzzle's celebration is over: hand control back to the hub (zoom out, badge, level-up...). */
    protected void finishLevel() {
        if (finished) return;
        finished = true;
        if (Nav.hub() != null) Nav.hub().levelFinished(this);
        else Nav.backToMenu();
    }

    /** Region (x, y, w, h) of the 320x480 screen that the level thumbnail shows; from Puzzles.plist. */
    public float[] snapshotRegion() {
        float oy = 78, h = 320;
        try {
            java.util.Map<String, Object> root = heist.engine.PList.map(heist.engine.PList.parse(heist.engine.Assets.bytes("Puzzles.plist")));
            for (Object o : heist.engine.PList.list(root.get("Puzzles"))) {
                java.util.Map<String, Object> m = heist.engine.PList.map(o);
                if (type.name.equals(m.get("name"))) {
                    oy = heist.engine.PList.i(m.get("zoomOriginY"), 78);
                    h = heist.engine.PList.i(m.get("zoomHeight"), 320);
                }
            }
        } catch (IOException ignored) { }
        return new float[] { 0, oy, 320, h };
    }

    /** Draws the puzzle without the back/reset overlay (used for zoom transitions and thumbnails). */
    public void renderContent(Gfx g) { renderPuzzle(g); }

    /** Leaving the puzzle: pauses whatever is moving. Returns the seconds to wait before {@link #exitReset()}. */
    public float exitPause() { return 0f; }

    /** Leaving the puzzle: puts the level back to its start if it was changed. Returns the seconds to wait afterwards. */
    public float exitReset() { return 0f; }

    /** True if the puzzle keeps only its middle part while zooming and fades the outer background in after the zoom and out before it. */
    public boolean fadesFloorInZoom() { return false; }

    /** True if the puzzle cannot be touched while its background fades in (Sokoban's level and floor fade in together). */
    public boolean holdsInputWhileFading() { return false; }

    /** Scale between the puzzle and its level icon (points of icon per point of puzzle) for the given window. */
    public float thumbScale(float[] window) { return 64f / 320f; }

    /** Extra drawing that belongs in the saved picture of the finished puzzle (the level icon) but not in the live view. */
    public void renderThumbnailOverlay(Gfx g) {}

    /** addCompleteBackgroundDidFinish: the time a level takes is counted from here (0.5 s after the zoom in ended). */
    public void beginTiming() { if (startedAt == 0) startedAt = System.nanoTime(); }

    public void onEnter() {
        try {
            NibLoader.Result r = NibLoader.load("GameUIOverlay");
            overlay = r.view();
            overlayInput.setRoot(overlay);
            overlayInput.bind(r);
            overlayInput.on("backButtonPressed:", new Runnable() { public void run() { onBack(); } });
            overlayInput.on("resetButtonPressed:", new Runnable() { public void run() { onReset(); } });
        } catch (IOException e) {
            System.err.println("[ui] cannot load GameUIOverlay.nib: " + e.getMessage());
        }
    }

    protected void onBack() {
        if (!interactive) return;
        if (Nav.hub() != null) Nav.hub().puzzleBack(this);
        else Nav.backToMenu();
    }

    protected abstract void onReset();

    protected abstract void renderPuzzle(Gfx g);

    protected boolean handlePuzzlePress(float x, float y) { return false; }
    protected void handlePuzzleDrag(float x, float y) {}
    protected void handlePuzzleRelease(float x, float y) {}

    public final void render(Gfx g) {
        renderPuzzle(g);
        if (overlayAlpha > 0f) {
            Gfx go = g.create();
            if (overlayAlpha < 1f) go.setAlpha(overlayAlpha);
            overlayInput.render(go);
            go.dispose();
        }
    }

    public final void pointerPressed(float x, float y) {
        if (!interactive) return;
        if (!overlayInput.press(x, y)) handlePuzzlePress(x, y);
    }

    public final void pointerDragged(float x, float y) {
        if (!interactive) return;                 // a touch that began while the puzzle was still zooming in is not a gesture
        if (!overlayInput.isPressing()) handlePuzzleDrag(x, y);
    }

    public final void pointerReleased(float x, float y) {
        if (!interactive) return;
        if (!overlayInput.release(x, y)) handlePuzzleRelease(x, y);
    }

    public void keyPressed(Key key) {
        if (key == Key.BACK) onBack();
    }
}
