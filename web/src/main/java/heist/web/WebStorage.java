package heist.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import heist.engine.PList;
import heist.platform.Storage;

/**
 * Player progress (binary plist) and puzzle thumbnails (blobs) stored in OPFS. flush() is fire-and-forget
 * via a background Thread because Save.put() is called from rAF (not Thread context), where await() throws.
 */
final class WebStorage implements Storage {
    private static final String VALUES_FILE = "progress.plist";

    private final Map<String, Object> values = new LinkedHashMap<>();
    // populated eagerly at construction (in Thread context) so rAF-loop reads never need await()
    private final Map<String, byte[]> blobs = new LinkedHashMap<>();

    WebStorage() {
        try {
            byte[] data = Opfs.read(VALUES_FILE);
            if (data != null) values.putAll(PList.map(PList.parse(data)));
        } catch (Exception e) {
            System.err.println("[save] cannot read " + VALUES_FILE + ": " + e);
        }
        for (String path : Opfs.listNames("LevelCompleted-")) {
            try {
                byte[] data = Opfs.read(path);
                if (data != null) blobs.put(path, data);
            } catch (IOException e) {
                System.err.println("[save] cannot preload blob " + path + ": " + e);
            }
        }
    }

    public synchronized Map<String, Object> readAll() { return new LinkedHashMap<>(values); }

    public synchronized void put(String key, Object value) {
        values.put(key, value);
        flush();
    }

    public synchronized void remove(String key) {
        values.remove(key);
        flush();
    }

    public synchronized void removeAll(Collection<String> keys) {
        for (String k : keys) values.remove(k);
        flush();
    }

    public List<String> listBlobNames(String prefix) {
        List<String> result = new ArrayList<>();
        for (String name : blobs.keySet()) {
            if (name.startsWith(prefix)) result.add(name);
        }
        return result;
    }

    public byte[] readBlob(String name) throws IOException { return blobs.get(name); }

    public synchronized void writeBlob(String name, byte[] data) throws IOException {
        blobs.put(name, data);
        final byte[] snapshot = data;
        new Thread(() -> {
            try { Opfs.write(name, snapshot); }
            catch (IOException e) { System.err.println("[save] cannot write blob " + name + ": " + e); }
        }).start();
    }

    public void deleteBlob(String name) {
        blobs.remove(name);
        new Thread(() -> Opfs.delete(name)).start();
    }

    private synchronized void flush() {
        byte[] data;
        try {
            data = PList.writeBinary(new LinkedHashMap<>(values));
        } catch (IOException e) {
            System.err.println("[save] cannot serialize progress: " + e);
            return;
        }
        new Thread(() -> {
            try {
                Opfs.write(VALUES_FILE, data);
            } catch (IOException e) {
                System.err.println("[save] cannot write " + VALUES_FILE + ": " + e);
            }
        }).start();
    }
}
