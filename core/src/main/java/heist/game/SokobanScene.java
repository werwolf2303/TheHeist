package heist.game;

import heist.engine.Image;
import heist.platform.Host;

import heist.engine.Gfx;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import heist.engine.Assets;
import heist.engine.Audio;
import heist.engine.Sprite;

/**
 * Sokoban ("robot and boxes") puzzle. Rules, timings and layout follow the original controller:
 * 53pt tiles, one step per 0.25s, a 0.25s turn before a step that changes direction, boxes slide
 * in 0.25s, taps on distant cells walk an A* path that never pushes boxes.
 */
public final class SokobanScene extends PuzzleScene {
    private static final int T = 53;
    private static final float STEP = 0.25f;
    private static final String DIR = "Sokoban_images/";

    // ---- board state ------------------------------------------------------
    private byte[] map;
    private final int cols, rows;
    private float scale, originX, originY;

    private final List<Box> boxes = new ArrayList<Box>();
    private final List<int[]> goals = new ArrayList<int[]>();
    private final List<int[]> walls = new ArrayList<int[]>();
    private int startCol, startRow;

    private static final class Box {
        int col, row;
        float x, y;
        float fromX, fromY, t, delay;     // slide animation
        boolean sliding, onGoal;
        float glow;                       // 0..1 alpha of the goal glow
        float glowT = -1;                 // time since lit (for fade + flicker)
        boolean glowHidden;
        float flickAcc;
    }

    // ---- robot ------------------------------------------------------------
    private int col, row, dir = 3;        // clock position: 3 right, 6 down, 9 left, 12 up
    private float rx, ry;
    private float rFromX, rFromY, rT, rDelay;
    private boolean moving;
    /**
     * robotReroutingFeatureEnabled: the original switches it on for retina devices and off for older ones (the iPod touch 3G).
     * Off, a tap while the robot walks a path is ignored; on, it stops the robot where it is and walks to the tapped cell.
     */
    private static final boolean REROUTING = Boolean.getBoolean("heist.rerouting");
    private boolean walking;              // robotIsPathFinderAnimating
    private boolean robotPaused;          // robotIsPaused: the robot was stopped between two cells
    private boolean gridCorrecting;       // robotIsGridCorrecting: gliding back onto the nearest cell
    private float corrT, corrFromX, corrFromY;
    private List<int[]> corrWalkPath;     // the walk that follows the correction
    private int pendC, pendR;             // a single step scheduled 0.25 s after a correction
    private float pendT = -1f;
    private float piecesAlpha = 1f;       // robot, boxes and shadows while the level resets on the way out
    private float exitResetT = -1f;
    // the robot view's rotation: every turn adds +90 (clockwise), -90 or -180 degrees to it and it is never reset, while the
    // picture of the new direction is swapped in at the start of the turn
    private float spinFrom, spinTo, turnT = 1f;
    private float turnDur = STEP;        // 0.25 s for a single step, 0.24 s for the turns along a walked path
    private boolean slideLinear;          // a walked path glides at constant speed; a single step eases in and out
    private float walkClock;

    private final LinkedList<int[]> path = new LinkedList<int[]>();
    private boolean busy;
    private int moveCount;

    // ---- completion -------------------------------------------------------
    private enum Phase { PLAY, WAIT_DONE, FLICKER, FADE_OUT }
    private Phase phase = Phase.PLAY;
    private float phaseT;
    private boolean litVisible;
    private boolean boxGlowOn = true;            // the boxes' glow views flicker together during the celebration
    private FlickerSim boxFlicker, litFlicker;    // FlickerControllerBoxGoal (with sound) and the silent backdrop controller
    private Image litMask, maskScratch;
    private Gfx scratchGfx;
    private final Random rnd = new Random();
    private boolean completedSaved;

    public SokobanScene(PuzzleType type, int level) {
        super(type, level);
        int[] d = SokobanLevels.DIMS[level - 1];
        cols = d[0];
        rows = d[1];
        computeLayout();
        loadMap();
    }

    // ---- layout (mirrors initPuzzleView) -----------------------------------

