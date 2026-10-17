package heist.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import heist.engine.Save;
import heist.platform.Storage;

/**
 * The player's progress in the app's shared preferences, plus a Documents folder in the app's private storage.
 * Preferences have no double type, so a real number is kept as a marked string.
 */
final class AndroidStorage implements Storage {
    /** Marks a string preference that really holds a real number. */
    private static final String REAL = "\u0001d";

    private final SharedPreferences prefs;
    private final File documents;

    AndroidStorage(Context context) {
        prefs = context.getSharedPreferences(Save.BUNDLE_ID, Context.MODE_PRIVATE);
        documents = new File(context.getFilesDir(), "Documents");
        documents.mkdirs();
    }

    public Map<String, Object> readAll() {
        Map<String, Object> values = new HashMap<String, Object>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String && ((String) v).startsWith(REAL)) {
                try { v = Double.valueOf(((String) v).substring(REAL.length())); } catch (NumberFormatException ex) { continue; }
            }
            values.put(e.getKey(), v);
        }
        return values;
    }

    public void put(String key, Object v) {
        SharedPreferences.Editor ed = prefs.edit();
        if (v instanceof Boolean) ed.putBoolean(key, (Boolean) v);
        else if (v instanceof Integer) ed.putInt(key, (Integer) v);
        else if (v instanceof Long) ed.putLong(key, (Long) v);
        else if (v instanceof Double) ed.putString(key, REAL + v);
        else ed.putString(key, String.valueOf(v));
        ed.apply();
    }

    public void remove(String key) { prefs.edit().remove(key).apply(); }

    public void removeAll(Collection<String> keys) {
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : keys) ed.remove(k);
        ed.apply();
    }

    public List<String> listBlobNames(String prefix) {
        documents.mkdirs();
        List<String> names = new ArrayList<String>();
        File[] fs = documents.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith(prefix)) names.add(f.getName());
        return names;
    }

    public byte[] readBlob(String name) throws IOException {
        File f = new File(documents, name);
        return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
    }

    public void writeBlob(String name, byte[] data) throws IOException {
        documents.mkdirs();
        Files.write(new File(documents, name).toPath(), data);
    }

    public void deleteBlob(String name) {
        new File(documents, name).delete();
    }
}
