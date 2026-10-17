package heist.engine;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary plist (bplist00) reader. XML is not supported — the DOM parser pulls in SPI lookups that TeaVM's
 * runtime can't satisfy. Values: Map (ordered), List, Long, Double, Boolean, String, byte[].
 */
public final class PList {
    private PList() {}

    public static Object parse(byte[] data) throws IOException {
        if (data.length > 8 && data[0] == 'b' && data[1] == 'p' && data[2] == 'l' && data[3] == 'i'
                && data[4] == 's' && data[5] == 't') {
            return new Binary(data).read();
        }
        throw new IOException("not a binary plist (XML plists are not supported)");
    }

    /** Writes a flat dictionary as bplist00 (NSUserDefaults on-disk format). */
    public static byte[] writeBinary(Map<String, Object> root) throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        // object table: 0 = the dictionary, then all keys, then all values
        List<Object> objs = new ArrayList<Object>();
        objs.add(root);
        List<String> keys = new ArrayList<String>(root.keySet());
        for (String k : keys) objs.add(k);
        for (String k : keys) objs.add(root.get(k));
        int n = objs.size();
        int refSize = n < 256 ? 1 : 2;
        List<Integer> offsets = new ArrayList<Integer>();
        body.write(new byte[] { 'b', 'p', 'l', 'i', 's', 't', '0', '0' });
        for (int i = 0; i < n; i++) {
            offsets.add(body.size());
            Object o = objs.get(i);
            if (i == 0) {
                writeHead(body, 0xD0, keys.size());
                for (int j = 0; j < keys.size(); j++) writeRef(body, 1 + j, refSize);
                for (int j = 0; j < keys.size(); j++) writeRef(body, 1 + keys.size() + j, refSize);
            } else if (o instanceof Boolean) {
                body.write(((Boolean) o) ? 0x09 : 0x08);
            } else if (o instanceof Integer || o instanceof Long || o instanceof Short) {
                writeInt(body, ((Number) o).longValue());
            } else if (o instanceof Double || o instanceof Float) {
                body.write(0x23);
                long bits = Double.doubleToLongBits(((Number) o).doubleValue());
                for (int b = 7; b >= 0; b--) body.write((int) (bits >> (b * 8)) & 0xFF);
            } else if (o instanceof byte[]) {
                byte[] d = (byte[]) o;
                writeHead(body, 0x40, d.length);
                body.write(d);
            } else {
                String str = String.valueOf(o);
                boolean ascii = true;
                for (int c = 0; c < str.length(); c++) if (str.charAt(c) > 127) { ascii = false; break; }
                if (ascii) {
                    writeHead(body, 0x50, str.length());
                    body.write(str.getBytes("US-ASCII"));
                } else {
                    writeHead(body, 0x60, str.length());
                    body.write(str.getBytes("UTF-16BE"));
                }
            }
        }
        int tableOffset = body.size();
        int offSize = tableOffset < 256 ? 1 : tableOffset < 65536 ? 2 : 4;
        for (int off : offsets) writeUInt(body, off, offSize);
        body.write(new byte[6]);
        body.write(offSize);
        body.write(refSize);
        writeUInt(body, 0, 4); writeUInt(body, n, 4);          // numObjects (8 bytes)
        writeUInt(body, 0, 4); writeUInt(body, 0, 4);          // top object = 0 (8 bytes)
        writeUInt(body, 0, 4); writeUInt(body, tableOffset, 4); // offset table position (8 bytes)
        return body.toByteArray();
    }

    private static void writeHead(java.io.ByteArrayOutputStream o, int marker, int len) {
        if (len < 15) { o.write(marker | len); return; }
        o.write(marker | 0x0F);
        writeInt(o, len);
    }

    private static void writeRef(java.io.ByteArrayOutputStream o, int ref, int size) { writeUInt(o, ref, size); }

    private static void writeUInt(java.io.ByteArrayOutputStream o, long v, int size) {
        for (int b = size - 1; b >= 0; b--) o.write((int) (v >> (b * 8)) & 0xFF);
    }

    private static void writeInt(java.io.ByteArrayOutputStream o, long v) {
        if (v < 0) { o.write(0x13); writeUInt(o, v, 8); }
        else if (v < 256) { o.write(0x10); writeUInt(o, v, 1); }
        else if (v < 65536) { o.write(0x11); writeUInt(o, v, 2); }
        else if (v < 4294967296L) { o.write(0x12); writeUInt(o, v, 4); }
        else { o.write(0x13); writeUInt(o, v, 8); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    public static int i(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt(((String) o).trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static double d(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static String s(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    public static boolean b(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        return def;
    }

    private static final class Binary {
        private final byte[] d;
        private int refSize, offSize, count;
        private long tableOffset;
        private Object[] cache;
        private boolean[] done;

        Binary(byte[] d) { this.d = d; }

        Object read() throws IOException {
            int t = d.length - 32;
            if (t < 8) throw new IOException("bplist too short");
            offSize = d[t + 6] & 0xff;
            refSize = d[t + 7] & 0xff;
            count = (int) be(t + 8, 8);
            int top = (int) be(t + 16, 8);
            tableOffset = be(t + 24, 8);
            cache = new Object[count];
            done = new boolean[count];
            return obj(top);
        }

        private long be(int pos, int n) {
            long v = 0;
            for (int k = 0; k < n; k++) v = (v << 8) | (d[pos + k] & 0xff);
            return v;
        }

        private int offsetOf(int ref) {
            return (int) be((int) (tableOffset + (long) ref * offSize), offSize);
        }

        private Object obj(int ref) throws IOException {
            if (ref < 0 || ref >= count) throw new IOException("bad object ref " + ref);
            if (done[ref]) return cache[ref];
            int p = offsetOf(ref);
            int marker = d[p] & 0xff;
            int type = marker >> 4, low = marker & 0xf;
            Object result;
            int[] hdr = new int[2]; // length, data start
            switch (type) {
                case 0x0:
                    result = low == 0x9 ? Boolean.TRUE : low == 0x8 ? Boolean.FALSE : null;
                    break;
                case 0x1:
                    result = Long.valueOf(be(p + 1, 1 << low));
                    if ((1 << low) == 8) result = Long.valueOf(be(p + 1, 8));
                    break;
                case 0x2: {
                    int n = 1 << low;
                    result = n == 4 ? (Object) Double.valueOf(Float.intBitsToFloat((int) be(p + 1, 4)))
                            : (Object) Double.valueOf(Double.longBitsToDouble(be(p + 1, 8)));
                    break;
                }
                case 0x3:
                    result = Double.valueOf(Double.longBitsToDouble(be(p + 1, 8)));
                    break;
                case 0x4: {
                    lenAt(p, low, hdr);
                    byte[] b = new byte[hdr[0]];
                    System.arraycopy(d, hdr[1], b, 0, hdr[0]);
                    result = b;
                    break;
                }
                case 0x5:
                    lenAt(p, low, hdr);
                    result = new String(d, hdr[1], hdr[0], Charset.forName("US-ASCII"));
                    break;
                case 0x6:
                    lenAt(p, low, hdr);
                    result = new String(d, hdr[1], hdr[0] * 2, Charset.forName("UTF-16BE"));
                    break;
                case 0x8:
                    result = Long.valueOf(be(p + 1, low + 1));
                    break;
                case 0xA: {
                    lenAt(p, low, hdr);
                    List<Object> l = new ArrayList<Object>(hdr[0]);
                    done[ref] = true; cache[ref] = l;
                    for (int k = 0; k < hdr[0]; k++) l.add(obj((int) be(hdr[1] + k * refSize, refSize)));
                    return l;
                }
                case 0xD: {
                    lenAt(p, low, hdr);
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    done[ref] = true; cache[ref] = m;
                    for (int k = 0; k < hdr[0]; k++) {
                        Object key = obj((int) be(hdr[1] + k * refSize, refSize));
                        Object val = obj((int) be(hdr[1] + (hdr[0] + k) * refSize, refSize));
                        m.put(String.valueOf(key), val);
                    }
                    return m;
                }
                default:
                    throw new IOException("unsupported bplist type 0x" + Integer.toHexString(type));
            }
            done[ref] = true;
            cache[ref] = result;
            return result;
        }

        /** Resolves the element count (possibly extended by a following int object). */
        private void lenAt(int p, int low, int[] out) {
            if (low != 0xf) {
                out[0] = low;
                out[1] = p + 1;
                return;
            }
            int m = d[p + 1] & 0xff;
            int n = 1 << (m & 0xf);
            out[0] = (int) be(p + 2, n);
            out[1] = p + 2 + n;
        }
    }

}
