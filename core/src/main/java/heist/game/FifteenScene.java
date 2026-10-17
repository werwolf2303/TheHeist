package heist.game;

import heist.engine.Gfx;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.PList;
import heist.engine.Sprite;

/**
 * Fifteen (sliding tiles with wiring). Drag a tile that shares a row or column with the empty cell:
 * every tile between it and the gap follows the finger, and releasing past the halfway point
 * completes the slide. Layout, timings and pin placement follow the original controller.
 */
public final class FifteenScene extends PuzzleScene {
    private static final String DIR = "FifteenPuzzle_images/";
    private static final float SLIDE = 0.15f, RETURN = 0.5f;

    private FifteenModel model;
    private final int n;
    private final float tile, gridX, gridY;
    private final boolean blankBg;

    // per-tile animated centre (pixels), indexed by tile number - 1
    private final float[] cx, cy, fromX, fromY, toX, toY, animT, animDur;
    private final boolean[] animating;

    // drag state
    private int dragTile = -1;
    private float startPX, startPY;
    private boolean horizontal;
    private float dragDelta;
    private final List<Integer> group = new ArrayList<Integer>();
    private int groupDir;      // +1 / -1 along the axis
    private boolean dragging;

    // completion
    private enum Phase { PLAY, FLICKER, HOLD, OUT }
    private Phase phase = Phase.PLAY;
    private float phaseT;
    private boolean glowVisible;
    private FlickerSim flicker;
    private final Random rnd = new Random();
    private boolean saved;

    public FifteenScene(PuzzleType type, int level) {
        super(type, level);
        Map<String, Object> all = loadSettings();
        model = new FifteenModel(PList.map(all.get("Level " + level)));
        n = model.size;
        if (n == 3) { tile = 94f; gridX = 18f; gridY = 105f; }
        else { tile = 70f; gridX = 19f; gridY = 106f; }
        blankBg = false;
        int total = n * n;
        cx = new float[total]; cy = new float[total];
        fromX = new float[total]; fromY = new float[total];
        toX = new float[total]; toY = new float[total];
        animT = new float[total]; animDur = new float[total];
        animating = new boolean[total];
        syncPositions();
    }

    private static Map<String, Object> loadSettings() {
        try {
            return PList.map(PList.parse(heist.engine.Assets.bytes("FifteenPuzzleSettings.plist")));
        } catch (IOException e) {
            throw new RuntimeException("cannot read FifteenPuzzleSettings.plist", e);
        }
    }

    private float centreX(int col) { return gridX + tile * col + tile / 2f; }
    private float centreY(int row) { return gridY + tile * row + tile / 2f; }

