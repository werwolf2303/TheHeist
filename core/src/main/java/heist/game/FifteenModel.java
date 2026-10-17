package heist.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import heist.engine.PList;

/**
 * Sliding-tile puzzle. Tiles are numbered 1..n*n by their home cell (row-major); the tile whose
 * number equals {@link #emptyTile} is the hole. "Ignored" tiles are filler pieces that slide like
 * any other but do not have to be home for the level to count as solved.
 */
public final class FifteenModel {
    public static final class Pin {
        public final String color;
        public final int edge;
        public final int[] requires;
        public final String middleType;   // "middle" (default), "middleOneThird" or "middleTwoThird"

        Pin(String color, int edge, int[] requires, String middleType) {
            this.color = color;
            this.edge = edge;
            this.requires = requires;
            this.middleType = middleType;
        }
    }

    public final int size;
    public final int emptyTile;
    public final Set<Integer> ignored = new HashSet<Integer>();
    public final List<Pin> pins = new ArrayList<Pin>();
    private final List<String> scramble = new ArrayList<String>();
    private final int[] board;      // board[cell] = tile number
    private int emptyCell;
    private int moves;

    public FifteenModel(Map<String, Object> level) {
        size = PList.i(level.get("puzzleSize"), 4);
        emptyTile = PList.i(level.get("emptyTileNumber"), size * size);
        for (Object o : PList.list(level.get("piecesToIgnore"))) ignored.add(PList.i(o, -1));
        for (Object o : PList.list(level.get("scramble"))) scramble.add(String.valueOf(o));
        for (Object o : PList.list(level.get("colorPinLocations"))) {
            Map<String, Object> m = PList.map(o);
            List<Object> req = PList.list(m.get("piecesUnlockedRequiredForLit"));
            int[] r = new int[req.size()];
            for (int k = 0; k < r.length; k++) r[k] = PList.i(req.get(k), 0);
            // one level's data has the key misspelled as "11"
            Object mt = m.containsKey("middleType") ? m.get("middleType") : m.get("11");
            pins.add(new Pin(PList.s(m.get("color"), "white"), PList.i(m.get("edgeNumber"), 1), r,
                    PList.s(mt, "middle")));
        }
        board = new int[size * size];
        reset();
    }

    public void reset() {
        for (int i = 0; i < board.length; i++) board[i] = i + 1;
        emptyCell = emptyTile - 1;
        moves = 0;
        for (String s : scramble) {
            int d = dirIndex(s);
            if (d < 0) continue;
            // like the original, a move that would leave the board finds no piece and is skipped
            int target = neighbour(emptyCell, d);
            if (target >= 0) swapWithEmpty(target);
        }
        moves = 0;
    }

    /** 0 up, 1 down, 2 left, 3 right (so d ^ 1 is the opposite direction). */
    private static int dirIndex(String s) {
        if (s.equals("up")) return 0;
        if (s.equals("down")) return 1;
        if (s.equals("left")) return 2;
        if (s.equals("right")) return 3;
        return -1;
    }

    private int neighbour(int cell, int dir) {
        int r = cell / size, c = cell % size;
        if (dir == 0) r--; else if (dir == 1) r++; else if (dir == 2) c--; else c++;
        return (r < 0 || c < 0 || r >= size || c >= size) ? -1 : r * size + c;
    }

    private void swapWithEmpty(int cell) {
        board[emptyCell] = board[cell];
        board[cell] = emptyTile;
        emptyCell = cell;
    }

    public int tileAt(int row, int col) { return board[row * size + col]; }
    public int moves() { return moves; }
    public int emptyRow() { return emptyCell / size; }
    public int emptyCol() { return emptyCell % size; }

    /** A tile can move if it is in the same row/column as the hole; everything in between shifts. */
    public boolean canMove(int row, int col) {
        return (row == emptyRow()) != (col == emptyCol());
    }

    /** Slides the tile (and any tiles between it and the hole). Returns true if something moved. */
    public boolean move(int row, int col) {
        if (!canMove(row, col)) return false;
        int er = emptyRow(), ec = emptyCol();
        int dr = Integer.signum(row - er), dc = Integer.signum(col - ec);
        int r = er, c = ec;
        while (r != row || c != col) {
            r += dr;
            c += dc;
            swapWithEmpty(r * size + c);
        }
        moves++;
        return true;
    }

    public boolean isHome(int tile) {
        for (int i = 0; i < board.length; i++) if (board[i] == tile) return i == tile - 1;
        return false;
    }

    public boolean isSolved() {
        for (int tile = 1; tile <= size * size; tile++) {
            if (tile == emptyTile || ignored.contains(tile)) continue;
            if (!isHome(tile)) return false;
        }
        return true;
    }

    public boolean pinLit(Pin p) {
        for (int t : p.requires) if (!isHome(t)) return false;
        return true;
    }

    /** Which scramble steps actually apply (the original skips ones that leave the board). */
    public static boolean[] scrambleApplied(Map<String, Object> level) {
        int size = PList.i(level.get("puzzleSize"), 4);
        int cell = PList.i(level.get("emptyTileNumber"), size * size) - 1;
        List<Object> steps = PList.list(level.get("scramble"));
        boolean[] applied = new boolean[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            int d = dirIndex(String.valueOf(steps.get(i)));
            int r = cell / size, c = cell % size;
            if (d == 0) r--; else if (d == 1) r++; else if (d == 2) c--; else c++;
            if (d < 0 || r < 0 || c < 0 || r >= size || c >= size) continue;
            applied[i] = true;
            cell = r * size + c;
        }
        return applied;
    }

    /** True if the scramble from the data file replays as-is (used by the verifier). */
    public static boolean scrambleIsLegal(Map<String, Object> level) {
        int size = PList.i(level.get("puzzleSize"), 4);
        int cell = PList.i(level.get("emptyTileNumber"), size * size) - 1;
        for (Object o : PList.list(level.get("scramble"))) {
            int d = dirIndex(String.valueOf(o));
            int r = cell / size, c = cell % size;
            if (d == 0) r--; else if (d == 1) r++; else if (d == 2) c--; else c++;
            if (r < 0 || c < 0 || r >= size || c >= size) return false;
            cell = r * size + c;
        }
        return true;
    }
}
