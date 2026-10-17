package heist.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import heist.engine.Image;
import heist.platform.AssetSource;

/**
 * Game files in memory from the player's uploaded .ipa. Images pre-decoded during the import Thread
 * (where await() is safe) so the rAF callback can return them synchronously.
 */
final class WebAssetSource implements AssetSource {
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, Image> decoded = new HashMap<>();

    void put(String name, byte[] data) { files.put(name, data); }

    void putDecoded(String name, Image img) { decoded.put(name, img); }

    Image getDecoded(String name) { return decoded.get(name); }

    byte[] getRaw(String name) { return files.get(name); }

    /** Returns names of all stored files that look like images (png/jpg). */
    java.util.List<String> imageNames() {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String name : files.keySet()) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                result.add(name);
            }
        }
        return result;
    }

    boolean isEmpty() { return files.isEmpty(); }

    public InputStream open(String name) throws IOException {
        byte[] data = files.get(name);
        if (data == null) throw new IOException("not found: " + name);
        return new ByteArrayInputStream(data);
    }

    public boolean exists(String name) { return files.containsKey(name); }
}
