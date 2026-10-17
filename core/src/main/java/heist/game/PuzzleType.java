package heist.game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import heist.engine.Assets;
import heist.engine.PList;

/** One of the four puzzle families listed in Puzzles.plist. */
public final class PuzzleType {
    public final int gameId;
    public final String name;          // "Gridlock", "Sudoku", "Sokoban", "Fifteen"
    public final String controller;
    public final String progressMeter;
    public final int levels;
    /** Difficulty tier (1..4) of each displayed level, from Puzzles.plist ("level3" -> 3). */
    private final int[] difficulties;

    /**
     * The game shows levels in difficulty order but plays a shuffled data level; these are the
     * original's per-puzzle "level order" tables (index = displayed level - 1).
     */
    private static final int[] ORDER_GRIDLOCK = { 1, 2, 3, 4, 5, 11, 9, 10, 14, 7, 12, 6, 8, 13, 15 };
    private static final int[] ORDER_FIFTEEN = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13, 11, 14, 12, 15 };
    private static final int[] ORDER_SUDOKU = { 1, 3, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 14 };
    private static final int[] ORDER_SOKOBAN = { 4, 1, 2, 3, 5, 6, 7, 8, 9, 10, 12, 13, 11, 14, 15 };

    private PuzzleType(Map<String, Object> m) {
        gameId = PList.i(m.get("gameID"), 0);
        name = PList.s(m.get("name"), "?");
        controller = PList.s(m.get("controller"), "");
        progressMeter = PList.s(m.get("progress_meter"), "blue");
        List<Object> lv = PList.list(m.get("levels"));
        levels = lv.size();
        difficulties = new int[levels];
        for (int i = 0; i < levels; i++) {
            String d = PList.s(PList.map(lv.get(i)).get("difficulty"), "level1");
            difficulties[i] = PList.i(d.replace("level", ""), 1);
        }
    }

    /** Difficulty tier (1..4) of a displayed level. */
    public int difficulty(int displayedLevel) { return difficulties[displayedLevel - 1]; }

    /** The data level (index into the *Settings.plist files / level table) behind a displayed level. */
    public int dataLevel(int displayedLevel) {
        int[] t = name.equals("Gridlock") ? ORDER_GRIDLOCK : name.equals("Fifteen") ? ORDER_FIFTEEN
                : name.equals("Sudoku") ? ORDER_SUDOKU : ORDER_SOKOBAN;
        return t[displayedLevel - 1];
    }

    private static List<PuzzleType> all;

    public static List<PuzzleType> all() {
        if (all == null) {
            all = new ArrayList<PuzzleType>();
            try {
                Map<String, Object> root = PList.map(PList.parse(heist.engine.Assets.bytes("Puzzles.plist")));
                for (Object o : PList.list(root.get("Puzzles"))) all.add(new PuzzleType(PList.map(o)));
            } catch (IOException e) {
                throw new RuntimeException("cannot read Puzzles.plist: " + e.getMessage(), e);
            }
        }
        return all;
    }

    public static PuzzleType byName(String name) {
        for (PuzzleType t : all()) if (t.name.equalsIgnoreCase(name)) return t;
        return null;
    }

    public String toString() { return name; }
}
