package heist.game;

import java.util.HashMap;
import java.util.Map;

/** The two rune colour palettes (loose stones vs. pre-placed runes), keyed by group colour name. */
final class SudokuColors {
    private SudokuColors() {}

    private static final Map<String, Integer> STONE = new HashMap<String, Integer>();
    private static final Map<String, Integer> FIXED = new HashMap<String, Integer>();

    private static float f(int bits) { return Float.intBitsToFloat(bits); }

    private static int ch(int bits) { return Math.round(Math.max(0f, Math.min(1f, f(bits))) * 255f); }

    private static void put(Map<String, Integer> m, String name, int r, int g, int b, int a) {
        m.put(name, (ch(a) << 24) | (ch(r) << 16) | (ch(g) << 8) | ch(b));
    }

    static {
        // loose stones
        put(STONE, "red", 0x3f50d0d1, 0x3e4ccccd, 0x3e20a0a1, 0x3f333333);
        put(STONE, "amber", 0x3f4f1aa0, 0x3f028f5c, 0x3e3851ec, 0x3f19999a);
        put(STONE, "green", 0x3ef8f8f9, 0x3f59d9da, 0x3e50d0d1, 0x3f19999a);
        put(STONE, "blue", 0x3de0e0e1, 0x3f21a1a2, 0x3f40c0c1, 0x3f19999a);
        put(STONE, "yellow", 0x3f7efeff, 0x3f76f6f7, 0x3eacacad, 0x3f19999a);
        put(STONE, "pink", 0x3f7cac08, 0x3e604189, 0x3f49fbe7, 0x3f19999a);
        put(STONE, "purple", 0x3f068687, 0x3e109091, 0x3f4bcbcc, 0x3f266666);
        put(STONE, "white", 0x3f800000, 0x3f800000, 0x3f800000, 0x3f19999a);
        put(STONE, "turquoise", 0x3e048485, 0x3f63e3e4, 0x3f31b1b2, 0x3f19999a);
        put(STONE, "dark", 0, 0, 0, 0x3f19999a);
        // runes already carved into the board
        put(FIXED, "red", 0x3f4ececf, 0, 0, 0x3f000000);
        put(FIXED, "amber", 0x3f389375, 0x3eb95810, 0, 0x3f19999a);
        put(FIXED, "green", 0x3e848485, 0x3f800000, 0, 0x3f000000);
        put(FIXED, "blue", 0, 0x3f4acacb, 0x3f7dfdfe, 0x3f000000);
        put(FIXED, "yellow", 0x3f74f4f5, 0x3f800000, 0x3e78f8f9, 0x3f000000);
        put(FIXED, "pink", 0x3f800000, 0, 0x3ee4e4e5, 0x3f000000);
        put(FIXED, "purple", 0x3ea8a8a9, 0, 0x3f0e8e8f, 0x3f000000);
        put(FIXED, "white", 0x3f800000, 0x3f800000, 0x3f800000, 0x3f000000);
        put(FIXED, "turquoise", 0, 0x3f7dfdfe, 0x3f50d0d1, 0x3f000000);
        put(FIXED, "dark", 0x3d109091, 0x3c008081, 0x3b808081, 0x3f666666);
    }

    static int color(String name, boolean looseStone) {
        Integer c = (looseStone ? STONE : FIXED).get(name);
        return c != null ? c : 0xFFFFFFFF;
    }
}
