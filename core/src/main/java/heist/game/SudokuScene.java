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
 * Sudoku with rune stones. Stones are dragged from the tray onto the board: a stone only fits a cell
 * of its own colour group, and is bounced back if the same rune already sits in that row or column.
 * The level is complete when every stone is on its winning cell. Geometry follows the original: sand
 * bars 70px high at the top and 77px at the bottom, board art at y=70 and grid origin (8, 86).
 */
public final class SudokuScene extends PuzzleScene {
    private static final String DIR = "Sudoku_images/";
    private static final float GRID_X = 8f, GRID_Y = 86f;
    private static final float BOARD_Y = 70f, BOARD_H = 333f;

    private static final class Piece {
        int group, rune;            // 1-based
        int winCol, winRow;
        boolean isStatic;
        int col = -1, row = -1;     // current cell when on board
        boolean onBoard;
        float x, y;                 // frame origin (pixels)
        float homeX, homeY;
        float sx, sy, tx, ty, animT = 1f;   // animation start / target, progress 0..1
        int stone;                  // 1..7
    }

    private static final float STONE_FADE = 0.35f;
    private boolean wasRising = true;
    private float stoneInT = -1f;            // 0 .. STONE_FADE while the movable stones fade in

    private final int n;
    private final float tile, scale;
    private final String runeFont, symbols;
    private final float runeSize;
    private final String[] groupColors;
    private final List<Piece> pieces = new ArrayList<Piece>();
    private final int[] groupAt;    // group number per cell (row-major)
    private final float dragOffset = 0f;   // the stone moves with the finger (no lift); kept for the tools

    private Piece dragging;
    private float grabX, grabY;           // where inside the stone it was picked up
    private float lastCol, lastRow;
    private float grabDX, grabDY;

    // conflict flashes: x, y, remaining time
    private final List<float[]> flashes = new ArrayList<float[]>();

    private enum Phase { PLAY, WAIT, FLICKER, OUT }
    private Phase phase = Phase.PLAY;
    private float phaseT;
    private boolean swirlOn;
    private FlickerSim flicker;
    private final Random rnd = new Random();
    private boolean saved;
    private int moves;

    public SudokuScene(PuzzleType type, int level) {
        super(type, level);
        Map<String, Object> all = load();
        Map<String, Object> data = PList.map(all.get("Level " + level));
        n = PList.i(data.get("puzzleGridSize"), 5);
        tile = (float) PList.d(data.get("puzzleGridTileSize"), 50);
        scale = tile / 64f;
        runeFont = PList.s(data.get("runeBase"), "Bacata");
        runeSize = (float) PList.d(data.get("runeBaseSize"), 40);
        StringBuilder sb = new StringBuilder();
        for (Object o : PList.list(data.get("runeSymbols"))) sb.append(o);
        symbols = sb.toString();
        List<Object> colors = PList.list(data.get("groupColors"));
        groupColors = new String[colors.size()];
        for (int i = 0; i < groupColors.length; i++) groupColors[i] = String.valueOf(colors.get(i));

        List<Object> ignore = PList.list(data.get("piecesToIgnore"));
        boolean[] isIgnored = new boolean[n * n + 1];
        for (Object o : ignore) { int k = PList.i(o, 0); if (k > 0 && k <= n * n) isIgnored[k] = true; }
        String[] toks = PList.s(data.get("runesHash"), "").split(",");
        groupAt = new int[n * n];
        Random stones = new Random(level * 7919L);
        for (int i = 0; i < n * n; i++) {
            String[] gr = toks[i].trim().split("-");
            Piece p = new Piece();
            p.group = Integer.parseInt(gr[0].substring(1));
            p.rune = Integer.parseInt(gr[1].substring(1));
            p.winCol = i % n;
            p.winRow = i / n;
            p.isStatic = isIgnored[i + 1];
            p.stone = 1 + stones.nextInt(7);
            groupAt[i] = p.group;
            if (p.isStatic) {
                p.onBoard = true;
                p.col = p.winCol; p.row = p.winRow;
                p.x = cellX(p.col); p.y = cellY(p.row);
            }
            pieces.add(p);
        }
        layoutTray();
    }

    private static Map<String, Object> load() {
        try {
            return PList.map(PList.parse(heist.engine.Assets.bytes("SudokuPuzzleSettings.plist")));
        } catch (IOException e) {
            throw new RuntimeException("cannot read SudokuPuzzleSettings.plist", e);
        }
    }

