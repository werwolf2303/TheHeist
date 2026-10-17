package heist.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import heist.platform.Host;
import heist.platform.Storage;

/** NSUserDefaults equivalent: typed values under string keys, backed by {@link Storage}. */
public final class Save {
    public static final String BUNDLE_ID = "com.taptaptap.TheHeist";
    /** 1970-01-01 to 2001-01-01 in seconds — NSDate's reference date offset. */
    public static final double REFERENCE_DATE_OFFSET = 978307200.0;

    private static final Map<String, Object> values = new HashMap<String, Object>();

    private Save() {}

    /** Loads the saved state from the platform's storage. */
    public static synchronized void load() {
        values.clear();
        values.putAll(Host.get().storage().readAll());
    }

    /** The value as text, or null if the key is not set. */
    public static synchronized String get(String key) {
        Object v = values.get(key);
        if (v == null) return null;
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    public static synchronized double getDouble(String key, double def) {
        Object v = values.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return parseDouble((String) v, def);
        return def;
    }

    public static synchronized boolean getBool(String key, boolean def) {
        Object v = values.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return Boolean.parseBoolean((String) v) || "1".equals(v) || "YES".equalsIgnoreCase((String) v);
        return def;
    }

    public static synchronized int getInt(String key, int def) {
        Object v = values.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) return parseInt((String) v, def);
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        return def;
    }

    /** Stores a Boolean, integer, real or string value. */
    public static synchronized void put(String key, Object value) {
        Object v = value;
        if (v instanceof Short || v instanceof Byte) v = Integer.valueOf(((Number) v).intValue());
        else if (v instanceof Float) v = Double.valueOf(((Number) v).doubleValue());
        else if (!(v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Double)) v = String.valueOf(v);
        values.put(key, v);
        Host.get().storage().put(key, v);
    }

    /** Snapshot for dev tools (replay). */
    public static synchronized Map<String, Object> snapshot() {
        return new HashMap<String, Object>(values);
    }

    /** Restores from a {@link #snapshot}. */
    public static synchronized void restore(Map<String, Object> snap) {
        Host.get().storage().removeAll(new ArrayList<String>(values.keySet()));
        values.clear();
        values.putAll(snap);
        for (Map.Entry<String, Object> e : snap.entrySet()) Host.get().storage().put(e.getKey(), e.getValue());
    }

    public static synchronized void remove(String key) {
        values.remove(key);
        Host.get().storage().remove(key);
    }

    /** Removes all entries whose key contains any of the given substrings. */
    public static synchronized void removeContaining(String... fragments) {
        java.util.List<String> gone = new ArrayList<String>();
        for (String k : new ArrayList<String>(values.keySet())) {
            for (String f : fragments) {
                if (k.contains(f)) {
                    values.remove(k);
                    gone.add(k);
                    break;
                }
            }
        }
        if (!gone.isEmpty()) Host.get().storage().removeAll(gone);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
