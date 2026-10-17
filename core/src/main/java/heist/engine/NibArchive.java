package heist.engine;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for compiled Interface Builder archives (NIBArchive, Xcode 4+ / iOS 5-6).
 * Layout (LE): "NIBArchive", u32 1, u32 version, then count/offset pairs for objects/keys/values/class names.
 * Entries use a varint where the HIGH bit marks the final byte.
 */
public final class NibArchive {
    public static final int T_INT8 = 0, T_INT16 = 1, T_INT32 = 2, T_INT64 = 3, T_TRUE = 4, T_FALSE = 5,
            T_FLOAT = 6, T_DOUBLE = 7, T_DATA = 8, T_NIL = 9, T_OBJECT = 10;

    /** An object reference value, pointing into {@link #objects}. */
    public static final class Ref {
        public final int index;
        Ref(int index) { this.index = index; }
        public String toString() { return "@" + index; }
    }

    public static final class Obj {
        public int index;
        public String className;
        /** Keys may repeat for some classes. */
        public final List<String> keys = new ArrayList<String>();
        public final List<Object> values = new ArrayList<Object>();

        public Object get(String key) {
            for (int i = 0; i < keys.size(); i++) if (keys.get(i).equals(key)) return values.get(i);
            return null;
        }

        public boolean has(String key) { return get(key) != null; }

        public double num(String key, double def) {
            Object o = get(key);
            return o instanceof Number ? ((Number) o).doubleValue() : def;
        }

        public String str(String key, String def) {
            Object o = get(key);
            if (o instanceof byte[]) return new String((byte[]) o, Charset.forName("UTF-8")).replace("\0", "");
            return o instanceof String ? (String) o : def;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), values.get(i));
            return m;
        }
    }

    public final int version;
    public final List<Obj> objects = new ArrayList<Obj>();

    public static NibArchive load(File f) throws IOException {
        return new NibArchive(Files.readAllBytes(f.toPath()));
    }

    public NibArchive(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 50 || !new String(data, 0, 10, Charset.forName("US-ASCII")).equals("NIBArchive")) {
            throw new IOException("not a NIBArchive");
        }
        b.position(10);
        b.getInt(); // always 1
        version = b.getInt();
        int objCount = b.getInt(), objOff = b.getInt();
        int keyCount = b.getInt(), keyOff = b.getInt();
        int valCount = b.getInt(), valOff = b.getInt();
        int clsCount = b.getInt(), clsOff = b.getInt();

        // class names
        String[] classes = new String[clsCount];
        int[] p = { clsOff };
        for (int i = 0; i < clsCount; i++) {
            int len = varint(data, p);
            int extras = varint(data, p);
            p[0] += extras * 4;
            classes[i] = new String(data, p[0], Math.max(0, len - 1), Charset.forName("UTF-8"));
            p[0] += len;
        }
        // keys
        String[] keys = new String[keyCount];
        p[0] = keyOff;
        for (int i = 0; i < keyCount; i++) {
            int len = varint(data, p);
            keys[i] = new String(data, p[0], len, Charset.forName("UTF-8"));
            p[0] += len;
        }
        // values
        final int[] vKey = new int[valCount];
        final Object[] vVal = new Object[valCount];
        p[0] = valOff;
        for (int i = 0; i < valCount; i++) {
            vKey[i] = varint(data, p);
            int type = data[p[0]++] & 0xff;
            ByteBuffer vb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int q = p[0];
            switch (type) {
                case T_INT8: vVal[i] = Long.valueOf(data[q]); p[0] += 1; break;
                case T_INT16: vVal[i] = Long.valueOf(vb.getShort(q)); p[0] += 2; break;
                case T_INT32: vVal[i] = Long.valueOf(vb.getInt(q)); p[0] += 4; break;
                case T_INT64: vVal[i] = Long.valueOf(vb.getLong(q)); p[0] += 8; break;
                case T_TRUE: vVal[i] = Boolean.TRUE; break;
                case T_FALSE: vVal[i] = Boolean.FALSE; break;
                case T_FLOAT: vVal[i] = Double.valueOf(vb.getFloat(q)); p[0] += 4; break;
                case T_DOUBLE: vVal[i] = Double.valueOf(vb.getDouble(q)); p[0] += 8; break;
                case T_DATA: {
                    int len = varint(data, p);
                    byte[] d = new byte[len];
                    System.arraycopy(data, p[0], d, 0, len);
                    vVal[i] = d;
                    p[0] += len;
                    break;
                }
                case T_NIL: vVal[i] = null; break;
                case T_OBJECT: vVal[i] = new Ref(vb.getInt(q)); p[0] += 4; break;
                default: throw new IOException("unknown NIB value type " + type + " at " + (p[0] - 1));
            }
        }
        // objects
        p[0] = objOff;
        for (int i = 0; i < objCount; i++) {
            Obj o = new Obj();
            o.index = i;
            o.className = classes[varint(data, p)];
            int first = varint(data, p), count = varint(data, p);
            for (int k = 0; k < count; k++) {
                o.keys.add(keys[vKey[first + k]]);
                o.values.add(vVal[first + k]);
            }
            objects.add(o);
        }
    }

    public Obj resolve(Object ref) {
        return ref instanceof Ref ? objects.get(((Ref) ref).index) : null;
    }

    private static int varint(byte[] d, int[] p) {
        int v = 0, shift = 0;
        while (true) {
            int b = d[p[0]++] & 0xff;
            v |= (b & 0x7f) << shift;
            if ((b & 0x80) != 0) return v;
            shift += 7;
        }
    }
}