    private float cellX(int c) { return GRID_X + c * tile; }
    private float cellY(int r) { return GRID_Y + r * tile; }

    private float pieceW(Piece p) {
        if (p.isStatic) return 64f * scale;
        return Assets.image(DIR + "STONE" + p.stone + ".png").w * scale;
    }

    private float pieceH(Piece p) {
        if (p.isStatic) return 64f * scale;
        return Assets.image(DIR + "STONE" + p.stone + ".png").h * scale;
    }

    // ---- tray ---------------------------------------------------------------------

    /**
     * renderPuzzlePieces: the loose stones sit on the two sand strips. The first row (above the board) holds about
     * half of them, zigzagging up and down, and is centred; with fewer than six stones the first row stays empty. The second row sits
     * just under the board and is spread over the full width.
     */
    private void layoutTray() {
        List<Piece> loose = new ArrayList<Piece>();
        for (Piece p : pieces) if (!p.isStatic) loose.add(p);
        int m = loose.size();
        if (m == 0) return;
        int a = 0;                                         // stones in the first row (none below six stones)
        if (m >= 6) {
            int half = Math.round(m / 2f);
            a = half + (half & 1) - 1;
        }
        int b = m - a;                                     // stones in the second row
        float bgTop = BOARD_Y, bgBottom = BOARD_Y + BOARD_H;
        float baseY = bgTop + (n < 7 ? 10f : 5f);
        // the vertical stagger factor of every second stone: 2.0 (a NEON float immediate: imm8 0 encodes 2.0), and on screens
        // taller than 480 pt 2.0 for boards up to 6x6 and 2.25 above that
        float f = 2f;
        // first row
        float rowW = a > 15 ? 272f : 280f;
        float step = a > 0 ? rowW / a : 0f;
        int[] xs = new int[a];
        for (int i = 0; i < a; i++) {
            float w = pieceW(loose.get(i));
            int slack = Math.abs((int) ((a - 1) * step + w - 320f));
            float spacing = step - slack / (float) a;
            xs[i] = (int) (spacing * i);
        }
        int span = a > 0 ? (int) (xs[a - 1] + pieceW(loose.get(a - 1)) - xs[0]) : 0;
        int offset = (320 - span) / 2;
        for (int i = 0; i < a; i++) {
            Piece p = loose.get(i);
            float h = pieceH(p);
            float y = zigzag(i, h, baseY, f, a) + 8f;
            p.homeX = xs[i] + offset;
            p.homeY = (int) y;
        }
        // second row
        if (b > 0) {
            int step2 = 320 / b;
            for (int j = 0; j < b; j++) {
                Piece p = loose.get(a + j);
                float w = pieceW(p);
                int slack = Math.abs((int) ((b - 1) * step2 + w - 320f));
                float spacing = step2 - slack / (float) b;
                p.homeX = (int) (spacing * j);
                // the same zigzag offset (counted over all loose stones), added to the board's bottom edge minus 10
                p.homeY = (int) (bgBottom - 10f + zigzag(a + j, pieceH(p), baseY, f, a));
            }
        }
        for (Piece p : loose) {
            p.x = p.homeX; p.y = p.homeY;
            p.onBoard = false; p.col = -1; p.row = -1;
        }
    }

    /**
     * The vertical offset the original computes for the loose stone with the given index: even stones rest above the line,
     * odd ones are lifted by about two stone heights; negative values clamp to 1, and with more than 20 stones in the
     * first row every odd stone uses 8 (480 pt screens).
     */
    private static int zigzag(int index, float h, float baseY, float f, int firstRow) {
        int v = (int) ((index % 2 == 0) ? baseY - h : baseY - f * h + 8f);
        if (v < 0) v = 1;
        if (firstRow > 20 && index % 2 == 1) v = 8;
        return v;
    }

    public void onEnter() {
        super.onEnter();
    }


    /** transitionOutBegin: after moves the stones glide back to the tray (placePieceAtHome, 0.25 s); the exit waits 0.5 s. */
    public float exitReset() {
        if (phase != Phase.PLAY || moves <= 0) return 0f;
        Audio.play("level_reset");
        List<Piece> loose = new ArrayList<Piece>();
        List<float[]> from = new ArrayList<float[]>();
        for (Piece p : pieces) if (!p.isStatic) { loose.add(p); from.add(new float[] { p.x, p.y }); }
        for (Piece p : pieces) {
            if (p.isStatic) continue;
            p.onBoard = false; p.col = -1; p.row = -1;
        }
        layoutTray();
        flashes.clear();
        moves = 0;
        for (int i = 0; i < loose.size(); i++) {
            Piece p = loose.get(i);
            p.sx = from.get(i)[0]; p.sy = from.get(i)[1]; p.tx = p.homeX; p.ty = p.homeY;
            p.x = p.sx; p.y = p.sy; p.animT = 0f;
        }
        return 0.5f;
    }