    private void syncPositions() {
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int t = model.tileAt(r, c);
                cx[t - 1] = centreX(c);
                cy[t - 1] = centreY(r);
                animating[t - 1] = false;
            }
        }
    }

    public void onEnter() {
        super.onEnter();
    }


    /** transitionOutBegin: after moves every tile slides back to its place (0.5 s) and the exit waits for it. */
    public float exitReset() {
        if (phase != Phase.PLAY || model.moves() <= 0) return 0f;
        Audio.play("level_reset");
        model.reset();
        for (int tile = 1; tile <= n * n; tile++) startAnim(tile, RETURN);
        return 0.5f;
    }

    protected void onReset() {
        if (phase != Phase.PLAY || dragging) return;
        Audio.play("level_reset");
        model.reset();
        syncPositions();
    }

    // ---- input ---------------------------------------------------------------

    protected boolean handlePuzzlePress(float x, float y) {
        if (phase != Phase.PLAY) return true;
        int c = (int) Math.floor((x - gridX) / tile), r = (int) Math.floor((y - gridY) / tile);
        if (c < 0 || r < 0 || c >= n || r >= n) return true;
        if (model.tileAt(r, c) == model.emptyTile) return true;
        for (boolean a : animating) if (a) return true;
        dragTile = model.tileAt(r, c);
        startPX = x; startPY = y;
        dragging = false;
        group.clear();
        return true;
    }

    protected void handlePuzzleDrag(float x, float y) {
        if (dragTile < 0 || phase != Phase.PLAY) return;
        float dx = x - startPX, dy = y - startPY;
        int[] pos = cellOf(dragTile);
        if (!dragging) {
            if (dx == 0 && dy == 0) return;
            boolean horiz = Math.abs(dx) > Math.abs(dy);
            int er = model.emptyRow(), ec = model.emptyCol();
            // the tile must share the relevant line with the gap and drag towards it
            if (horiz) {
                if (pos[0] != er) return;
                groupDir = ec > pos[1] ? 1 : -1;
                if (Math.signum(dx) != groupDir) return;
            } else {
                if (pos[1] != ec) return;
                groupDir = er > pos[0] ? 1 : -1;
                if (Math.signum(dy) != groupDir) return;
            }
            horizontal = horiz;
            dragging = true;
            buildGroup(pos);
        }
        float d = horizontal ? dx : dy;
        dragDelta = Math.max(0f, Math.min(tile, d * groupDir));
        for (int t : group) {
            int[] p = cellOf(t);
            if (horizontal) { cx[t - 1] = centreX(p[1]) + dragDelta * groupDir; cy[t - 1] = centreY(p[0]); }
            else { cx[t - 1] = centreX(p[1]); cy[t - 1] = centreY(p[0]) + dragDelta * groupDir; }
        }
    }

    protected void handlePuzzleRelease(float x, float y) {
        if (dragTile < 0) return;
        int t = dragTile;
        dragTile = -1;
        if (!dragging) return;
        dragging = false;
        int[] pos = cellOf(t);
        if (dragDelta > tile * 0.5f) {
            // complete the slide: the whole group moves one cell towards the gap
            model.move(pos[0], pos[1]);
            for (int g : group) startAnim(g, SLIDE);
        } else {
            for (int g : group) startAnimBack(g);
        }
    }

    private int[] cellOf(int tileNo) {
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) if (model.tileAt(r, c) == tileNo) return new int[] { r, c };
        return new int[] { 0, 0 };
    }

    private void buildGroup(int[] pos) {
        group.clear();
        int er = model.emptyRow(), ec = model.emptyCol();
        if (horizontal) {
            for (int c = pos[1]; c != ec; c += groupDir) group.add(model.tileAt(pos[0], c));
        } else {
            for (int r = pos[0]; r != er; r += groupDir) group.add(model.tileAt(r, pos[1]));
        }
    }

    private void startAnim(int tileNo, float dur) {
        int i = tileNo - 1;
        int[] p = cellOf(tileNo);
        fromX[i] = cx[i]; fromY[i] = cy[i];
        toX[i] = centreX(p[1]); toY[i] = centreY(p[0]);
        animT[i] = 0; animDur[i] = dur; animating[i] = true;
    }

    private void startAnimBack(int tileNo) { startAnim(tileNo, RETURN); }

    // ---- update ----------------------------------------------------------------

    public void update(float dt) {
        boolean wasAnimating = false, anyNow = false;
        for (int i = 0; i < animating.length; i++) {
            if (!animating[i]) continue;
            wasAnimating = true;
            animT[i] += dt;
            float k = Math.min(1f, animT[i] / animDur[i]);
            k = 1f - (1f - k) * (1f - k);
            cx[i] = fromX[i] + (toX[i] - fromX[i]) * k;
            cy[i] = fromY[i] + (toY[i] - fromY[i]) * k;
            if (animT[i] >= animDur[i]) { cx[i] = toX[i]; cy[i] = toY[i]; animating[i] = false; }
            else anyNow = true;
        }
        if (wasAnimating && !anyNow && phase == Phase.PLAY) afterMove();
        updateCompletion(dt);
    }

    private void afterMove() {
        if (model.isSolved()) {
            phase = Phase.FLICKER;
            phaseT = 0; glowVisible = false;
            flicker = new FlickerSim(0.02f, 1.0f, 2, true, Audio.isSfxOn());   // the glow picture: interval 0.02, modulus 2
            celebrationStarted();
            if (!saved) { saved = true; markCompleted(); }
           
        }
    }

    private void updateCompletion(float dt) {
        if (phase == Phase.PLAY) return;
        phaseT += dt;
        if (phase == Phase.FLICKER) {
            flicker.update(dt);
            glowVisible = flicker.visible();
            if (flicker.finished()) { phase = Phase.OUT; phaseT = 0; startBrass(); }   // flickerControllerDidFinish
        } else if (phase == Phase.OUT) {
            if (phaseT >= 0.35f) finishLevel();                     // fadeBackgroundOutAnimation: 0.35 s
        }
    }

    // ---- rendering ---------------------------------------------------------------

    /** The outer background fades in after the zoom and out before it (addCompleteBackground / removeCompletedBackground). */
    public boolean fadesFloorInZoom() { return true; }

    protected void renderPuzzle(Gfx g) {
        float out = phase == Phase.OUT ? Math.max(0f, 1f - phaseT / 0.35f) : 1f;
        Gfx g2 = g.create();
        // removeCompletedBackground fades only the outer backdrop images; the puzzle stays for the zoom to the icon
        Assets.image(DIR + "backdrop-top.jpg").drawAlpha(g2, 0, 0, out * zoomFloor);
        Assets.image(DIR + "backdrop-middle.jpg").draw(g2, 0, 79);
        Assets.image(DIR + "backdrop-bottom.jpg").drawAlpha(g2, 0, 413, out * zoomFloor);
        if (glowVisible) Assets.image(DIR + "backdrop-solved_glow.jpg").draw(g2, 12, 100, 295, 293);

        boolean solved = phase != Phase.PLAY;
        String emptyName = DIR + (solved
                ? (n == 3 ? "FifteenPuzzleEmptyPiece3x3_CLEAR.png" : "FifteenPuzzleEmptyPiece_CLEAR.png")
                : (n == 3 ? "FifteenPuzzleEmptyPiece3x3.png" : "FifteenPuzzleEmptyPiece.png"));
        Sprite empty = Assets.image(emptyName);

        // dragged tiles are drawn last so they sit above their neighbours
        for (int t = 1; t <= n * n; t++) if (!group.contains(t) || !dragging) drawTile(g2, t, empty);
        if (dragging) for (int t : group) drawTile(g2, t, empty);

        drawPins(g2);
        g2.dispose();
    }

    private void drawTile(Gfx g, int t, Sprite empty) {
        if (t == model.emptyTile) return;
        float x = cx[t - 1] - tile / 2f, y = cy[t - 1] - tile / 2f;
        empty.draw(g, x, y, tile, tile);
        if (model.ignored.contains(t)) return;
        Sprite s = Assets.image(DIR + String.format("puzzle%02d_%02d.png", level, t));
        s.draw(g, x, y, tile, tile);
    }

    // ---- pins ----------------------------------------------------------------------

    private void drawPins(Gfx g) {
        for (FifteenModel.Pin p : model.pins) {
            float[] pos = pinPosition(p);
            if (pos == null) continue;
            String side = pinSide(p);
            String letter = side.equals("top") ? "T" : side.equals("left") ? "L" : side.equals("right") ? "R" : "B";
            Assets.image(DIR + "PIN_UNDER_" + letter + ".png").draw(g, pos[0], pos[1]);
            boolean lit = model.pinLit(p);
            Assets.image(DIR + "PIN_OVER_" + p.color.toUpperCase() + (lit ? "_ON" : "_OFF") + ".png").draw(g, pos[0], pos[1]);
        }
    }

    private String pinSide(FifteenModel.Pin p) {
        int side = (p.edge - 1) / n;
        return side == 0 ? "top" : side == 1 ? "left" : side == 2 ? "right" : "bottom";
    }

    /** Edges run clockwise-ish: top 1..n, left n+1..2n, right 2n+1..3n, bottom 3n+1..4n. */
    private float[] pinPosition(FifteenModel.Pin p) {
        int idx = (p.edge - 1) % n;
        String side = pinSide(p);
        float along = along(p.middleType);
        float x, y;
        if (side.equals("top")) { x = gridX + tile * idx + along; y = gridY - 20; }
        else if (side.equals("bottom")) { x = gridX + tile * idx + along; y = gridY + n * tile; }
        else if (side.equals("left")) { x = gridX - 20; y = gridY + tile * idx + along; }
        else { x = gridX + n * tile; y = gridY + tile * idx + along; }
        return new float[] { x, y };
    }

    /** Offset of the pin along its edge for middle / one-third / two-third placement. */
    private float along(String middleType) {
        boolean three = n == 3;
        if ("middleOneThird".equals(middleType)) return three ? 22.5f : 13f;
        if ("middleTwoThird".equals(middleType)) return three ? 53f : 37.5f;
        return three ? 37f : 25f;
    }

    /** Debug helper for tests: current model. */
    public FifteenModel model() { return model; }

    /** Debug helper for tests: fast-forward the simulation. */
    public void advance(float seconds) {
        float step = 1f / 60f;
        for (float t = 0; t < seconds; t += step) update(step);
    }

    public float[] gridInfo() { return new float[] { gridX, gridY, tile }; }
}
