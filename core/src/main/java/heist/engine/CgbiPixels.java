package heist.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the pixels of the "CgBI" PNG variant that Xcode produces for iOS apps: raw deflate data (no zlib
 * header), BGR(A) channel order and premultiplied alpha. Standard decoders cannot read these.
 *
 * This holds only the byte[]-in, int[]-ARGB-out core (inflate + unfilter + channel/alpha fixup) so it can be
 * shared by every port: the desktop/Android build-time importer (in :tools, which turns the result into a
 * java.awt.image.BufferedImage - not available here) and the web port's browser-side importer (which turns
 * it into whatever its own Image implementation is, running entirely inside TeaVM's WasmGC compile target).
 */
public final class CgbiPixels {
    public final int width, height;
    private final int[] argb;

    private CgbiPixels(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    /** Row-major ARGB pixels, not premultiplied. */
    public int[] argb() { return argb; }

    /** True if the bytes are a PNG carrying the Apple CgBI chunk. */
    public static boolean isCgbi(byte[] d) {
        return d.length > 16 && d[0] == (byte) 0x89 && d[1] == 'P' && d[12] == 'C' && d[13] == 'g'
                && d[14] == 'B' && d[15] == 'I';
    }

    public static CgbiPixels decode(byte[] d) throws IOException {
        int pos = 8, w = 0, h = 0, depth = 0, ctype = 0, interlace = 0;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (pos + 8 <= d.length) {
            int len = i32(d, pos);
            String type = new String(d, pos + 4, 4, "US-ASCII");
            int data = pos + 8;
            if (type.equals("IHDR")) {
                w = i32(d, data);
                h = i32(d, data + 4);
                depth = d[data + 8] & 0xff;
                ctype = d[data + 9] & 0xff;
                interlace = d[data + 12] & 0xff;
            } else if (type.equals("IDAT")) {
                idat.write(d, data, len);
            } else if (type.equals("IEND")) {
                break;
            }
            pos = data + len + 4;
        }
        if (depth != 8 || interlace != 0 || (ctype != 6 && ctype != 2)) {
            throw new IOException("unsupported CgBI layout (depth=" + depth + " type=" + ctype + " interlace=" + interlace + ")");
        }
        int bpp = ctype == 6 ? 4 : 3;
        int stride = w * bpp;
        byte[] raw = inflate(idat.toByteArray(), (stride + 1) * h);
        byte[] px = new byte[stride * h];
        unfilter(raw, px, w, h, bpp);

        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = y * stride + x * bpp;
                int b = px[o] & 0xff, g = px[o + 1] & 0xff, r = px[o + 2] & 0xff;
                int a = bpp == 4 ? px[o + 3] & 0xff : 255;
                if (a > 0 && a < 255) {
                    r = Math.min(255, (r * 255 + a / 2) / a);
                    g = Math.min(255, (g * 255 + a / 2) / a);
                    b = Math.min(255, (b * 255 + a / 2) / a);
                }
                argb[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return new CgbiPixels(w, h, argb);
    }

    private static byte[] inflate(byte[] in, int expected) throws IOException {
        Inflater inf = new Inflater(true);
        inf.setInput(in);
        byte[] out = new byte[expected];
        int n = 0;
        try {
            while (n < expected && !inf.finished()) {
                int r = inf.inflate(out, n, expected - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
        } catch (DataFormatException e) {
            throw new IOException("bad deflate stream: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
        if (n < expected) throw new IOException("truncated image data (" + n + "/" + expected + ")");
        return out;
    }

    private static void unfilter(byte[] raw, byte[] out, int w, int h, int bpp) throws IOException {
        int stride = w * bpp;
        for (int y = 0; y < h; y++) {
            int f = raw[y * (stride + 1)] & 0xff;
            int src = y * (stride + 1) + 1, dst = y * stride;
            for (int i = 0; i < stride; i++) {
                int x = raw[src + i] & 0xff;
                int a = i >= bpp ? out[dst + i - bpp] & 0xff : 0;
                int b = y > 0 ? out[dst - stride + i] & 0xff : 0;
                int c = (y > 0 && i >= bpp) ? out[dst - stride + i - bpp] & 0xff : 0;
                int v;
                switch (f) {
                    case 0: v = x; break;
                    case 1: v = x + a; break;
                    case 2: v = x + b; break;
                    case 3: v = x + ((a + b) >> 1); break;
                    case 4: v = x + paeth(a, b, c); break;
                    default: throw new IOException("bad PNG filter " + f);
                }
                out[dst + i] = (byte) v;
            }
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static int i32(byte[] d, int p) {
        return ((d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16) | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
    }
}
