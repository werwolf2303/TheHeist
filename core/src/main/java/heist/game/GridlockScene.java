package heist.game;

import heist.engine.Gfx;
import java.io.IOException;
import java.util.Map;
import java.util.Random;

import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.PList;
import heist.engine.Sprite;

/**
 * Gridlock (slide the blocks, free the chip). 6x6 cells of 46pt starting at (21,108); blocks slide
 * freely along their axis between their nearest neighbours and snap to the nearest cell on release
 * (0.25s). The goal block wins by reaching column 6, half way out of the exit.
 */
public final class GridlockScene extends PuzzleScene {
    private static final String DIR = "GridlockPuzzle_images/";
    private static final float CELL = 46f, OX = 21f, OY = 108f;

    private final GridlockModel model;
    private float[] px, py, fromX, fromY, toX, toY, animT;
    private boolean[] animating;
    private int[] shadowDX, shadowDY;

    // drag
    private int dragging = -1;
    private float grabX, grabY, startPX, startPY, minPos, maxPos;
    private boolean moved;

    // completion
    private enum Phase { PLAY, WAIT, FLICKER, OUT }
    private Phase phase = Phase.PLAY;
    private float phaseT;
    private FlickerSim glowFlicker, litFlicker;   // two controllers: the goal glow (with sound) and the lit backdrop (silent)
    private boolean glowOn;               // the goal glow (flickers with sound)
    private boolean litOn;                // the lit backdrop (a second, independent flicker controller)
    private final Random rnd = new Random();
    private boolean saved;
    private float resetT = -1f;                   // the exit reset: 0 .. 0.5 s
    private float[] resetFromX, resetFromY;
    private float pieceAlpha = 1f;

    public GridlockScene(PuzzleType type, int level) {
        super(type, level);
        Map<String, Object> all = load();
        model = new GridlockModel(PList.map(all.get("Level " + level)));
        int n = model.pieces.size();
        px = new float[n]; py = new float[n]; fromX = new float[n]; fromY = new float[n];
        toX = new float[n]; toY = new float[n]; animT = new float[n];
        animating = new boolean[n];
        shadowDX = new int[n]; shadowDY = new int[n];
        for (int i = 0; i < n; i++) {
            GridlockModel.Piece p = model.pieces.get(i);
            int[] sh = shadowOffset(p.type);
            shadowDX[i] = sh[0]; shadowDY[i] = sh[1];
        }
        syncAll();
    }

    private static Map<String, Object> load() {
        try {
            return PList.map(PList.parse(heist.engine.Assets.bytes("GridlockPuzzleSettings.plist")));
        } catch (IOException e) {
            throw new RuntimeException("cannot read GridlockPuzzleSettings.plist", e);
        }
    }

    /** Shadow image offset relative to the block frame, per block type. */
    private static int[] shadowOffset(String type) {
        if (type.equals("Long-Horiz")) return new int[] { -21, -16 };
        if (type.equals("Short-Horiz")) return new int[] { -20, -11 };
        if (type.equals("Long-Vert")) return new int[] { -16, -20 };
        if (type.equals("Short-Vert")) return new int[] { -7, -15 };
        return new int[] { 0, -4 };    // Goal
    }

    private float cellX(int col) { return OX + col * CELL; }
    private float cellY(int row) { return OY + row * CELL; }

    private void syncAll() {
        for (int i = 0; i < model.pieces.size(); i++) {
            GridlockModel.Piece p = model.pieces.get(i);
            px[i] = cellX(p.col);
            py[i] = cellY(p.row);
            animating[i] = false;
        }
    }

    public void onEnter() {
        super.onEnter();
    }


