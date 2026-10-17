package heist.web;

import java.io.IOException;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLImageElement;

import heist.engine.Fnt;
import heist.engine.Image;
import heist.platform.AssetSource;
import heist.platform.AudioBackend;
import heist.platform.CameraPreview;
import heist.platform.Platform;
import heist.platform.Storage;

/** The browser port's {@link Platform}: Canvas2D pictures, Web Audio, OPFS-backed progress, uploaded game files. */
final class WebPlatform implements Platform {
    private final WebAssetSource assets = new WebAssetSource();
    private final WebStorage storage = new WebStorage();
    private final WebAudioBackend audio = new WebAudioBackend();

    WebAssetSource assetSource() { return assets; }

    WebPlatform() {
    }

    /** Called synchronously from saveCompletedThumbnail — populates the cache before refreshCompleted runs. */
    @Override
    public void blobWritten(String name, byte[] data, heist.engine.Image decoded) {
        blobDecodeCache.put(data, decoded);
    }

    public Image createImage(int width, int height) { return new WebImage(width, height); }

    /** Decodes image bytes via HTMLImageElement.decode(), awaited via JSPromise.await() to keep the synchronous contract. */
    @JSBody(params = "img", script = "return img.decode();")
    private static native JSPromise<JSObject> decode(HTMLImageElement img);

    private static String mimeType(byte[] encoded) {
        // JPEG starts with FF D8; PNG starts with 89 50 4E 47
        if (encoded.length >= 2 && (encoded[0] & 0xff) == 0xFF && (encoded[1] & 0xff) == 0xD8) return "image/jpeg";
        return "image/png";
    }

    public Image decodeImage(byte[] encoded) {
        Image cached = blobDecodeCache.get(encoded);
        if (cached != null) return cached;
        try {
            HTMLDocument doc = Window.current().getDocument();
            HTMLImageElement img = (HTMLImageElement) doc.createElement("img");
            img.setSrc("data:" + mimeType(encoded) + ";base64," + Base64.getEncoder().encodeToString(encoded));
            decode(img).await();
            int w = img.getNaturalWidth(), h = img.getNaturalHeight();
            if (w <= 0 || h <= 0) return null;
            WebImage out = new WebImage(w, h);
            out.ctx.drawImage(img, 0, 0);
            blobDecodeCache.put(encoded, out);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // keyed by exact byte[] instance: decodeImage() can return pre-decoded results from the rAF loop where await() is forbidden
    private final Map<byte[], Image> blobDecodeCache = new IdentityHashMap<>();

    /** Pre-decodes all stored thumbnails (LevelCompleted-* blobs). Call from a Thread context. */
    void preDecodeThumbnails() {
        for (String name : storage.listBlobNames("LevelCompleted-")) {
            try {
                byte[] data = storage.readBlob(name);
                if (data != null) {
                    Image img = decodeImage(data);
                    if (img != null) blobDecodeCache.put(data, img);
                }
            } catch (IOException ignored) {}
        }
    }
    /** Returns pre-decoded image from cache if available (safe to call from rAF), else falls back to decodeImage(). */
    @Override
    public Image decodeImageNamed(String name, byte[] encoded) {
        Image cached = assets.getDecoded(name);
        if (cached != null) return cached;
        return decodeImage(encoded);
    }

    /** Pre-decodes all images whose names end in .png or .jpg. Must be called from a Thread context
     *  (e.g. the import thread) where await() is safe. */
    void preDecodeImages() {
        for (String name : assets.imageNames()) {
            Image img = decodeImage(assets.getRaw(name));
            if (img != null) assets.putDecoded(name, img);
        }
    }

    public Fnt sansFont(boolean bold, float size) { return new WebFnt("sans-serif", bold, size); }

    public Fnt loadFont(String name, float size) throws IOException {
        return WebFontLoader.load(assets, name, size);
    }

    public AssetSource assets() { return assets; }

    public Storage storage() { return storage; }

    public AudioBackend audio() { return audio; }

    public void warn(String message) { System.err.println("[warn] " + message); }

    public void error(String message) { System.err.println("[error] " + message); }

    /** DeviceProximityEvent was removed from all browsers; matches the original's iPod touch behaviour. */
    public boolean hasProximitySensor() { return false; }

    public CameraPreview frontCamera() { return null; }
}
