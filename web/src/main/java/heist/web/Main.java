package heist.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.typedarrays.Uint8Array;

import heist.engine.Game;
import heist.engine.Key;
import heist.engine.Save;
import heist.debug.DebugMenu;
import heist.game.HintCall;
import heist.game.HubScene;
import heist.game.Nav;
import heist.platform.Host;

/**
 * The browser port's entry point. The play button shows an upload screen until the player supplies their own
 * .ipa; {@link #checkExistingGame} skips that if OPFS already has a previously imported copy.
 *
 * Every @JSExport method that touches {@link Opfs} starts a Thread: JSPromise.await() only works inside a
 * Thread context, not from a @JSExport entry point ("Suspension point reached from non-threading context").
 */
public final class Main {
    private Main() {}

    private static WebPlatform platform;
    private static WebAssetSource assets;
    private static Game game;
    private static WebGfx gfx;
    private static HTMLCanvasElement canvas;
    private static long lastFrameMillis = -1;
    private static boolean debugEnabled = false;

    @JSBody(params = {}, script = "return document.visibilityState;")
    private static native String documentVisibilityState();

    @JSBody(params = {}, script = "return window.devicePixelRatio || 1;")
    private static native double devicePixelRatio();

    @JSBody(params = "el", script = "return el.getBoundingClientRect().width;")
    private static native double elementCssWidth(org.teavm.jso.dom.html.HTMLElement el);

    @JSBody(params = "el", script = "return el.getBoundingClientRect().height;")
    private static native double elementCssHeight(org.teavm.jso.dom.html.HTMLElement el);

    /** Requests OPFS storage not be evicted. Silently refused by some browsers; must be called from a user gesture. */
    @JSBody(params = {}, script = "return navigator.storage.persist();")
    private static native JSPromise<JSObject> requestPersistentStorage();

    public static void main(String[] args) {
        platform = new WebPlatform();
        Host.set(platform);
        assets = platform.assetSource();
        Save.load();
        setupCanvas();
    }

    private static void setupCanvas() {
        HTMLDocument doc = Window.current().getDocument();
        canvas = (HTMLCanvasElement) doc.createElement("canvas");
        canvas.setWidth(Game.W);
        canvas.setHeight(Game.H);
        doc.getBody().appendChild(canvas);
        CanvasRenderingContext2D ctx = (CanvasRenderingContext2D) canvas.getContext("2d");
        gfx = new WebGfx(ctx);
        doc.addEventListener("visibilitychange", (EventListener<org.teavm.jso.dom.events.Event>) e -> {
            if (game != null && "hidden".equals(documentVisibilityState())) HintCall.onExit();
        });
    }

    /** Checks for a previously imported game; reports back through callbacks (must use a Thread for await()). */
    @JSExport
    public static void checkExistingGame(ExistingGameCallback onFound, ExistingGameCallback onNotFound) {
        new Thread(() -> {
            boolean found = WebImportFlow.loadExisting(platform, assets);
            if (found) onFound.call(); else onNotFound.call();
        }).start();
    }

    /** Called from the play button (a user gesture, required by requestPersistentStorage). */
    @JSExport
    public static void onPlayButtonClicked() {
        requestPersistentStorage().then(granted -> {
            System.out.println("[storage] persistent: " + granted);
            return null;
        }, err -> {
            System.err.println("[storage] persist() request failed: " + err);
            return null;
        });
        WebAudioBackend.resumeOnUserGesture();
    }

    /** Called by the page to enable the in-game developer menu (press D on the hub). */
    @JSExport
    public static void setDebugEnabled(boolean enabled) { debugEnabled = enabled; }

