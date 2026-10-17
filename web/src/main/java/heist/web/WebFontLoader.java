package heist.web;

import java.io.IOException;
import java.io.InputStream;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Uint8Array;

import heist.engine.Fnt;
import heist.platform.AssetSource;

/** Registers a .ttf game file as a browser font via the CSS Font Loading API. No TeaVM binding exists for FontFace. */
final class WebFontLoader {
    private WebFontLoader() {}

    private static int nextId;

    @JSBody(params = { "name", "buffer" }, script = ""
            + "var f = new FontFace(name, buffer);"
            + "return f.load().then(function (loaded) { document.fonts.add(loaded); return loaded; });")
    private static native JSPromise<JSObject> loadFontFace(String name, ArrayBuffer buffer);

    static Fnt load(AssetSource assets, String name, float size) throws IOException {
        byte[] bytes = readAll(assets.open(name + ".ttf"));
        ArrayBuffer buffer = new ArrayBuffer(bytes.length);
        Uint8Array view = new Uint8Array(buffer);
        for (int i = 0; i < bytes.length; i++) view.set(i, (short) (bytes[i] & 0xff));

        // unique name avoids collisions with CSS generic families and same-name calls at different sizes
        String family = "heist-font-" + (nextId++) + "-" + name;
        // fire-and-forget: loadFont() may be called from rAF (not a Thread), where await() throws
        loadFontFace(family, buffer);
        return new WebFnt(family, false, size);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
