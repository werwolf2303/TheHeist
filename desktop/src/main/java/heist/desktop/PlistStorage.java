package heist.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import heist.engine.PList;
import heist.engine.Save;
import heist.platform.Storage;

/** NSUserDefaults on disk: binary plist named after the bundle id, Documents/ subfolder for puzzle snapshots. */
final class PlistStorage implements Storage {
    private final File dataDir;
    private final File file;
    private final File documents;
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    PlistStorage(File dataDir) {
        this.dataDir = dataDir;
        this.file = new File(dataDir, Save.BUNDLE_ID + ".plist");
        this.documents = new File(dataDir, "Documents");
        if (file.isFile()) {
            try {
                values.putAll(PList.map(PList.parse(Files.readAllBytes(file.toPath()))));
            } catch (Exception e) {
                System.err.println("[save] cannot read " + file + ": " + e.getMessage());
            }
        }
    }

    public synchronized Map<String, Object> readAll() { return new LinkedHashMap<String, Object>(values); }

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

    private void flush() {
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) return;
        OutputStream out = null;
        try {
            byte[] data = PList.writeBinary(values);
            File tmp = new File(file.getPath() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write(data);
            out.close();
            out = null;
            if (!tmp.renameTo(file)) {
                // rename-over-existing can fail on Windows
                file.delete();
                tmp.renameTo(file);
            }
        } catch (IOException e) {
            System.err.println("[save] cannot write " + file + ": " + e.getMessage());
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }
}
