package heist.game;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import heist.engine.PList;

/**
 * Rune sudoku: each rune must appear exactly once in every row, column and colour group.
 * The level file stores the full solution as a row-major "gN-rM" list; cells listed in
 * piecesToIgnore are pre-filled and locked.
 */
public final class SudokuModel {
    public final int n;
    public final int[] group;     // 0-based group per cell
    public final int[] solution;  // 0-based rune per cell
    public final boolean[] locked;
    public final String[] groupColors;
    public final String runeFont;
    public final float runeFontSize;
    public final String runeSymbols;
    public final float tileSize;
    private final int[] value;    // -1 = empty
    private int moves;

    public SudokuModel(Map<String, Object> level) {
        n = PList.i(level.get("puzzleGridSize"), 5);
        group = new int[n * n];
        solution = new int[n * n];
        locked = new boolean[n * n];
        value = new int[n * n];
        String[] toks = PList.s(level.get("runesHash"), "").split(",");
        for (int i = 0; i < n * n && i < toks.length; i++) {
            String[] gr = toks[i].trim().split("-");
            group[i] = Integer.parseInt(gr[0].substring(1)) - 1;
            solution[i] = Integer.parseInt(gr[1].substring(1)) - 1;
        }
        for (Object o : PList.list(level.get("piecesToIgnore"))) {
            int idx = PList.i(o, 0) - 1;
            if (idx >= 0 && idx < locked.length) locked[idx] = true;
        }
        List<Object> colors = PList.list(level.get("groupColors"));
        groupColors = new String[colors.size()];
        for (int i = 0; i < groupColors.length; i++) groupColors[i] = String.valueOf(colors.get(i));
        StringBuilder sb = new StringBuilder();
        for (Object o : PList.list(level.get("runeSymbols"))) sb.append(o);
        runeSymbols = sb.toString();
        runeFont = PList.s(level.get("runeBase"), "Bacata");
        runeFontSize = (float) PList.d(level.get("runeBaseSize"), 40);
        tileSize = (float) PList.d(level.get("puzzleGridTileSize"), 50);
        reset();
    }

    public void reset() {
        for (int i = 0; i < value.length; i++) value[i] = locked[i] ? solution[i] : -1;
        moves = 0;
    }

    public int get(int row, int col) { return value[row * n + col]; }
    public int moves() { return moves; }

    /** Cycles an unlocked cell: empty -> rune 0 -> ... -> rune n-1 -> empty. */
    public void cycle(int row, int col) {
        int i = row * n + col;
        if (locked[i]) return;
        value[i] = value[i] + 1 >= n ? -1 : value[i] + 1;
        moves++;
    }

    public boolean hasConflict(int row, int col) {
        int i = row * n + col;
        int v = value[i];
        if (v < 0) return false;
        for (int k = 0; k < n * n; k++) {
            if (k == i || value[k] != v) continue;
            if (k / n == row || k % n == col || group[k] == group[i]) return true;
        }
        return false;
    }

    public boolean isSolved() {
        for (int i = 0; i < value.length; i++) if (value[i] < 0 || hasConflict(i / n, i % n)) return false;
        return true;
    }

    /** Checks the stored solution really is a valid assignment (used by the verifier). */
    public boolean solutionIsValid() {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < n * n; i++) {
            if (solution[i] < 0 || solution[i] >= n || group[i] < 0 || group[i] >= n) return false;
            if (!seen.add("r" + (i / n) + "-" + solution[i])) return false;
            if (!seen.add("c" + (i % n) + "-" + solution[i])) return false;
            if (!seen.add("g" + group[i] + "-" + solution[i])) return false;
        }
        return true;
    }
}