    /**
     * transitionOutBegin after moves (resetLevel): the pieces fade to 0.75 over 0.125 s, slide back to their start cells over
     * 0.25 s starting at 0.125 s, and fade back to 1 over 0.125 s starting at 0.375 s. The exit waits 0.5 s for all of it.
     */
    public float exitReset() {
        if (phase != Phase.PLAY || model.moves() <= 0) return 0f;
        Audio.play("level_reset");
        int n = model.pieces.size();
        resetFromX = new float[n]; resetFromY = new float[n];
        for (int i = 0; i < n; i++) { resetFromX[i] = px[i]; resetFromY[i] = py[i]; animating[i] = false; }
        model.reset();
        resetT = 0f;
        return 0.5f;
    }

    protected void onReset() {
        if (phase != Phase.PLAY || dragging >= 0 || resetT >= 0f) return;
        Audio.play("level_reset");
        model.reset();
        syncAll();
    }

    // ---- sprites ---------------------------------------------------------------

    private Sprite pieceSprite(GridlockModel.Piece p) {
        if (p.goal) return Assets.image(DIR + String.format("goal%02d.png", level));
        return Assets.image(DIR + p.type + ".png");
    }

    private Sprite shadowSprite(GridlockModel.Piece p) {
        return Assets.image(DIR + (p.goal ? "goal_UNDER.png" : p.type + "_UNDER.png"));
    }

    // ---- input -------------------------------------------------------------------

    protected boolean handlePuzzlePress(float x, float y) {
        if (phase != Phase.PLAY || dragging >= 0 || resetT >= 0f) return true;
        for (int i = model.pieces.size() - 1; i >= 0; i--) {
            GridlockModel.Piece p = model.pieces.get(i);
            Sprite s = pieceSprite(p);
            if (x >= px[i] && x < px[i] + s.w && y >= py[i] && y < py[i] + s.h) {
                dragging = i;
                animating[i] = false;
                grabX = x; grabY = y;
                startPX = px[i]; startPY = py[i];
                moved = false;
                computeRange(i);
                return true;
            }
        }
        return true;
    }

    /** Free travel limits (in pixels) along the block's axis, from its nearest neighbours. */
    private void computeRange(int i) {
        GridlockModel.Piece p = model.pieces.get(i);
        int neg = model.freeSpace(i, -1), pos = model.freeSpace(i, 1);
        if (p.horizontal) {
            minPos = cellX(p.col - neg);
            maxPos = cellX(p.col + pos);
        } else {
            minPos = cellY(p.row - neg);
            maxPos = cellY(p.row + pos);
        }
    }

    protected void handlePuzzleDrag(float x, float y) {
        if (dragging < 0) return;
        GridlockModel.Piece p = model.pieces.get(dragging);
        if (p.horizontal) {
            px[dragging] = Math.max(minPos, Math.min(maxPos, startPX + (x - grabX)));
        } else {
            py[dragging] = Math.max(minPos, Math.min(maxPos, startPY + (y - grabY)));
        }
        moved = true;
    }

    protected void handlePuzzleRelease(float x, float y) {
        if (dragging < 0) return;
        int i = dragging;
        dragging = -1;
        GridlockModel.Piece p = model.pieces.get(i);
        int oldCol = p.col, oldRow = p.row;
        if (p.horizontal) p.col = Math.round((px[i] - OX) / CELL);
        else p.row = Math.round((py[i] - OY) / CELL);
        if (p.col != oldCol || p.row != oldRow) {
            model.countMove();
        }
        fromX[i] = px[i]; fromY[i] = py[i];
        toX[i] = cellX(p.col); toY[i] = cellY(p.row);
        animT[i] = 0; animating[i] = true;
        if (p.goal && model.isSolved() && !saved) {
            saved = true;
            markCompleted();
            phase = Phase.WAIT;
            phaseT = 0;
        }
    }

    // ---- update -------------------------------------------------------------------

