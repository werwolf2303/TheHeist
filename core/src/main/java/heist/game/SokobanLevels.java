package heist.game;

import java.io.IOException;

import heist.engine.Assets;

/**
 * The 15 Sokoban maps (20x20 byte grids, only the top-left 13x13 is used) read from
 * sokoban_levels.bin, which the importer extracts from the original executable.
 *
 * Tile codes: 0 empty, 1 wall, 2 robot start, 5 box, 6 goal, 7 box already on a goal.
 */
public final class SokobanLevels {
    public static final int STRIDE = 20, GRID = 13, COUNT = 15;
    /** Layout dimensions (columns, rows) the original uses to scale and centre each level. */
    public static final int[][] DIMS = {
        {6, 9}, {6, 7}, {8, 9}, {7, 7}, {8, 12}, {8, 11}, {8, 9}, {8, 10},
        {7, 7}, {6, 7}, {11, 11}, {6, 10}, {8, 12}, {10, 13}, {9, 12}
    };

    private static byte[] data;

    private SokobanLevels() {}

    private static void load() {
        if (data != null) return;
        try {
            data = Assets.bytes("sokoban_levels.bin");
        } catch (IOException e) {
            throw new RuntimeException("sokoban_levels.bin missing - this build has no game files", e);
        }
    }

    /** A fresh copy of the level's 20x20 map. */
    public static byte[] map(int level) {
        load();
        byte[] m = new byte[STRIDE * STRIDE];
        System.arraycopy(data, (level - 1) * STRIDE * STRIDE, m, 0, m.length);
        return m;
    }
}