    private void computeLayout() {
        final float H = 480f, W = 320f;
        int[] colTable = { 45, 40, 35, 34 };
        int[] rowTable = { 45, 40, 35, 35 };
        if (cols < 7 && rows < 10) {
            scale = 1f;
            originX = 0;
            originY = (H - rows * T) / 2f;
            return;
        }
        int tile;
        if (cols < 7) tile = rows >= 10 && rows <= 13 ? rowTable[rows - 10] : 29;
        else tile = cols <= 10 ? colTable[cols - 7] : 29;
        scale = (float) ((int) ((tile / 53.0) * 100.0)) / 100f;
        float w = cols * T * scale, h = rows * T * scale;
        if (cols < 7) {
            originX = (W - w) / 2f;
            originY = (H - h) / 2f;
        } else if (rows < 11 || rows % 2 == 0) {
            originX = 0;
            originY = (H - h) / 2f;
        } else {
            originX = level == 14 ? (W - (w - (int) (scale * 53f))) / 2f : 0;
            originY = (H - (h - 48f + 20f)) / 2f;
        }
    }

    private float colX(int c) { return c * T; }
    private float rowY(int r) { return r * T; }

    // ---- level loading ------------------------------------------------------

    private void loadMap() {
        map = SokobanLevels.map(level);
        boxes.clear();
        goals.clear();
        walls.clear();
        path.clear();
        for (int r = 0; r < SokobanLevels.GRID; r++) {
            for (int c = 0; c < SokobanLevels.GRID; c++) {
                int v = map[r * SokobanLevels.STRIDE + c];
                if (v == 1) walls.add(new int[] { c, r });
                if (v == 5 || v == 7) {
                    Box b = new Box();
                    b.col = c; b.row = r; b.x = colX(c); b.y = rowY(r);
                    boxes.add(b);
                }
                if (v == 6 || v == 7) goals.add(new int[] { c, r });
                if (v == 2) {
                    startCol = c; startRow = r;
                    map[r * SokobanLevels.STRIDE + c] = 0;
                }
            }
        }
        col = startCol; row = startRow; dir = 3;
        rx = colX(col); ry = rowY(row);
        moving = false; busy = false; moveCount = 0; turnT = 1f; spinFrom = spinTo = 0f;
        walking = robotPaused = gridCorrecting = walkStarted = false;
        corrWalkPath = null;
        pendT = -1f;
        phase = Phase.PLAY; phaseT = 0; litVisible = false; boxGlowOn = true; completedSaved = false;
        for (Box b : boxes) {
            if (isGoal(b.col, b.row)) { b.onGoal = true; b.glow = 1f; b.glowT = 99f; }
        }
    }

    /** transitionOutBegin: a walking robot is paused where it is, and the exit waits 0.1 s if it was walking or turning. */
    public float exitPause() {
        boolean wasWalking = walking, turning = turnT < 1f && turnDur == STEP;
        if (wasWalking) pauseRobot(0, 0, true);
        return wasWalking || turning ? 0.1f : 0f;
    }

    /** transitionOutPostRobotPaused: after moves the level fades out and back in at its start, 0.625 s in all. */
    public float exitReset() {
        if (phase != Phase.PLAY || moveCount <= 0) return 0f;
        Audio.play("level_reset");
        exitResetT = 0f;
        return 0.625f;
    }

    protected void onReset() {
        if (phase != Phase.PLAY) return;
        Audio.play("level_reset");
        resetKeepingRobotPose();
    }

    /** resetLevelPiecesFadeDidFinish moves the robot back to its start cell but never touches its direction or rotation. */
    private void resetKeepingRobotPose() {
        int d = dir;
        float a = spinTo;
        loadMap();
        dir = d;
        spinFrom = spinTo = a;
    }

    public void onEnter() {
        super.onEnter();
    }


    // ---- map queries ---------------------------------------------------------

    private int at(int c, int r) {
        if (c < 0 || r < 0 || c >= SokobanLevels.GRID || r >= SokobanLevels.GRID) return 1;
        return map[r * SokobanLevels.STRIDE + c];
    }

