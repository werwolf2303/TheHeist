package heist.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * Synchronous-looking wrapper over the Origin Private File System. No TeaVM binding exists for OPFS, so
 * every operation is its own @JSBody with JSPromise.await(). All public methods take '/'-separated paths;
 * OPFS getFileHandle/getDirectoryHandle only accept a single segment each, so dirOf/dirAt walk the path
 * one segment at a time (a '/' in the name makes the promise reject).
 */
final class Opfs {
    private Opfs() {}

    @JSBody(params = {}, script = "return navigator.storage.getDirectory();")
    private static native JSPromise<JSObject> getRoot();

    @JSBody(params = { "dir", "name" }, script = "return dir.getDirectoryHandle(name, {create: true});")
    private static native JSPromise<JSObject> getDirectoryHandleCreate(JSObject dir, String name);

    @JSBody(params = { "dir", "name" }, script = "return dir.getFileHandle(name, {create: true});")
    private static native JSPromise<JSObject> getFileHandleCreate(JSObject dir, String name);

    // catch in JS so the promise always resolves (null if missing): JSPromise.await() throws on a rejected promise
    @JSBody(params = { "dir", "name" }, script = "return dir.getFileHandle(name).catch(function() { return null; });")
    private static native JSPromise<JSObject> getFileHandleExisting(JSObject dir, String name);

    @JSBody(params = "handle", script = "return handle.createWritable();")
    private static native JSPromise<JSObject> createWritable(JSObject handle);

    @JSBody(params = { "writable", "bytes" }, script = "return writable.write(bytes);")
    private static native JSPromise<JSObject> writeChunk(JSObject writable, Uint8Array bytes);

    @JSBody(params = "writable", script = "return writable.close();")
    private static native JSPromise<JSObject> closeWritable(JSObject writable);

    @JSBody(params = "handle", script = "return handle.getFile();")
    private static native JSPromise<JSObject> getFile(JSObject handle);

    @JSBody(params = "file", script = "return file.arrayBuffer();")
    private static native JSPromise<ArrayBuffer> readArrayBuffer(JSObject file);

    @JSBody(params = { "dir", "name" }, script = "return dir.removeEntry(name).catch(function() {});")
    private static native JSPromise<JSObject> removeEntryQuiet(JSObject dir, String name);

    private static JSObject root;

    private static JSObject root() {
        if (root == null) root = getRoot().await();
        return root;
    }

    /** Directory handle for the given path's parent, creating segments as it goes. */
    private static JSObject dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? root() : dirAt(path.substring(0, slash));
    }

    /** Directory handle for this '/'-separated path, creating each segment as it goes. */
    private static JSObject dirAt(String dirPath) {
        JSObject dir = root();
        if (dirPath.isEmpty()) return dir;
        for (String segment : dirPath.split("/")) {
            dir = getDirectoryHandleCreate(dir, segment).await();
        }
        return dir;
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** Null if nothing is stored under this name. */
    static byte[] read(String name) throws IOException {
        JSObject handle = getFileHandleExisting(dirOf(name), fileNameOf(name)).await();
        if (handle == null) return null;   // nothing stored under this name yet (including a missing parent dir)
        JSObject file = getFile(handle).await();
        ArrayBuffer buf = readArrayBuffer(file).await();
        Uint8Array view = new Uint8Array(buf);
        byte[] out = new byte[buf.getByteLength()];
        for (int i = 0; i < out.length; i++) out[i] = (byte) view.get(i);
        return out;
    }

    static void write(String name, byte[] data) throws IOException {
        JSObject handle = getFileHandleCreate(dirOf(name), fileNameOf(name)).await();
        JSObject writable = createWritable(handle).await();
        Uint8Array view = new Uint8Array(data.length);
        for (int i = 0; i < data.length; i++) view.set(i, (short) (data[i] & 0xff));
        writeChunk(writable, view).await();
        closeWritable(writable).await();
    }

    static void delete(String name) {
        removeEntryQuiet(dirOf(name), fileNameOf(name)).await();
    }

    // walks the whole async iterator to completion, returning [{name, kind}] objects
    @JSBody(params = "dir", script = ""
            + "return (function () {"
            + "  var it = dir.values();"
            + "  var entries = [];"
            + "  function step() {"
            + "    return it.next().then(function (r) {"
            + "      if (r.done) return entries;"
            + "      entries.push({name: r.value.name, kind: r.value.kind});"
            + "      return step();"
            + "    });"
            + "  }"
            + "  return step();"
            + "})();")
    private static native JSPromise<JSArray<JSObject>> listAllEntries(JSObject dir);

    @JSBody(params = "entry", script = "return entry.name;")
    private static native String entryName(JSObject entry);

    @JSBody(params = "entry", script = "return entry.kind;")
    private static native String entryKind(JSObject entry);

    /** All stored paths starting with prefix. Walks recursively — OPFS listing is flat per-directory. */
    static List<String> listNames(String prefix) {
        List<String> out = new ArrayList<>();
        int slash = prefix.lastIndexOf('/');
        String startDir = slash < 0 ? "" : prefix.substring(0, slash);
        JSObject dir = dirAt(startDir);
        String dirPrefix = startDir.isEmpty() ? "" : startDir + "/";
        collectNames(dir, dirPrefix, prefix, out);
        return out;
    }

    private static void collectNames(JSObject dir, String dirPrefix, String prefix, List<String> out) {
        JSArrayReader<JSObject> entries = listAllEntries(dir).await();
        for (int i = 0; i < entries.getLength(); i++) {
            JSObject entry = entries.get(i);
            String name = entryName(entry);
            String fullPath = dirPrefix + name;
            if ("directory".equals(entryKind(entry))) {
                JSObject subDir = getDirectoryHandleCreate(dir, name).await();
                collectNames(subDir, fullPath + "/", prefix, out);
            } else if (fullPath.startsWith(prefix)) {
                out.add(fullPath);
            }
        }
    }
}