    public void update(float dt) {
        if (resetT >= 0f) {
            resetT += dt;
            float t = resetT;
            pieceAlpha = t < 0.125f ? 1f - 0.25f * (t / 0.125f) : t < 0.375f ? 0.75f : 0.75f + 0.25f * Math.min(1f, (t - 0.375f) / 0.125f);
            float k = heist.engine.Ease.inOut(Math.max(0f, Math.min(1f, (t - 0.125f) / 0.25f)));
            for (int i = 0; i < px.length; i++) {
                GridlockModel.Piece p = model.pieces.get(i);
                px[i] = resetFromX[i] + (cellX(p.col) - resetFromX[i]) * k;
                py[i] = resetFromY[i] + (cellY(p.row) - resetFromY[i]) * k;
            }
            if (resetT >= 0.5f) { resetT = -1f; pieceAlpha = 1f; syncAll(); }
        }
        for (int i = 0; i < animating.length; i++) {
            if (!animating[i]) continue;
            animT[i] += dt;
            float k = Math.min(1f, animT[i] / 0.25f);
            k = 1f - (1f - k) * (1f - k);
            px[i] = fromX[i] + (toX[i] - fromX[i]) * k;
            py[i] = fromY[i] + (toY[i] - fromY[i]) * k;
            if (animT[i] >= 0.25f) { px[i] = toX[i]; py[i] = toY[i]; animating[i] = false; }
        }
        if (phase == Phase.PLAY) return;
        phaseT += dt;
        if (phase == Phase.WAIT) {
            if (phaseT >= 0.25f) {
                phase = Phase.FLICKER; phaseT = 0; celebrationStarted();
                glowFlicker = new FlickerSim(0.02f, 1.0f, 2, true, Audio.isSfxOn());
                litFlicker = new FlickerSim(0.02f, 1.0f, 2, true);
            }
        } else if (phase == Phase.FLICKER) {
            glowFlicker.update(dt);
            litFlicker.update(dt);
            glowOn = glowFlicker.visible();
            litOn = litFlicker.visible();
            if (glowFlicker.finished()) {                   // flickerControllerDidFinish: beginGlowAnimationDidStop
                glowOn = true; litOn = true;
                startBrass();
                phase = Phase.OUT; phaseT = 0;
            }
        } else if (phase == Phase.OUT) {
            if (phaseT >= 0.35f) finishLevel();
        }
    }

    // ---- rendering ----------------------------------------------------------------

    protected void renderPuzzle(Gfx g) {
        float out = phase == Phase.OUT ? Math.max(0f, 1f - phaseT / 0.35f) : 1f;
        Gfx g2 = g.create();
        // removeCompletedBackground fades only the outer top and bottom backdrop images; the puzzle itself stays put
        // (it is what zooms into the level icon afterwards)
        Assets.image(DIR + "backdrop-top.jpg").drawAlpha(g2, 0, 0, out * zoomFloor);
        Assets.image(DIR + "backdrop-middle.jpg").draw(g2, 0, 84);
        Assets.image(DIR + "backdrop-bottom.jpg").drawAlpha(g2, 0, 401, out * zoomFloor);
        if (litOn) Assets.image(DIR + "backdrop-lit.png").draw(g2, 36, 122);

        int n = model.pieces.size();
        if (pieceAlpha < 1f) g2.setAlpha(pieceAlpha * g2.getAlpha());       // the pieces and their shadows fade together
        for (int i = 0; i < n; i++) shadowSprite(model.pieces.get(i)).draw(g2, px[i] + shadowDX[i], py[i] + shadowDY[i]);
        for (int i = 0; i < n; i++) {
            GridlockModel.Piece p = model.pieces.get(i);
            pieceSprite(p).draw(g2, px[i], py[i]);
            if (p.goal && glowOn) {
                Sprite glow = Assets.image(DIR + "goal-glow" + level + ".png");
                glow.draw(g2, px[i] - 7, py[i] - 7);
            }
        }
        g2.dispose();
    }

    public boolean fadesFloorInZoom() { return true; }

    // ---- test helpers --------------------------------------------------------------

    public GridlockModel model() { return model; }

    public float[] gridInfo() { return new float[] { OX, OY, CELL }; }

    public float pieceX(int i) { return px[i]; }
    public float pieceY(int i) { return py[i]; }

    public void advance(float seconds) {
        float step = 1f / 60f;
        for (float t = 0; t < seconds; t += step) update(step);
    }
}