    private boolean isWall(int c, int r) { return at(c, r) == 1; }
    private boolean isBox(int c, int r) { int v = at(c, r); return v == 5 || v == 7; }

    private boolean isGoal(int c, int r) {
        for (int[] g : goals) if (g[0] == c && g[1] == r) return true;
        return false;
    }

    private Box boxAt(int c, int r) {
        for (Box b : boxes) if (b.col == c && b.row == r) return b;
        return null;
    }

    /** Box cell is stored as 5 once moved (goals are tracked separately). */
    private boolean isBoxMoveValid(int c, int r) {
        int bc = c + (c - col), br = r + (r - row);
        return !isWall(bc, br) && !isBox(bc, br);
    }

    private boolean isRobotMoveValid(int c, int r) {
        int dx = Math.abs(col - c), dy = Math.abs(row - r);
        if (!((dy == 1 && dx == 0) || (dx == 1 && dy == 0))) return false;
        if (isBox(c, r)) return isBoxMoveValid(c, r);
        return !isWall(c, r);
    }

    private int directionTo(int c, int r) {
        if (c > col) return 3;
        if (c < col) return 9;
        if (r > row) return 6;
        if (r < row) return 12;
        return dir;
    }

    // ---- input -----------------------------------------------------------------

    protected boolean handlePuzzlePress(float x, float y) {
        if (phase != Phase.PLAY) return true;
        float px = (x - originX) / scale, py = (y - originY) / scale;
        int c = (int) Math.floor(px / T), r = (int) Math.floor(py / T);
        if (px < 0 || py < 0 || c >= cols || r >= rows) return true;

        // touchesBegan:withEvent: of the original
        if (gridCorrecting) return true;
        if (turnT < 1f && turnDur == STEP) return true;        // isSingleGridAnimating: a single step's turn is running
        if (walking) {
            if (isBox(c, r)) return true;
            if (isRobotMoveValid(c, r)) return true;
            if (!REROUTING) return true;
            pauseRobot(c, r, false);                           // stop where it is, then handle the tap from there
        }
        if (robotPaused && c == col && r == row && !onGrid()) {
            // "user tapped on the gridpoint the robot is on and therefore paused": glide onto it
            startCorrection(null);
            moveCount++;
            return true;
        }
        if (isRobotMoveValid(c, r)) {
            if (!robotPaused) {
                queueStep(c, r, true);                         // a tap on the next cell: turn and slide together
                return true;
            }
            if (!onGrid()) startCorrection(null);              // first back onto the grid, then the step 0.25 s later
            pendC = c; pendR = r; pendT = STEP;
            return true;
        }
        if (isBox(c, r) || isWall(c, r)) return true;
        List<int[]> p = findPath(col, row, c, r);
        if (p != null && !p.isEmpty()) startWalk(p);
        return true;
    }

    private boolean onGrid() {
        return Math.abs(rx - colX(col)) < 0.01f && Math.abs(ry - rowY(row)) < 0.01f;
    }

    /** animateRobotWithPathFinderPath: back onto the grid first if needed (0.25 s ease-out), then the walk itself. */
    private void startWalk(List<int[]> p) {
        if (gridCorrecting) return;
        if (!onGrid()) {
            moveCount++;
            startCorrection(p);
            return;
        }
        robotPaused = false;
        walking = true;
        path.clear();
        path.addAll(p);
        startNextPathStep();
    }

    private void startCorrection(List<int[]> thenWalk) {
        gridCorrecting = true;
        corrT = 0f;
        corrFromX = rx;
        corrFromY = ry;
        corrWalkPath = thenWalk;
    }