    /** Called by the page once the player has picked an .ipa file (its raw bytes, read via FileReader). */
    @JSExport
    public static void startImport(Uint8Array ipaBytes, ImportProgressCallback onProgress,
            ImportDoneCallback onDone, ImportErrorCallback onError) {
        byte[] bytes = new byte[ipaBytes.getLength()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ipaBytes.get(i);

        new Thread(() -> {
            WebImportFlow flow = new WebImportFlow(platform, assets, new WebImportFlow.ProgressListener() {
                public void onProgress(String phase, float fraction) { onProgress.call(phase, fraction); }

                public void onDone() {
                    onDone.call();
                    startGame();
                }

                public void onError(String message) { onError.call(message); }
            });
            flow.importFrom(bytes);
        }).start();
    }

    /** Starts the already-imported game. Wraps in a Thread so both the @JSExport and the import-done paths are safe. */
    @JSExport
    public static void startGame() {
        new Thread(() -> {
            platform.preDecodeThumbnails();
            game = new Game();
            Nav.init(game);
            if (debugEnabled) game.setOverlay(new DebugMenu());
            HubScene hub = new HubScene();
            Nav.setHub(hub);
            game.setScene(hub);
            bindInput();
            Window.requestAnimationFrame(Main::frame);
        }).start();
    }

    @org.teavm.jso.JSFunctor
    public interface ExistingGameCallback extends JSObject {
        void call();
    }

    @org.teavm.jso.JSFunctor
    public interface ImportProgressCallback extends JSObject {
        void call(String phase, float fraction);
    }

    @org.teavm.jso.JSFunctor
    public interface ImportDoneCallback extends JSObject {
        void call();
    }

    @org.teavm.jso.JSFunctor
    public interface ImportErrorCallback extends JSObject {
        void call(String message);
    }

    private static void bindInput() {
        canvas.addEventListener(MouseEvent.MOUSEDOWN, (EventListener<MouseEvent>) e -> {
            double dpr = devicePixelRatio();
            game.touchDown((float)(e.getOffsetX() * dpr), (float)(e.getOffsetY() * dpr));
            e.preventDefault();
        });
        canvas.addEventListener(MouseEvent.MOUSEMOVE, (EventListener<MouseEvent>) e -> {
            double dpr = devicePixelRatio();
            game.touchMove((float)(e.getOffsetX() * dpr), (float)(e.getOffsetY() * dpr));
            e.preventDefault();
        });
        canvas.addEventListener(MouseEvent.MOUSEUP, (EventListener<MouseEvent>) e -> {
            double dpr = devicePixelRatio();
            game.touchUp((float)(e.getOffsetX() * dpr), (float)(e.getOffsetY() * dpr));
            e.preventDefault();
        });
        Window.current().getDocument().addEventListener("keydown", (EventListener<org.teavm.jso.dom.events.KeyboardEvent>) e -> {
            String k = e.getKey();
            if ("Escape".equals(k)) game.key(Key.BACK);
            else if ("ArrowUp".equals(k)) game.key(Key.UP);
            else if ("ArrowDown".equals(k)) game.key(Key.DOWN);
            else if ("Enter".equals(k) || " ".equals(k)) game.key(Key.SELECT);
            else if ("d".equals(k) || "D".equals(k)) game.key(Key.DEBUG);
        });
    }

    private static void frame(double timestampMillis) {
        double dpr = devicePixelRatio();
        int targetW = (int) Math.round(elementCssWidth(canvas) * dpr);
        int targetH = (int) Math.round(elementCssHeight(canvas) * dpr);
        if (targetW > 0 && targetH > 0 && (canvas.getWidth() != targetW || canvas.getHeight() != targetH)) {
            canvas.setWidth(targetW);
            canvas.setHeight(targetH);
        }

        long now = (long) timestampMillis;
        float dt = lastFrameMillis < 0 ? 0f : Math.min(0.05f, (now - lastFrameMillis) / 1000f);
        lastFrameMillis = now;
        game.update(dt);
        game.render(gfx, canvas.getWidth(), canvas.getHeight());
        Window.requestAnimationFrame(Main::frame);
    }
}
