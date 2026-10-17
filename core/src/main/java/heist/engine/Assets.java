package heist.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import heist.platform.Host;

/** Loads game resources. Prefers @2x variants (drawn at half size) when present. */
public final class Assets {
    private static final Map<String, Sprite> sprites = new HashMap<String, Sprite>();
    private static final Map<String, Fnt> fonts = new HashMap<String, Fnt>();
    private static final Map<String, Boolean> known = new HashMap<String, Boolean>();
    private static final Set<String> warned = new HashSet<String>();

    private Assets() {}

    /** Forgets all loaded resources (call after the asset source changes). */
    public static synchronized void reset() {
        sprites.clear();
        fonts.clear();
        known.clear();
    }

    /** True if the game files are there. */
    public static boolean isInstalled() {
        return exists("Puzzles.plist");
    }

    public static InputStream open(String name) throws IOException {
        return Host.get().assets().open(name);
    }

    /** The whole file. */
    public static byte[] bytes(String name) throws IOException {
        InputStream in = open(name);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    public static synchronized boolean exists(String name) {
        Boolean b = known.get(name);
        if (b == null) {
            b = Host.get().assets().exists(name);
            known.put(name, b);
        }
        return b;
    }

    /** Returns the sprite for the given relative path, or a magenta placeholder if it is missing. */
    public static synchronized Sprite image(String name) {
        Sprite s = sprites.get(name);
        if (s != null) return s;
        s = load(name);
        sprites.put(name, s);
        return s;
    }

    /** Returns null if neither the image nor its @2x variant exists. */
    public static Sprite imageOrNull(String name) {
        if (!exists(name) && !exists(retina(name))) return null;
        return image(name);
    }

    /** Returns the first name that exists on disk. */
    public static Sprite firstOf(String... names) {
        for (String n : names) {
            if (exists(n) || exists(retina(n))) return image(n);
        }
        return image(names[0]);
    }

    private static String retina(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name + "@2x";
        return name.substring(0, dot) + "@2x" + name.substring(dot);
    }

    private static Sprite load(String name) {
        String[] tries = { retina(name), name };
        float[] scales = { 0.5f, 1f };
        for (int k = 0; k < tries.length; k++) {
            if (!exists(tries[k])) continue;
            Image img = null;
            try {
                img = Host.get().decodeImageNamed(tries[k], bytes(tries[k]));
            } catch (IOException e) {
                // reported below
            }
            if (img != null) {
                Host.get().imageLoaded(img, tries[k]);
                return new Sprite(img, scales[k]);
            }
            warn("cannot read image " + tries[k]);
        }
        warn("missing image " + name);
        Image ph = Host.get().createImage(16, 16);
        ph.clear(0xFFFF00FF);
        return new Sprite(ph, 1f);
    }

    /** Loads "<name>.ttf" from the game files at the given point size. */
    public static synchronized Fnt font(String name, float size) {
        String key = name + "@" + size;
        Fnt f = fonts.get(key);
        if (f != null) return f;
        try {
            f = Host.get().loadFont(name, size);
        } catch (IOException e) {
            warn("cannot load font " + name + ": " + e.getMessage());
            f = Fnt.sans(true, size);
        } catch (RuntimeException e) {
            warn("cannot load font " + name + ": " + e.getMessage());
            f = Fnt.sans(true, size);
        }
        fonts.put(key, f);
        return f;
    }

    private static void warn(String msg) {
        if (warned.add(msg)) Host.get().warn("[assets] " + msg);
    }
}