    protected void onReset() {
        if (phase != Phase.PLAY || dragging != null) return;
        Audio.play("level_reset");
        for (Piece p : pieces) {
            if (p.isStatic) continue;
            p.onBoard = false; p.col = -1; p.row = -1;
        }
        layoutTray();
        flashes.clear();
        moves = 0;
    }

    // ---- input ---------------------------------------------------------------------

    protected boolean handlePuzzlePress(float x, float y) {
        if (phase != Phase.PLAY || dragging != null) return true;
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece p = pieces.get(i);
            if (p.isStatic) continue;
            float w = pieceW(p), h = pieceH(p);
            if (x >= p.x && x < p.x + w && y >= p.y && y < p.y + h) {
                dragging = p;
                pieces.remove(i);
                pieces.add(p);     // bring to front
                lastCol = p.col; lastRow = p.row;
                p.animT = 1f;
                grabX = x - p.x; grabY = y - p.y;
                moveDragged(x, y);
                return true;
            }
        }
        return true;
    }

    protected void handlePuzzleDrag(float x, float y) {
        if (dragging != null) moveDragged(x, y);
    }

    private void moveDragged(float x, float y) {
        Piece p = dragging;
        // touchesMoved: the stone moves by the finger's movement, so the grab point stays under the pointer
        p.x = x - grabX;
        p.y = y - grabY;
    }

    protected void handlePuzzleRelease(float x, float y) {
        if (dragging == null) return;
        Piece p = dragging;
        dragging = null;
        float cx = p.x + pieceW(p) / 2f, cy = p.y + pieceH(p) / 2f;
        boolean inside = cx >= 0 && cx < 320 && cy >= BOARD_Y && cy < BOARD_Y + BOARD_H;
        if (!inside) { sendHome(p); return; }

        moves++;
        int col = (int) ((cx - GRID_X) / tile), row = (int) ((cy - GRID_Y) / tile);
        Piece occupant = pieceAt(col, row);
        boolean inGrid = col >= 0 && row >= 0 && col < n && row < n;
        boolean groupMatches = inGrid && groupAt[row * n + col] == p.group;
        if (!groupMatches) {
            conflictSound();
            returnToPrevious(p);
            if (inGrid) flashGroup(groupAt[row * n + col]);
            return;
        }
        boolean runeConflict = runeConflict(p, col, row);
        boolean blockedByStatic = occupant != null && occupant.isStatic;
        if (runeConflict || blockedByStatic) {
            conflictSound();
            returnToPrevious(p);
            flashCell(col, row);
            return;
        }
        if (occupant != null && occupant != p) sendHome(occupant);
        place(p, col, row);
        if (levelIsComplete()) {
            phase = Phase.WAIT;
            phaseT = 0;
            if (!saved) { saved = true; markCompleted(); }
        }
    }

    private Piece pieceAt(int col, int row) {
        for (Piece p : pieces) if (p.onBoard && p.col == col && p.row == row && p != dragging) return p;
        return null;
    }

    /** True if another stone with the same rune already shares the row or column. */
    private boolean runeConflict(Piece piece, int col, int row) {
        for (Piece o : pieces) {
            if (o == piece || !o.onBoard || o.rune != piece.rune) continue;
            if (o.col == col || o.row == row) return true;
        }
        return false;
    }

    private void place(Piece p, int col, int row) {
        p.col = col; p.row = row; p.onBoard = true;
        startAnim(p, cellX(col), cellY(row));
    }

    private void startAnim(Piece p, float targetX, float targetY) {
        p.sx = p.x; p.sy = p.y; p.tx = targetX; p.ty = targetY; p.animT = 0f;
    }

    private void sendHome(Piece p) {
        p.onBoard = false; p.col = -1; p.row = -1;
        startAnim(p, p.homeX, p.homeY);
    }

    private void returnToPrevious(Piece p) {
        if (lastCol >= 0 && lastRow >= 0) {
            p.col = (int) lastCol; p.row = (int) lastRow; p.onBoard = true;
            startAnim(p, cellX(p.col), cellY(p.row));
        } else {
            sendHome(p);
        }
    }

    private void conflictSound() { Audio.play("sudoku_negative"); }

    private void flashCell(int col, int row) {
        flashes.add(new float[] { cellX(col), cellY(row), 3f });
    }

    private void flashGroup(int group) {
        for (int i = 0; i < n * n; i++) if (groupAt[i] == group) flashCell(i % n, i / n);
    }

    private boolean levelIsComplete() {
        for (Piece p : pieces) {
            if (p.isStatic) continue;
            if (!(p.onBoard && p.col == p.winCol && p.row == p.winRow)) return false;
        }
        return true;
    }

    // ---- update ----------------------------------------------------------------------

    public void update(float dt) {
        // the stones come in once the background is in: the exit's fade-out (0.35 s, ease-out) played backwards
        if (wasRising && !floorRising) stoneInT = 0f;
        wasRising = floorRising;
        if (stoneInT >= 0f) {
            stoneInT += dt;
            if (stoneInT >= STONE_FADE) stoneInT = -1f;
        }
        for (Piece p : pieces) {
            if (p == dragging || p.animT >= 1f) continue;
            p.animT = Math.min(1f, p.animT + dt / 0.25f);
            float k = 1f - (1f - p.animT) * (1f - p.animT);     // ease-out
            p.x = p.sx + (p.tx - p.sx) * k;
            p.y = p.sy + (p.ty - p.sy) * k;
        }
        for (int i = flashes.size() - 1; i >= 0; i--) {
            flashes.get(i)[2] -= dt;
            if (flashes.get(i)[2] <= 0) flashes.remove(i);
        }
        if (phase == Phase.PLAY) return;
        phaseT += dt;
        if (phase == Phase.WAIT) {
            if (phaseT >= 0.5f) {
                phase = Phase.FLICKER; phaseT = 0; swirlOn = false; celebrationStarted();
                flicker = new FlickerSim(0.03f, 1.0f, 3, true, Audio.isSfxOn());   // the beam: interval 0.03, modulus 3
               
            }
        } else if (phase == Phase.FLICKER) {
            flicker.update(dt);
            swirlOn = flicker.visible();
            if (flicker.finished()) {                       // flickerControllerDidFinish: the beam goes, transitionOutBegin
                swirlOn = false;
                startBrass();
                phase = Phase.OUT;
                phaseT = 0;
            }
        } else if (phase == Phase.OUT) {
            if (phaseT >= 0.35f) finishLevel();
        }
    }

    // ---- rendering ---------------------------------------------------------------------

    /** The outer background fades in after the zoom and out before it (addCompleteBackground / removeCompletedBackground). */
    public boolean fadesFloorInZoom() { return true; }

    /** The movable stones are not there yet while the background fades in, so they cannot be grabbed. */
    public boolean holdsInputWhileFading() { return true; }

    protected void renderPuzzle(Gfx g) {
        float out = phase == Phase.OUT ? Math.max(0f, 1f - phaseT / 0.35f) : 1f;
        Gfx g2 = g.create();
        // only the outer sand strips fade away at the end; the board stays for the zoom to the level icon
        // the water wall is removed as the celebration starts. It lies behind the board and the sand strips, so it is only
        // there once the outer background is fully in: while that fades (and while the level zooms) the room shows around the
        // board, not the water
        if ((phase == Phase.PLAY || phase == Phase.WAIT) && zoomFloor >= 1f) Assets.image(DIR + "WATER.jpg").draw(g2, 0, 0, 320, 480);
        Assets.image(DIR + String.format("LVL_%02d_BASE_CROPPED.jpg", level)).draw(g2, 0, BOARD_Y);
        Assets.image(DIR + "SAND_TOP.jpg").drawAlpha(g2, 0, 0, out * zoomFloor);
        Assets.image(DIR + "SAND_BOTTOM.jpg").drawAlpha(g2, 0, 403, out * zoomFloor);

        for (Piece p : pieces) if (p.isStatic) drawPiece(g2, p);
        // the movable stones: invisible while the level zooms and while the background fades in (drawDraggablePieces gives
        // them alpha 0; they come in once the animation has finished, as the exit fade-out played backwards), and fade out with the
        // background on the way out (removeCompletedBackground)
        float stoneAlpha;
        if (floorRising || wasRising) stoneAlpha = 0f;      // wasRising: the hub has just switched it off, the fade starts with the next update
        else if (stoneInT >= 0f) stoneAlpha = Math.min(zoomFloor, 1f - heist.engine.Ease.out(1f - stoneInT / STONE_FADE));   // the exit's curve, reversed
        else stoneAlpha = zoomFloor;
        if (stoneAlpha > 0f) {
            Gfx gs = g2.create();
            if (stoneAlpha < 1f) gs.setAlpha(stoneAlpha * gs.getAlpha());
            for (Piece p : pieces) if (!p.isStatic && p != dragging) drawPiece(gs, p);
            if (dragging != null) drawPiece(gs, dragging);
            gs.dispose();
        }

        for (float[] f : flashes) {
            float t = 3f - f[2];                       // 0..3 over a 1.5s fade in + 1.5s fade out
            float a = t < 1.5f ? t / 1.5f : (3f - t) / 1.5f;
            a = 1f - (1f - Math.max(0f, Math.min(1f, a))) * (1f - Math.max(0f, Math.min(1f, a)));
            Sprite s = Assets.image(DIR + "puzzle_whiteIndicator.png");
            Gfx gf = g2.create();
            gf.setAlpha(Math.max(0f, Math.min(1f, a)));
            s.draw(gf, f[0], f[1], s.w * scale, s.h * scale);
            gf.dispose();
        }

        if (swirlOn) {
            // CRISSCROSS_BEAM: centred on the screen, turning 225 degrees (3.927 rad) over the flicker
            float turn = phase == Phase.FLICKER && flicker != null ? Math.min(1f, phaseT / flicker.duration()) : 1f;   // turns for as long as the flicker runs
            Sprite beam = Assets.image(DIR + "CRISSCROSS_BEAM.png");
            Gfx gb = g2.create();
            gb.translate(160, 240);
            gb.rotate((float) (3.927 * turn));
            beam.draw(gb, -beam.w / 2f, -beam.h / 2f);
            gb.dispose();
        }
        g2.dispose();
    }

    /** The level icon of a solved puzzle carries the red crisscross beam in the pose it ends the flicker in. */
    public void renderThumbnailOverlay(Gfx g) {
        Sprite beam = Assets.image(DIR + "CRISSCROSS_BEAM.png");
        Gfx gb = g.create();
        gb.translate(160, 240);
        gb.rotate(3.927f);
        beam.draw(gb, -beam.w / 2f, -beam.h / 2f);
        gb.dispose();
    }

    private void drawPiece(Gfx g, Piece p) {
        Gfx gp = g.create();
        float w = pieceW(p), h = pieceH(p);
        if (!p.isStatic) {
            Sprite stone = Assets.image(DIR + "STONE" + p.stone + ".png");
            stone.draw(gp, p.x, p.y, w, h);
        }
        // glyph, drawn in the 64x64 piece space and scaled like the original label
        gp.translate(p.x + w / 2f, p.y + h / 2f);
        gp.scale(scale, scale);
        gp.setFont(Assets.font(runeFont, runeSize));
        String s = p.rune >= 1 && p.rune <= symbols.length() ? String.valueOf(symbols.charAt(p.rune - 1)) : "?";
        float gx = -gp.stringWidth(s) / 2f, gy = (gp.fontAscent() - gp.fontDescent()) / 2f;
        int c = labelColor(p);
        gp.setColor(0, 0, 0, 90);
        gp.drawString(s, gx + 1.5f, gy + 1.5f);
        gp.setColor(c);
        gp.drawString(s, gx, gy);
        gp.dispose();
    }

    private int labelColor(Piece p) {
        String name = p.group >= 1 && p.group <= groupColors.length ? groupColors[p.group - 1] : "white";
        return SudokuColors.color(name, !p.isStatic);
    }

    // ---- test helpers ----------------------------------------------------------------------

    public void advance(float seconds) {
        float step = 1f / 60f;
        for (float t = 0; t < seconds; t += step) update(step);
    }

    /** Pixel centre where a stone is currently drawn (for the verifier to grab). */
    public List<float[]> looseStones() {
        List<float[]> out = new ArrayList<float[]>();
        for (Piece p : pieces) {
            if (p.isStatic) continue;
            out.add(new float[] { p.x + pieceW(p) / 2f, p.y + pieceH(p) / 2f, p.winCol, p.winRow, p.rune, p.group,
                    p.onBoard ? 1 : 0, pieceH(p) });
        }
        return out;
    }

    public float[] gridInfo() { return new float[] { GRID_X, GRID_Y, tile, dragOffset, 64f * scale }; }
}