    /**
     * pauseRobotAnimation: the robot stops where it is (a turn in progress jumps to its end), and its cell becomes the one of the
     * two it lies between that is nearer to the tapped cell. When the level is being left no cell is chosen.
     */
    private void pauseRobot(int tapC, int tapR, boolean exiting) {
        if (!walking || gridCorrecting) return;
        robotPaused = true;
        moving = false;
        busy = false;
        walking = false;
        walkStarted = false;
        path.clear();
        turnT = 1f;
        float fc = (rx - colX(0)) / T, fr = (ry - rowY(0)) / T;
        int c0 = (int) Math.floor(fc), r0 = (int) Math.floor(fr), c1 = (int) Math.ceil(fc), r1 = (int) Math.ceil(fr);
        if (exiting) { col = c0; row = r0; return; }
        double d1 = Math.hypot(tapC - c0, tapR - r0), d2 = Math.hypot(tapC - c1, tapR - r1);
        if (d2 < d1) { col = c1; row = r1; } else { col = c0; row = r0; }
    }

    // ---- A* (mirrors PathFinder: 4-neighbourhood, walls and boxes block) --------

    private List<int[]> findPath(int sx, int sy, int ex, int ey) {
        if (sx == ex && sy == ey) return null;
        final int N = SokobanLevels.GRID;
        int[][] g = new int[N][N];
        int[][] parent = new int[N][N];
        boolean[][] closed = new boolean[N][N], open = new boolean[N][N];
        for (int[] a : g) java.util.Arrays.fill(a, Integer.MAX_VALUE);
        for (int[] a : parent) java.util.Arrays.fill(a, -1);
        List<int[]> openList = new ArrayList<int[]>();
        g[sy][sx] = 0;
        openList.add(new int[] { sx, sy });
        open[sy][sx] = true;
        int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
        while (!openList.isEmpty()) {
            int best = 0, bestF = Integer.MAX_VALUE;
            for (int i = 0; i < openList.size(); i++) {
                int[] n = openList.get(i);
                int f = g[n[1]][n[0]] + Math.abs(n[0] - ex) + Math.abs(n[1] - ey);
                if (f < bestF) { bestF = f; best = i; }
            }
            int[] cur = openList.remove(best);
            open[cur[1]][cur[0]] = false;
            if (cur[0] == ex && cur[1] == ey) {
                LinkedList<int[]> out = new LinkedList<int[]>();
                int cx = ex, cy = ey;
                while (!(cx == sx && cy == sy)) {
                    out.addFirst(new int[] { cx, cy });
                    int pi = parent[cy][cx];
                    cx = pi % N;
                    cy = pi / N;
                }
                return out;
            }
            closed[cur[1]][cur[0]] = true;
            for (int[] d : dirs) {
                int nx = cur[0] + d[0], ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= N || ny >= N || closed[ny][nx]) continue;
                int v = at(nx, ny);
                if (v == 1 || v == 5 || v == 7) continue;
                int ng = g[cur[1]][cur[0]] + 1;
                if (ng < g[ny][nx]) {
                    g[ny][nx] = ng;
                    parent[ny][nx] = cur[1] * N + cur[0];
                    if (!open[ny][nx]) { open[ny][nx] = true; openList.add(new int[] { nx, ny }); }
                }
            }
        }
        return null;
    }

    // ---- stepping -----------------------------------------------------------------

    private boolean walkStarted;

    private void startNextPathStep() {
        if (path.isEmpty()) return;
        int[] n = path.removeFirst();
        queueStep(n[0], n[1], false);
    }

    private void queueStep(int c, int r, boolean single) {
        int nd = directionTo(c, r);
        boolean turn = nd != dir;
        // animateRobotWithPathFinderPath: a walked path is one position animation through all its cells at 0.25 s per
        // cell and constant speed, while the turns (0.24 s each, one per step) run next to it; a single step turns and
        // slides together as well, but with the default ease-in-out curve
        float delay = single && robotPaused ? STEP : 0f;   // animateRobotToX: delay 0.25 s if the robot was paused
        turnDur = single ? STEP : 0.24f;
        slideLinear = !single;
        if (turn) {
            spinFrom = spinTo;                 // beginsFromCurrentState: the turn starts from where the view is
            spinTo = spinFrom + turnDelta(dir, nd);
            turnT = 0f;
        }
        dir = nd;

        Box pushed = isBox(c, r) ? boxAt(c, r) : null;
        rFromX = rx; rFromY = ry;
        rT = 0f; rDelay = delay;
        boolean continuing = !single && walkStarted;
        if (!continuing) walkClock = 0f;     // startAnimating: the walk frames begin with the move (and run on through a path)
        walkStarted = !single;
        col = c; row = r;
        moving = true; busy = true;
        if (!continuing) moveCount++;        // a walked path counts as one move

        if (pushed != null) {
            int bc = c + (c - (c - (nd == 3 ? 1 : nd == 9 ? -1 : 0))), br;
            // destination = one further in the direction of travel
            int dx = nd == 3 ? 1 : nd == 9 ? -1 : 0, dy = nd == 6 ? 1 : nd == 12 ? -1 : 0;
            bc = c + dx;
            br = r + dy;
            map[pushed.row * SokobanLevels.STRIDE + pushed.col] = 0;
            map[br * SokobanLevels.STRIDE + bc] = 5;
            pushed.col = bc; pushed.row = br;
            pushed.fromX = pushed.x; pushed.fromY = pushed.y;
            pushed.t = 0f; pushed.delay = delay; pushed.sliding = true;
            boolean on = isGoal(bc, br);
            if (on) {
                if (!pushed.onGoal) { pushed.onGoal = true; pushed.glowT = 0f; }
                Audio.play("sokoban_box_on");
            } else if (pushed.onGoal) {
                pushed.onGoal = false; pushed.glow = 0f; pushed.glowT = -1f;
                Audio.play("sokoban_box_off");
            }
        }
    }

    /**
     * The rotation added to the view when the direction changes from a to b (clock positions 3, 6, 9, 12): a quarter turn the
     * short way round (clockwise is positive), and minus half a turn for a reversal. Decoded from animationToNewDirection and
     * animateRobotToNextRotation.
     */
    private static float turnDelta(int a, int b) {
        int diff = (idx(b) - idx(a) + 4) % 4;
        if (diff == 1) return (float) (Math.PI / 2);
        if (diff == 3) return (float) (-Math.PI / 2);
        return (float) -Math.PI;
    }

    private static int idx(int dir) { return dir == 3 ? 0 : dir == 6 ? 1 : dir == 9 ? 2 : 3; }

    private static float ease01(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return heist.engine.Ease.inOut(u);
    }

    private void stepFinished() {
        moving = false;
        busy = false;
        if (path.isEmpty()) { walkStarted = false; walking = false; }
        robotPaused = false;                 // animateRobotToSingleGrid: finished
        if (levelIsComplete()) {
            path.clear();
            phase = Phase.WAIT_DONE;
            phaseT = 0f;
            if (!completedSaved) {
                completedSaved = true;
                markCompleted();
            }
            return;
        }
        if (!path.isEmpty()) startNextPathStep();
    }

    private boolean levelIsComplete() {
        int n = 0;
        for (Box b : boxes) if (b.onGoal) n++;
        return n == goals.size();
    }

    // ---- update ----------------------------------------------------------------------

    public void update(float dt) {
        if (exitResetT >= 0f) {
            exitResetT += dt;
            if (exitResetT < 0.25f) piecesAlpha = 1f - ease01(exitResetT / 0.25f);
            else {
                if (exitResetT - dt < 0.25f) { resetKeepingRobotPose(); }
                piecesAlpha = Math.min(1f, ease01((exitResetT - 0.25f) / 0.25f));
                if (exitResetT >= 0.5f) { exitResetT = -1f; piecesAlpha = 1f; }
            }
        }
        // robot slide
        if (gridCorrecting) {
            corrT += dt / STEP;
            float k = heist.engine.Ease.out(Math.min(1f, corrT));
            rx = corrFromX + (colX(col) - corrFromX) * k;
            ry = corrFromY + (rowY(row) - corrFromY) * k;
            if (corrT >= 1f) {
                rx = colX(col); ry = rowY(row);
                gridCorrecting = false;
                List<int[]> w = corrWalkPath;
                corrWalkPath = null;
                if (w != null) startWalk(w);
            }
        }
        if (pendT >= 0f) {
            pendT -= dt;
            if (pendT <= 0f && !gridCorrecting) { pendT = -1f; queueStep(pendC, pendR, true); }
        }
        if (moving) {
            if (rDelay > 0) {
                rDelay -= dt;
                if (rDelay < 0) { rT -= rDelay; rDelay = 0; walkClock = rT; }   // the slide starts: frames restart
            } else {
                rT += dt;
            }
            walkClock += dt;
            float lin = Math.min(1f, rT / STEP);
            float k = slideLinear ? lin : heist.engine.Ease.inOut(lin);   // a path glides linearly, a step uses UIView's default curve
            rx = rFromX + (colX(col) - rFromX) * k;
            ry = rFromY + (rowY(row) - rFromY) * k;
            if (rT >= STEP && rDelay <= 0) { rx = colX(col); ry = rowY(row); stepFinished(); }
        }
        if (turnT < 1f) {
            turnT += dt / turnDur;
            if (turnT > 1f) turnT = 1f;
        }
        for (Box b : boxes) {
            if (b.sliding) {
                if (b.delay > 0) {
                    b.delay -= dt;
                    if (b.delay < 0) { b.t -= b.delay; b.delay = 0; }
                } else {
                    b.t += dt;
                }
                float k = heist.engine.Ease.inOut(Math.min(1f, b.t / STEP));
                b.x = b.fromX + (colX(b.col) - b.fromX) * k;
                b.y = b.fromY + (rowY(b.row) - b.fromY) * k;
                if (b.t >= STEP) { b.x = colX(b.col); b.y = rowY(b.row); b.sliding = false; }
            }
            if (b.onGoal && b.glowT >= 0 && b.glowT < 99f) {
                b.glowT += dt;
                b.glow = Math.min(1f, b.glowT / 1.0f);
                // flicker for the first half second after landing on a goal
                // SokobanBoxView randomFlicker: every 0.025 s for 0.5 s the glow is hidden when a signed random % 2 is 1
                if (b.glowT < 0.5f) {
                    b.flickAcc += dt;
                    while (b.flickAcc >= 0.025f) { b.flickAcc -= 0.025f; b.glowHidden = rnd.nextInt() % 2 == 1; }
                } else {
                    b.glowHidden = false;
                }
                if (b.glowT >= 1.0f) { b.glowT = 99f; b.glow = 1f; b.glowHidden = false; }
            }
        }
        updateCompletion(dt);
    }

    private void updateCompletion(float dt) {
        if (phase == Phase.PLAY) return;
        phaseT += dt;
        switch (phase) {
            case WAIT_DONE:
                if (phaseT >= (Audio.isSfxOn() ? 0.75f : 0f)) {
                    phase = Phase.FLICKER; phaseT = 0;
                    litVisible = false;
                    boxFlicker = new FlickerSim(0.02f, 1.0f, 2, false, Audio.isSfxOn());   // no view: its box glows start shown
                    litFlicker = new FlickerSim(0.02f, 1.0f, 2, true);
                    celebrationStarted();
                   
                }
                break;
            case FLICKER:
                boxFlicker.update(dt);
                litFlicker.update(dt);
                boxGlowOn = boxFlicker.visible();
                litVisible = litFlicker.visible();
                if (boxFlicker.finished()) {                // flickerControllerDidFinish: the lit backdrop stays, then the exit
                    boxGlowOn = true;
                    litVisible = true;
                    startBrass();
                    phase = Phase.FADE_OUT; phaseT = 0;
                }
                break;
            case FADE_OUT:
                if (phaseT >= 0.35f) finishLevel();
                break;
            default:
                break;
        }
    }

    // ---- rendering ----------------------------------------------------------------------

    protected void renderPuzzle(Gfx g) {
        float fade = 1f;
        if (phase == Phase.FADE_OUT) fade = Math.max(0f, 1f - phaseT / 0.35f);

        // the full floor fades in once the level has zoomed to full screen and out before it zooms back; while the level is
        // small only its own silhouette (floor texture masked to the level) is drawn, with the pieces on it
        if (zoomFloor < 1f) drawMasked(g, DIR + "backdrop.jpg", 1f);
        if (zoomFloor > 0f) Assets.image(DIR + "backdrop.jpg").draw(g, 0, 0, 320, 480, zoomFloor * g.getAlpha());
        if (litVisible) drawLit(g, 1f);

        Gfx g2 = g.create();
        // the puzzle itself stays fully visible (it zooms into the level icon); only the backdrop would fade
        g2.translate(originX, originY);
        g2.scale(scale, scale);

        Sprite goal = Assets.image(DIR + "goal.png");
        for (int[] gl : goals) goal.draw(g2, colX(gl[0]), rowY(gl[1]));

        // every shadow lies on the floor, below the walls, boxes and the robot
        Sprite wallShadow = Assets.image(DIR + "wall_shadow.png");
        Sprite wall = Assets.image(DIR + "wall.png");
        for (int[] w : walls) wallShadow.draw(g2, colX(w[0]) - 4, rowY(w[1]));

        Gfx gp = g2;
        g2 = gp.create();
        if (piecesAlpha < 1f) g2.setAlpha(Math.max(0f, piecesAlpha) * g2.getAlpha());
        Sprite blockShadow = Assets.image(DIR + "block_shadow.png");
        for (Box b : boxes) blockShadow.draw(g2, b.x - 3, b.y);
        Assets.image(DIR + "robot_shadow.png").draw(g2, rx, ry);
        g2.dispose();

        for (int[] w : walls) wall.draw(gp, colX(w[0]), rowY(w[1]));

        g2 = gp.create();
        if (piecesAlpha < 1f) g2.setAlpha(Math.max(0f, piecesAlpha) * g2.getAlpha());
        Sprite box = Assets.image(DIR + "box.png");
        Sprite glow = Assets.image(DIR + "boxGoal.png");
        for (Box b : boxes) {
            box.draw(g2, b.x, b.y);
            if (b.onGoal && b.glow > 0 && !b.glowHidden && boxGlowOn) glow.drawAlpha(g2, b.x - 15, b.y - 14, b.glow);
        }

        drawRobot(g2);
        g2.dispose();
        gp.dispose();
    }

    private void drawRobot(Gfx g) {
        // the walk cycle is the images 2, 3, 1 over 0.35 s; it runs while sliding or turning, otherwise the still image 1
        int frame = 1;
        if (moving && rDelay <= 0 || (moving && turnT < 1f)) frame = new int[] { 2, 3, 1 }[(int) (walkClock / (0.35f / 3f)) % 3];
        Sprite s = Assets.image(DIR + "robot" + dir + "-" + frame + ".png");
        Gfx gr = g.create();
        float a = turnT < 1f ? spinFrom + (spinTo - spinFrom) * heist.engine.Ease.inOut(turnT) : spinTo;
        gr.rotate(a, rx + T / 2f, ry + T / 2f);
        s.draw(gr, rx, ry);
        gr.dispose();
    }

    public boolean fadesFloorInZoom() { return true; }

    public boolean holdsInputWhileFading() { return true; }

    /** The level icon shows the level layout itself (levelZoomIn): its rectangle on the screen. */
    public float[] snapshotRegion() {
        return new float[] { originX, originY, cols * T * scale, rows * T * scale };
    }

    /**
     * levelZoomIn: the layout (floor(scale x 53 x columns) by floor(scale x 53 x rows) points) is fitted into the 64 point icon,
     * keeping its proportions; in a portrait layout the width is rounded down to a whole number of points.
     */
    public float thumbScale(float[] window) {
        float w = (float) Math.floor(scale * T * cols), h = (float) Math.floor(scale * T * rows);
        float k = 64f;
        if (w < h) k = (float) Math.floor(w * (64f / h));
        return k / w;
    }

    private void drawLit(Gfx g, float alpha) { drawMasked(g, DIR + "backdrop_sokoban_lit.jpg", alpha); }

    /** The given full-screen picture, cut to the level's silhouette. */
    private void drawMasked(Gfx g, String image, float alpha) {
        if (litMask == null) litMask = buildLitMask();
        if (maskScratch == null) {
            maskScratch = Host.get().createImage(320, 480);
            scratchGfx = Gfx.of(maskScratch);
        }
        maskScratch.clear(0);
        Assets.image(image).draw(scratchGfx, 0, 0, 320, 480);
        Gfx og = scratchGfx.create();
        og.setBlend(Gfx.DST_IN);
        og.drawImage(litMask, 0, 0);
        og.dispose();
        Gfx g2 = g.create();
        g2.setAlpha(alpha * g.getAlpha());            // multiplied: the whole view fades together when it zooms in or out
        g2.drawImage(maskScratch, 0, 0);
        g2.dispose();
    }

    /** White where the level interior (cells enclosed by walls in all four directions) and walls are. */
    private Image buildLitMask() {
        Image m = Host.get().createImage(320, 480);
        Gfx g = Gfx.of(m);
        g.setColor(0xFFFFFFFF);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (isWall(c, r) || insideWalls(c, r)) {
                    g.fillRect(Math.round(originX + c * T * scale), Math.round(originY + r * T * scale),
                            Math.round(T * scale) + 1, Math.round(T * scale) + 1);
                }
            }
        }
        g.dispose();
        Image soft = blur(m, 8);
        // the lit picture must not show outside the playable area: keep the soft edge only where the sharp silhouette is
        Gfx gs = Gfx.of(soft);
        gs.setBlend(Gfx.DST_IN);
        gs.drawImage(m, 0, 0);
        gs.dispose();
        return soft;
    }

    private boolean insideWalls(int c, int r) {
        return wallRay(c, r, -1, 0) && wallRay(c, r, 1, 0) && wallRay(c, r, 0, -1) && wallRay(c, r, 0, 1);
    }

    private boolean wallRay(int c, int r, int dx, int dy) {
        for (int i = 1; i < SokobanLevels.GRID; i++) {
            int cc = c + dx * i, rr = r + dy * i;
            if (cc < 0 || rr < 0 || cc >= SokobanLevels.GRID || rr >= SokobanLevels.GRID) return false;
            if (isWall(cc, rr)) return true;
        }
        return false;
    }

    private static Image blur(Image src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < a.length; i++) a[i] = a[i] >>> 24;
        for (int pass = 0; pass < 3; pass++) {
            a = box(a, w, h, radius, true);
            a = box(a, w, h, radius, false);
        }
        for (int i = 0; i < a.length; i++) a[i] = (a[i] << 24) | 0xffffff;
        Image out = Host.get().createImage(w, h);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static int[] box(int[] in, int w, int h, int r, boolean horizontal) {
        int[] out = new int[in.length];
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int l = 0; l < lines; l++) {
            long sum = 0;
            for (int i = -r; i <= r; i++) sum += in[idx(l, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
            for (int i = 0; i < len; i++) {
                out[idx(l, i, w, horizontal)] = (int) (sum / (2 * r + 1));
                int add = Math.min(len - 1, i + r + 1), sub = Math.max(0, i - r);
                sum += in[idx(l, add, w, horizontal)] - in[idx(l, sub, w, horizontal)];
            }
        }
        return out;
    }

    private static int idx(int line, int pos, int w, boolean horizontal) {
        return horizontal ? line * w + pos : pos * w + line;
    }

    /** Layout used for rendering: {originX, originY, scale}. */
    public float[] layout() { return new float[] { originX, originY, scale }; }

    /** Debug helper for tests: fast-forward the simulation. */
    public void advance(float seconds) {
        float step = 1f / 60f;
        for (float t = 0; t < seconds; t += step) update(step);
    }

    /** Debug helper for tests: the robot's grid cell. */
    public int[] robotCell() { return new int[] { col, row }; }

    public int[][] boxCells() {
        int[][] out = new int[boxes.size()][];
        for (int i = 0; i < out.length; i++) out[i] = new int[] { boxes.get(i).col, boxes.get(i).row };
        Collections.sort(java.util.Arrays.asList(out), new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]; }
        });
        return out;
    }
}
