package heist.desktop.tools;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import heist.engine.Assets;
import heist.engine.Save;
import heist.engine.PList;
import heist.game.FifteenModel;
import heist.game.FifteenScene;
import heist.game.GridlockScene;
import heist.game.Progress;
import heist.game.PuzzleType;
import heist.game.SudokuModel;
import heist.game.SudokuScene;
import heist.game.SokobanLevels;
import heist.game.SokobanScene;

/**
 * Solves every puzzle level (BFS for Sokoban, replay for the rest) and checks each one registers as complete.
 * Usage: LevelVerifier &lt;assetsDir&gt;
 */
public final class LevelVerifier {
    private LevelVerifier() {}

    private static final int N = SokobanLevels.GRID;
    private static final int[] DX = { 0, 1, 0, -1 }, DY = { -1, 0, 1, 0 };

    public static void main(String[] args) throws Exception {
        ToolSetup.init(new File(args[0]));
        PuzzleType sok = PuzzleType.byName("Sokoban");
        int failures = 0;
        for (int lv = 1; lv <= SokobanLevels.COUNT; lv++) {
            byte[] map = SokobanLevels.map(lv);
            List<int[]> moves = solve(map);
            if (moves == null) {
                System.out.println("Sokoban " + lv + ": NO SOLUTION FOUND");
                failures++;
                continue;
            }
            boolean replayed = replay(sok, lv, moves);
            System.out.println("Sokoban " + lv + ": solvable in " + moves.size() + " walking steps, replay "
                    + (replayed ? "OK" : "FAILED"));
            if (!replayed) failures++;
        }
        PuzzleType fif = PuzzleType.byName("Fifteen");
        Map<String, Object> fifSettings = PList.map(PList.parse(Assets.bytes("FifteenPuzzleSettings.plist")));
        for (int lv = 1; lv <= fif.levels; lv++) {
            Map<String, Object> data = PList.map(fifSettings.get("Level " + lv));
            boolean legal = FifteenModel.scrambleIsLegal(data);
            boolean ok = replayFifteen(fif, lv, data);
            System.out.println("Fifteen " + lv + ": scramble " + (legal ? "fully legal" : "has skipped (off-board) steps")
                    + ", undone by drags " + (ok ? "OK" : "FAILED"));
            if (!ok) failures++;
        }
        PuzzleType grid = PuzzleType.byName("Gridlock");
        for (int lv = 1; lv <= grid.levels; lv++) {
            GridlockScene gs = new GridlockScene(grid, lv);
            gs.onEnter();
            boolean valid = gs.model().isValid();
            List<int[]> moves = valid ? gs.model().solve() : null;
            boolean ok = false;
            if (moves != null) {
                float[] gi = gs.gridInfo();
                for (int[] mv : moves) {
                    int i = mv[0];
                    float x = gs.pieceX(i) + 8, y = gs.pieceY(i) + 8;
                    boolean horiz = gs.model().pieces.get(i).horizontal;
                    float tx = horiz ? x + mv[1] * gi[2] : x, ty = horiz ? y : y + mv[1] * gi[2];
                    gs.pointerPressed(x, y);
                    gs.pointerDragged((x + tx) / 2, (y + ty) / 2);
                    gs.pointerDragged(tx, ty);
                    gs.pointerReleased(tx, ty);
                    gs.advance(0.3f);
                }
                gs.advance(0.3f);
                ok = Progress.isCompleted(grid, lv);
            }
            System.out.println("Gridlock " + lv + ": layout " + (valid ? "valid" : "INVALID") + ", "
                    + (moves == null ? "NO SOLUTION" : "solved in " + moves.size() + " slides, replay " + (ok ? "OK" : "FAILED")));
            if (!ok) failures++;
        }
        PuzzleType sud = PuzzleType.byName("Sudoku");
        Map<String, Object> sudSettings = PList.map(PList.parse(Assets.bytes("SudokuPuzzleSettings.plist")));
        for (int lv = 1; lv <= sud.levels; lv++) {
            SudokuModel sm = new SudokuModel(PList.map(sudSettings.get("Level " + lv)));
            boolean valid = sm.solutionIsValid();
            boolean ok = valid && replaySudoku(sud, lv);
            System.out.println("Sudoku " + lv + ": stored solution " + (valid ? "valid" : "INVALID") + ", stones dragged home "
                    + (ok ? "OK" : "FAILED"));
            if (!ok) failures++;
        }
        System.out.println(failures == 0 ? "ALL LEVELS OK" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Returns true if replaying solution taps marks the level completed. */
    private static boolean replay(PuzzleType t, int lv, List<int[]> cells) throws Exception {
        SokobanScene s = new SokobanScene(t, lv);
        s.onEnter();
        float[] lay = s.layout();
        for (int[] c : cells) {
            float x = lay[0] + (c[0] + 0.5f) * 53 * lay[2], y = lay[1] + (c[1] + 0.5f) * 53 * lay[2];
            s.pointerPressed(x, y);
            s.pointerReleased(x, y);
            s.advance(0.8f);
        }
        s.advance(0.2f);
        return Progress.isCompleted(t, lv);
    }

    /** Returns true if un-scrambling by drag marks the level completed. */
    private static boolean replayFifteen(PuzzleType t, int lv, Map<String, Object> data) throws Exception {
        FifteenScene s = new FifteenScene(t, lv);
        s.onEnter();
        float[] gi = s.gridInfo();
        FifteenModel m = s.model();
        List<Object> scramble = PList.list(data.get("scramble"));
        boolean[] applied = FifteenModel.scrambleApplied(data);
        for (int i = scramble.size() - 1; i >= 0; i--) {
            if (!applied[i]) continue;   // skipped by the game as well
            String d = String.valueOf(scramble.get(i));
            // the empty cell moved in direction d; undo = move it back, i.e. drag the tile on the
            // far side of the gap towards the gap.
            int dr = d.equals("up") ? 1 : d.equals("down") ? -1 : 0, dc = d.equals("left") ? 1 : d.equals("right") ? -1 : 0;
            int er = m.emptyRow(), ec = m.emptyCol();
            int tr = er + dr, tc = ec + dc;
            float px = gi[0] + (tc + 0.5f) * gi[2], py = gi[1] + (tr + 0.5f) * gi[2];
            s.pointerPressed(px, py);
            s.pointerDragged(px - dc * gi[2] * 0.2f, py - dr * gi[2] * 0.2f);
            s.pointerDragged(px - dc * gi[2] * 0.9f, py - dr * gi[2] * 0.9f);
            s.pointerReleased(px - dc * gi[2] * 0.9f, py - dr * gi[2] * 0.9f);
            s.advance(0.3f);
        }
        s.advance(0.5f);
        return m.isSolved() && Progress.isCompleted(t, lv);
    }

    /** Returns true if dragging every loose stone to its cell marks the level completed. */
    private static boolean replaySudoku(PuzzleType t, int lv) throws Exception {
        SudokuScene s = new SudokuScene(t, lv);
        s.onEnter();
        float[] gi = s.gridInfo();
        for (int guard = 0; guard < 200; guard++) {
            List<float[]> stones = s.looseStones();
            float[] pick = null;
            for (int i = stones.size() - 1; i >= 0; i--) {
                if (stones.get(i)[6] == 0) { pick = stones.get(i); break; }
            }
            if (pick == null) break;
            float tx = gi[0] + (pick[2] + 0.5f) * gi[2];
            float ty = gi[1] + (pick[3] + 0.5f) * gi[2] + gi[3] * pick[7];   // the stone rides above the finger
            s.pointerPressed(pick[0], pick[1]);
            s.pointerDragged((pick[0] + tx) / 2, (pick[1] + ty) / 2);
            s.pointerDragged(tx, ty);
            s.pointerReleased(tx, ty);
            s.advance(0.4f);
        }
        s.advance(0.6f);
        return Progress.isCompleted(t, lv);
    }

    // ---- push-based BFS ------------------------------------------------------

    private static final class State {
        final int player;           // cell index
        final String boxes;         // sorted box cell list
        final State prev;
        final int[] step;           // cells the robot walked to reach this state (last = box cell pushed from)
        State(int player, String boxes, State prev, int[] step) {
            this.player = player; this.boxes = boxes; this.prev = prev; this.step = step;
        }
    }

    static List<int[]> solve(byte[] map) {
        boolean[][] wall = new boolean[N][N], goal = new boolean[N][N];
        Set<Integer> boxes = new TreeSet<Integer>();
        int player = -1;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                int v = map[r * SokobanLevels.STRIDE + c];
                wall[r][c] = v == 1;
                if (v == 6 || v == 7) goal[r][c] = true;
                if (v == 5 || v == 7) boxes.add(r * N + c);
                if (v == 2) player = r * N + c;
            }
        }
        ArrayDeque<State> queue = new ArrayDeque<State>();
        Set<String> seen = new HashSet<String>();
        State start = new State(player, key(boxes), null, new int[0]);
        queue.add(start);
        seen.add(norm(player, boxes, wall));
        while (!queue.isEmpty()) {
            State s = queue.poll();
            Set<Integer> bs = parse(s.boxes);
            if (done(bs, goal)) return path(s);
            // reachable cells for the player without pushing
            Map<Integer, Integer> from = new HashMap<Integer, Integer>();
            ArrayDeque<Integer> q = new ArrayDeque<Integer>();
            q.add(s.player);
            from.put(s.player, -1);
            while (!q.isEmpty()) {
                int p = q.poll();
                for (int d = 0; d < 4; d++) {
                    int nx = p % N + DX[d], ny = p / N + DY[d];
                    if (nx < 0 || ny < 0 || nx >= N || ny >= N || wall[ny][nx]) continue;
                    int np = ny * N + nx;
                    if (bs.contains(np) || from.containsKey(np)) continue;
                    from.put(np, p);
                    q.add(np);
                }
            }
            for (int b : bs) {
                for (int d = 0; d < 4; d++) {
                    int bx = b % N, by = b / N;
                    int px = bx - DX[d], py = by - DY[d];
                    int tx = bx + DX[d], ty = by + DY[d];
                    if (px < 0 || py < 0 || px >= N || py >= N || tx < 0 || ty < 0 || tx >= N || ty >= N) continue;
                    int pp = py * N + px, tt = ty * N + tx;
                    if (!from.containsKey(pp) || wall[ty][tx] || bs.contains(tt)) continue;
                    Set<Integer> nb = new TreeSet<Integer>(bs);
                    nb.remove(b);
                    nb.add(tt);
                    String nk = norm(b, nb, wall);
                    if (!seen.add(nk)) continue;
                    // walk to pp, then step onto b (push)
                    java.util.LinkedList<Integer> walk = new java.util.LinkedList<Integer>();
                    for (int c = pp; c != -1; c = from.get(c)) walk.addFirst(c);
                    int[] steps = new int[walk.size()];
                    int i = 0;
                    for (int c : walk) steps[i++] = c;
                    // drop the start cell (already there), keep walk cells then the pushed-box cell
                    int[] full = new int[steps.length];
                    System.arraycopy(steps, 1, full, 0, steps.length - 1);
                    full[full.length - 1] = b;
                    queue.add(new State(b, key(nb), s, full));
                }
            }
        }
        return null;
    }

    private static boolean done(Set<Integer> boxes, boolean[][] goal) {
        for (int b : boxes) if (!goal[b / N][b % N]) return false;
        return true;
    }

    private static List<int[]> path(State s) {
        java.util.LinkedList<int[]> out = new java.util.LinkedList<int[]>();
        for (State x = s; x.prev != null; x = x.prev) {
            for (int i = x.step.length - 1; i >= 0; i--) out.addFirst(new int[] { x.step[i] % N, x.step[i] / N });
        }
        return out;
    }

    private static String key(Set<Integer> boxes) {
        StringBuilder sb = new StringBuilder();
        for (int b : boxes) sb.append(b).append(',');
        return sb.toString();
    }

    private static Set<Integer> parse(String k) {
        Set<Integer> s = new TreeSet<Integer>();
        for (String p : k.split(",")) if (!p.isEmpty()) s.add(Integer.parseInt(p));
        return s;
    }

    // normalising player to a canonical reachable cell would be tighter but this is fast enough
    private static String norm(int player, Set<Integer> boxes, boolean[][] wall) {
        return player + "|" + key(boxes);
    }
}
