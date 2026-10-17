package heist.platform;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistent key/value storage for the player's progress, the equivalent of the original's NSUserDefaults, plus a
 * small blob store for the few larger files the game keeps alongside it (the finished-puzzle thumbnail snapshots -
 * see heist.game.LevelStrip). Values are Boolean, Integer, Long, Double or String; blobs are named byte[]s.
 *
 * This used to expose a java.io.File "Documents folder" directly, but that doesn't exist as a concept outside a
 * real filesystem (there is no File on a web port backed by the browser's Origin Private File System), so blob
 * access is a handful of named operations instead - every platform's own storage mechanism (a real directory on
 * desktop, internal app storage on Android, an OPFS directory in a browser) can implement this the same way.
 */
public interface Storage {
    /** Everything stored so far. */
    Map<String, Object> readAll();

    void put(String key, Object value);

    void remove(String key);

    void removeAll(Collection<String> keys);

    /** The names of every stored blob that starts with this prefix (e.g. "LevelCompleted-"). */
    List<String> listBlobNames(String prefix);

    /** Null if no blob is stored under this name. */
    byte[] readBlob(String name) throws IOException;

    void writeBlob(String name, byte[] data) throws IOException;

    /** No-op if nothing is stored under this name. */
    void deleteBlob(String name);
}
