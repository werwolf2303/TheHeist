package heist.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import heist.engine.PList;

/**
 * Sliding-block "unblock the exit" puzzle on a 6x6 grid. Pieces slide only along their long axis;
 * the goal piece (a horizontal 2-cell block) wins by reaching the right-hand edge.
 */
public final class GridlockModel {
    public static final int N = 6;

    public static final class Piece {
        public final String type;
        public final boolean horizontal, goal;
        public final int len;
        public int col, row; // 0-based top-left cell

        Piece(String type, int col, int row) {
            this.type = type;
            this.goal = type.equals("Goal");
            this.horizontal = goal || type.endsWith("Horiz");
            this.len = type.startsWith("Long") ? 3 : 2;
            this.col = col;
            this.row = row;
        }

        public boolean covers(int c, int r) {
            return horizontal ? (r == row && c >= col && c < col + len) : (c == col && r >= row && r < row + len);
        }
    }

    public final List<Piece> pieces = new ArrayList<Piece>();
    private final int[] startCols, startRows;
    private int moves;

    public GridlockModel(Map<String, Object> level) {
        for (Object o : PList.list(level.get("pieces"))) {
            Map<String, Object> m = PList.map(o);
            pieces.add(new Piece(PList.s(m.get("pieceType"), "Short-Horiz"),
                    PList.i(m.get("column"), 1) - 1, PList.i(m.get("row"), 1) - 1));
        }
        startCols = new int[pieces.size()];
        startRows = new int[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            startCols[i] = pieces.get(i).col;
            startRows[i] = pieces.get(i).row;
        }
    }

    public void reset() {
        for (int i = 0; i < pieces.size(); i++) {
            pieces.get(i).col = startCols[i];
            pieces.get(i).row = startRows[i];
        }
        moves = 0;
    }

    public int moves() { return moves; }
    public void countMove() { moves++; }

    public Piece goalPiece() {
        for (Piece p : pieces) if (p.goal) return p;
        return null;
    }

    /** The goal block is solved once it has slid out to column 6 (half of it past the board edge). */
    public boolean isSolved() {
        Piece g = goalPiece();
        return g != null && g.col == N - 1;
    }

    /** True if the layout is geometrically valid: in bounds, no overlaps, exactly one goal. */
    public boolean isValid() {
        int[][] occ = new int[N][N];
        int goals = 0;
        for (Piece p : pieces) {
            if (p.goal) goals++;
            for (int k = 0; k < p.len; k++) {
                int c = p.horizontal ? p.col + k : p.col, r = p.horizontal ? p.row : p.row + k;
                if (c < 0 || r < 0 || c >= N || r >= N || occ[r][c]++ != 0) return false;
            }
        }
        return goals == 1;
    }

    /** Which piece covers the cell (c, r)? -1 if free. */
    public int pieceAt(int c, int r, int ignore) {
        for (int i = 0; i < pieces.size(); i++) {
            if (i != ignore && pieces.get(i).covers(c, r)) return i;
        }
        return -1;
    }

    /** How far piece i may slide in the negative (-1) or positive (+1) direction. */
    public int freeSpace(int i, int dir) {
        Piece p = pieces.get(i);
        int steps = 0;
        int c = p.col, r = p.row;
        while (true) {
            int nc, nr;
            if (p.horizontal) { nc = dir < 0 ? c - 1 : c + p.len; nr = r; if (dir < 0) c--; else c++; }
            else { nr = dir < 0 ? r - 1 : r + p.len; nc = c; if (dir < 0) r--; else r++; }
            // the goal block may leave the board through the exit: one cell beyond the right edge
            boolean exitCell = p.goal && dir > 0 && nc == N && nr >= 0 && nr < N;
            if (!exitCell && (nc < 0 || nr < 0 || nc >= N || nr >= N || pieceAt(nc, nr, i) >= 0)) break;
            steps++;
        }
        return steps;
    }

    /**
     * Breadth-first solve. Returns the shortest list of single-cell slides as {piece, direction}
     * pairs (direction -1 or +1 along the piece's axis), or null if unsolvable.
     */
    public List<int[]> solve() {
        int n = pieces.size();
        int[] start = new int[n];
        for (int i = 0; i < n; i++) start[i] = pieces.get(i).horizontal ? pieces.get(i).col : pieces.get(i).row;
        int[] saveC = new int[n], saveR = new int[n];
        for (int i = 0; i < n; i++) { saveC[i] = pieces.get(i).col; saveR[i] = pieces.get(i).row; }

        java.util.Map<String, String> parent = new java.util.HashMap<String, String>();
        java.util.Map<String, int[]> via = new java.util.HashMap<String, int[]>();
        ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
        queue.add(start);
        String startKey = Arrays.toString(start);
        parent.put(startKey, null);
        List<int[]> result = null;
        while (!queue.isEmpty()) {
            int[] s = queue.poll();
            apply(s);
            if (isSolved()) {
                java.util.LinkedList<int[]> path = new java.util.LinkedList<int[]>();
                for (String k = Arrays.toString(s); parent.get(k) != null; k = parent.get(k)) path.addFirst(via.get(k));
                result = path;
                break;
            }
            String key = Arrays.toString(s);
            for (int i = 0; i < n; i++) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    if (freeSpace(i, dir) > 0) {
                        int[] t = s.clone();
                        t[i] += dir;
                        String tk = Arrays.toString(t);
                        if (!parent.containsKey(tk)) {
                            parent.put(tk, key);
                            via.put(tk, new int[] { i, dir });
                            queue.add(t);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) { pieces.get(i).col = saveC[i]; pieces.get(i).row = saveR[i]; }
        return result;
    }

    private void apply(int[] s) {
        for (int i = 0; i < s.length; i++) {
            Piece p = pieces.get(i);
            if (p.horizontal) p.col = s[i]; else p.row = s[i];
        }
    }
}
