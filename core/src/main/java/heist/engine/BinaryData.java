package heist.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Extracts data tables compiled into the original Mach-O binary (not shipped as files) and checks for
 * FairPlay encryption. Pure byte[] handling, shared across all port importers.
 */
public final class BinaryData {
    private BinaryData() {}

    public static final int SOKOBAN_LEVELS = 15, SOKOBAN_MAP = 20;

    /** Returns the armv7 (or first) thin slice of a Mach-O / fat Mach-O file. */
    public static byte[] thinSlice(byte[] d) throws IOException {
        if (d.length < 8) throw new IOException("too small");
        ByteBuffer b = ByteBuffer.wrap(d);
        int magic = b.getInt(0);
        if (magic != 0xCAFEBABE) return d;
        int n = b.getInt(4);
        int off = -1, size = 0;
        for (int i = 0; i < n; i++) {
            int base = 8 + i * 20;
            int cpu = b.getInt(base), sub = b.getInt(base + 4);
            int o = b.getInt(base + 8), s = b.getInt(base + 12);
            if (off < 0 || (cpu == 12 && sub == 9)) { off = o; size = s; }
        }
        return Arrays.copyOfRange(d, off, off + size);
    }

    /** True if LC_ENCRYPTION_INFO/LC_ENCRYPTION_INFO_64 has non-zero cryptid (FairPlay-encrypted). */
    public static boolean isEncrypted(byte[] macho) throws IOException {
        byte[] t = thinSlice(macho);
        if (t.length < 28) return false;
        ByteBuffer b = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt(0);
        boolean is64 = magic == 0xFEEDFACF;
        if (magic != 0xFEEDFACE && !is64) return false;   // not a thin Mach-O we recognise; nothing to check
        int ncmds = b.getInt(16);
        int pos = is64 ? 32 : 28;
        for (int i = 0; i < ncmds && pos + 8 <= t.length; i++) {
            int cmd = b.getInt(pos), cmdsize = b.getInt(pos + 4);
            if (cmd == 0x21 || cmd == 0x2C) {   // LC_ENCRYPTION_INFO / LC_ENCRYPTION_INFO_64
                int cryptid = b.getInt(pos + 16);
                if (cryptid != 0) return true;
            }
            if (cmdsize <= 0) break;
            pos += cmdsize;
        }
        return false;
    }

    /** Returns the 15×20×20-byte Sokoban level table from the Mach-O, or null if not found. */
    public static byte[] sokobanLevels(byte[] macho) throws IOException {
        byte[] t = thinSlice(macho);
        int need = SOKOBAN_LEVELS * SOKOBAN_MAP * SOKOBAN_MAP;
        int i = 0;
        while (i < t.length) {
            if ((t[i] & 0xff) >= 8) { i++; continue; }
            int j = i;
            while (j < t.length && (t[j] & 0xff) < 8) j++;
            if (j - i >= need) {
                // right-shifted content still validates; scan from end to find true start
                for (int s = j - need; s >= i; s--) {
                    if (validSokoban(t, s)) return Arrays.copyOfRange(t, s, s + need);
                }
            }
            i = j;
        }
        return null;
    }

    private static boolean validSokoban(byte[] t, int start) {
        for (int lv = 0; lv < SOKOBAN_LEVELS; lv++) {
            int base = start + lv * 400;
            int robots = 0, boxes = 0, goals = 0, both = 0;
            for (int k = 0; k < 400; k++) {
                int v = t[base + k] & 0xff;
                if (v == 0) continue;
                int r = k / 20, c = k % 20;
                if (r >= 13 || c >= 13) return false;
                if (v == 2) robots++;
                else if (v == 5) boxes++;
                else if (v == 6) goals++;
                else if (v == 7) both++;
            }
            if (robots != 1 || boxes + both != goals + both || boxes + both == 0) return false;
        }
        return true;
    }
}
