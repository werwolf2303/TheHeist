package heist.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import heist.engine.Audio;
import heist.engine.Fnt;
import heist.engine.Gfx;
import heist.engine.Key;
import heist.engine.Overlay;
import heist.engine.Save;
import heist.game.Disarm;
import heist.game.HubScene;
import heist.game.Nav;
import heist.platform.CameraPreview;
import heist.platform.Host;

/**
 * Developer menu of the safe room: on desktop, press D; on Android (debug builds only, see MainActivity),
 * tap with three fingers anywhere on the game area. It can replay the big animations and move the player up
 * or down a security stage. Replays run on a rebuilt safe room and, once everything has stopped moving, the
 * saved game is put back exactly as it was before. It sits on top of the game as an {@link Overlay}, so it
 * gets the input first and draws over everything; it works on whichever safe room is current.
 */
public final class DebugMenu implements Overlay {
    private interface Prepare { void run(Disarm d); }

    /** One replayable animation. */
    private static final class Animation {
        final String name;
        final Prepare prepare;          // sets up the save before the safe room is rebuilt
        final boolean fillBar;          // fire the bar-full level-up once the room is ready
        Animation(String name, Prepare prepare, boolean fillBar) {
            this.name = name;
            this.prepare = prepare;
            this.fillBar = fillBar;
        }
    }

    private static final List<Animation> ANIMATIONS = new ArrayList<Animation>();
    static {
        ANIMATIONS.add(new Animation("First launch: power-up + intro call", new Prepare() { public void run(Disarm d) {
            d.debugSetLevel(0);
            Disarm.markIntroSeen(false);
        } }, false));
        ANIMATIONS.add(stage("Level up: floor lasers", Disarm.LASERS));
        ANIMATIONS.add(stage("Level up: cameras", Disarm.CAMERAS));
        ANIMATIONS.add(stage("Level up: electricity", Disarm.ELECTRICITY));
        ANIMATIONS.add(stage("Level up: bars + safe cracking", Disarm.BARS));
        ANIMATIONS.add(new Animation("Safe cracking (vault)", new Prepare() { public void run(Disarm d) {
            d.debugSetLevel(Disarm.BARS);
            Disarm.markIntroSeen(true);
        } }, false));
    }

    /** Plays the level-up that clears the given security stage, starting from the state right before it. */
    private static Animation stage(String name, final int level) {
        return new Animation(name, new Prepare() { public void run(Disarm d) {
            d.debugSetLevel(Disarm.debugLevelBefore(level));
            Disarm.markIntroSeen(true);
        } }, true);
    }

    /** A saved-game state the room can be set to (the disarm level it stands for). */
    private static final class State {
        final String name;
        final int level;
        final boolean smoke;   // also drop the vault into its just-cracked, still-smoking look
        State(String name, int level) { this(name, level, false); }
        State(String name, int level, boolean smoke) { this.name = name; this.level = level; this.smoke = smoke; }
    }

    private static final State[] STATES = {
        new State("Vault normal (level 1)", 1),
        new State("Floor lasers cleared (level 4)", Disarm.LASERS),
        new State("Cameras cleared (level 6)", Disarm.CAMERAS),
        new State("Electricity cleared (level 7)", Disarm.ELECTRICITY),
        new State("Vault cracked (level 10)", Disarm.SAFE_SEQUENCE),
        new State("Vault cracked + smoke (level 10)", Disarm.SAFE_SEQUENCE, true),
        new State("100% completed (level 12)", Disarm.COMPLETE),
    };

    private static final String[][] CALLS = {
        { "Intro call (1.0)", "secureCall_1.0" },
        { "Lasers call (2.0)", "secureCall_2.0" },
        { "Cameras call (3.0)", "secureCall_3.0" },
        { "Electricity call (4.0)", "secureCall_4.0" },
        { "Bars call (4.1)", "secureCall_4.1" },
        { "Pedestal hint call", "secureCall_hint" },
        { "Prize video call", "secureCallVideo" },
        { "Prize video only (no prelude)", "secureCallVideoOnly" },
        { "Prize video call + camera diag", "secureCallVideo+diag" },
    };

    private static final int MAIN = 0, ANIM = 1, STATE = 2, CALL = 3;
    private static final int ROW_H = 24;
    private static final int PANEL_X = 20, PANEL_Y = 40, PANEL_W = 280, HEADER_H = 30;

    private boolean confirmReset;
    private boolean open;
    private int page = MAIN;
    private int selected;
    private String message;
    private float messageT;

    // an armed replay (it lives in the rebuilt safe room)
    private Map<String, Object> restoreTo;
    private Animation armed;
    private boolean fired;
    private float readyT, quietT, runT;

    private final Runnable showLayerWindow;
    private final java.util.concurrent.Callable<Boolean> layerWindowOpen;

    /** No layer window on this port (e.g. Android): that row is left out of the menu entirely. */
    public DebugMenu() {
        this(null, null);
    }

    /**
     * @param showLayerWindow  opens the layer window, or null if this port doesn't have one
     * @param layerWindowOpen  tells whether it is open (ignored if showLayerWindow is null)
     */
    public DebugMenu(Runnable showLayerWindow, java.util.concurrent.Callable<Boolean> layerWindowOpen) {
        this.showLayerWindow = showLayerWindow;
        this.layerWindowOpen = layerWindowOpen;
    }

    private HubScene hub() { return Nav.hub(); }

    private boolean available() {
        HubScene h = hub();
        return h != null && h.debugAvailable();
    }

    // ---- input ---------------------------------------------------------------------------------------------

    public boolean key(Key key) {
        if (key == Key.DEBUG) {
            if (!available()) return false;
            open = !open;
            page = MAIN;
            selected = 0;
            confirmReset = false;
            return true;
        }
        if (key == Key.BACK) {
            if (open) { open = false; return true; }
            if (restoreTo != null) { finishReplay(); return true; }
            return false;
        }
        if (!open) return false;
        int n = rows().size();
        if (key == Key.UP) selected = (selected + n - 1) % n;
        else if (key == Key.DOWN) selected = (selected + 1) % n;
        else if (key == Key.SELECT) activate(selected);
        return true;
    }

    public boolean pointerPressed(float x, float y) { return open; }

    public boolean pointerDragged(float x, float y) { return open; }

    public boolean pointerReleased(float x, float y) {
        if (!open) return false;
        int row = (int) ((y - PANEL_Y - HEADER_H) / ROW_H);
        if (x >= PANEL_X && x < PANEL_X + PANEL_W && y >= PANEL_Y + HEADER_H && row >= 0 && row < rows().size()) {
            selected = row;
            activate(row);
        } else if (x < PANEL_X || x >= PANEL_X + PANEL_W || y < PANEL_Y || y >= PANEL_Y + panelHeight()) {
            open = false;
        }
        return true;
    }

    private boolean hasLayerWindow() { return showLayerWindow != null; }

    private boolean layerWindowIsOpen() {
        if (layerWindowOpen == null) return false;
        try { return Boolean.TRUE.equals(layerWindowOpen.call()); } catch (Exception e) { return false; }
    }

    private List<String> rows() {
        List<String> r = new ArrayList<String>();
        if (page == MAIN) {
            r.add("Start animation  >");
            r.add("Set scene state  >");
            r.add("Trigger call  >");
            r.add("Level up (fill the bar)");
            r.add("Level down");
            r.add("Camera status");
            if (hasLayerWindow()) r.add(layerWindowIsOpen() ? "Layer window (open, close it with x)" : "Layer window");
            r.add(confirmReset ? "Really reset? Tap again to confirm" : "Reset puzzles and levels");
            r.add("Close");
        } else if (page == ANIM) {
            for (Animation a : ANIMATIONS) r.add(a.name);
            r.add("<  Back");
        } else if (page == STATE) {
            for (State st : STATES) r.add(st.name);
            r.add("<  Back");
        } else {
            for (String[] c : CALLS) r.add(c[0]);
            r.add("<  Back");
        }
        return r;
    }

    private void activate(int row) {
        if (page == MAIN) {
            int resetRow = hasLayerWindow() ? 7 : 6;
            if (row != resetRow) confirmReset = false;
            if (row == 0) { page = ANIM; selected = 0; }
            else if (row == 1) { page = STATE; selected = 0; }
            else if (row == 2) { page = CALL; selected = 0; }
            else if (row == 3) levelUp();
            else if (row == 4) levelDown();
            else if (row == 5) cameraStatus();
            else if (hasLayerWindow() && row == 6) { showLayerWindow.run(); open = false; }
            else if (row == resetRow) resetPuzzles();
            else open = false;
            return;
        }
        int count = page == ANIM ? ANIMATIONS.size() : page == STATE ? STATES.length : CALLS.length;
        if (row >= count) { page = MAIN; selected = 0; return; }
        if (page == ANIM) start(ANIMATIONS.get(row));
        else if (page == STATE) setState(STATES[row]);
        else triggerCall(CALLS[row][1]);
    }

    // ---- actions ---------------------------------------------------------------------------------------------

    private void say(String m) { message = m; messageT = 2.5f; }

    private boolean quiet() {
        HubScene h = hub();
        if (h == null || !h.debugIdle()) { say("Wait until the room is quiet"); return false; }
        return true;
    }

    private void levelUp() {
        if (!quiet()) return;
        Disarm.Result r = Disarm.get().debugFillBar();
        if (r == null) { say("Already at the top"); return; }
        open = false;
        hub().debugLevelFilled(r);
    }

    private void levelDown() {
        if (!quiet()) return;
        if (!Disarm.get().debugLevelDown()) { say("Already at the start"); return; }
        open = false;
        rebuild(null, null);
    }

    private CameraPreview cameraStatusProbe;

    /** Starts the camera and keeps a live preview on screen until the debug menu is closed again. */
    private void cameraStatus() {
        CameraPreview camera = Host.get().frontCamera();
        if (camera == null) { say("Camera: none found (or still probing)"); return; }
        camera.start();
        cameraStatusProbe = camera;
        say("Camera: started, see preview top-right");
    }

    private void start(Animation a) {
        if (restoreTo != null) { say("Another replay is running (Esc aborts)"); return; }
        if (!quiet()) return;
        open = false;
        rebuild(Save.snapshot(), a);
    }

    /** Rings a call on top of the current room; nothing changes in the save. */
    private void triggerCall(String file) {
        if (!quiet()) return;
        open = false;
        if (file.equals("secureCallVideoOnly")) { hub().showVideoCallDirectly(); return; }
        if (file.equals("secureCallVideo+diag")) { hub().showVideoCallWithCameraDebug(); return; }
        if (file.equals("secureCall_hint")) hub().showHintCall();
        else hub().showCallThen(file, null);
    }

    /** Sets the saved game to a scene state (this changes the save for real) and rebuilds the room. */
    private void setState(State st) {
        if (restoreTo != null) { say("A replay is running (Esc aborts)"); return; }
        if (!quiet()) return;
        Disarm.get().debugSetLevel(st.level);
        Disarm.markIntroSeen(true);
        open = false;
        rebuild(null, null);
        if (st.smoke) hub().debugShowVaultSmoke();
    }

    private void resetPuzzles() {
        if (restoreTo != null) { say("A replay is running (Esc aborts)"); return; }
        if (!quiet()) return;
        if (!confirmReset) { confirmReset = true; return; }
        confirmReset = false;
        Disarm.get().debugResetPuzzles();
        open = false;
        rebuild(null, null);
    }

    /** Throws the safe room away and builds a new one from the (possibly edited) save. */
    private void rebuild(Map<String, Object> snapshot, Animation a) {
        if (a != null) a.prepare.run(Disarm.get());
        hub().shutdown();
        Disarm.reload();
        HubScene fresh = new HubScene();
        if (a != null) arm(snapshot, a);
        Nav.setHub(fresh);
        Nav.show(fresh);
    }

    private void arm(Map<String, Object> snapshot, Animation a) {
        restoreTo = snapshot;
        armed = a;
        fired = !a.fillBar;      // plain replays start by themselves
        readyT = quietT = runT = 0f;
    }

    /** Puts the saved game back and rebuilds the room the way it was. */
    private void finishReplay() {
        Map<String, Object> snap = restoreTo;
        restoreTo = null;
        armed = null;
        hub().shutdown();
        Save.restore(snap);
        Disarm.reload();
        HubScene fresh = new HubScene();
        Nav.setHub(fresh);
        Nav.show(fresh);
    }

    // ---- per frame -------------------------------------------------------------------------------------------

    public void update(float dt) {
        if (messageT > 0f) messageT -= dt;
        // the preview (see render()) keeps running until the whole debug menu is closed, not on a timer,
        // so there's as long as needed to actually look at it
        if (cameraStatusProbe != null && !open) {
            cameraStatusProbe.stop();
            cameraStatusProbe = null;
        }
        if (open && !available()) open = false;
        if (restoreTo == null || hub() == null) return;
        HubScene h = hub();
        runT += dt;
        if (!fired) {
            if (h.debugReady()) readyT += dt; else readyT = 0f;
            if (readyT >= 0.6f) {
                fired = true;
                if (armed.fillBar) {
                    Disarm.Result r = Disarm.get().debugFillBar();
                    if (r != null) h.debugLevelFilled(r);
                }
                quietT = 0f;
                runT = 0f;
            }
            return;
        }
        // wait for everything to stop moving, then enjoy the result for a moment before restoring
        if (runT < 1.5f) return;
        if (h.debugIdle()) quietT += dt; else quietT = 0f;
        if (quietT >= 3f) finishReplay();
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    private int panelHeight() { return HEADER_H + rows().size() * ROW_H + 8; }

    public void render(Gfx g) {
        if (!open && restoreTo == null && messageT <= 0f) return;
        Gfx gd = g.create();
        if (restoreTo != null && !open) {
            gd.setColor(0xAA000000);
            gd.fillRect(0, 0, 320, 18);
            gd.setFont(Fnt.sans(false, 11));
            gd.setColor(0xFFFFD25A);
            gd.drawString("DEBUG replay: " + (armed != null ? armed.name : "") + "  -  Esc restores now", 6, 13);
        }
        if (messageT > 0f && !open && message != null) {
            gd.setFont(Fnt.sans(true, 12));
            gd.setColor(0xBE000000);
            gd.fillRoundRect(40, 430, 240, 24, 10, 10);
            gd.setColor(0xFFFFFFFF);
            gd.drawString(message, 160 - gd.stringWidth(message) / 2f, 446);
        }
        if (open) {
            gd.setColor(0xFF000000);
            Gfx dim = gd.create();
            dim.setAlpha(0.55f);
            dim.fillRect(0, 0, 320, 480);
            dim.dispose();
            int h = panelHeight();
            gd.setColor(0xF0181A1E);
            gd.fillRoundRect(PANEL_X, PANEL_Y, PANEL_W, h, 12, 12);
            gd.setColor(0xFF5AC8FF);
            gd.strokeRoundRect(PANEL_X, PANEL_Y, PANEL_W, h, 12, 12);
            gd.setFont(Fnt.sans(true, 13));
            gd.drawString(page == MAIN ? "DEBUG MENU   (D / 3-finger tap to close)" : page == ANIM ? "START ANIMATION" : page == STATE ? "SET SCENE STATE" : "TRIGGER CALL", PANEL_X + 12, PANEL_Y + 20);
            gd.setFont(Fnt.sans(false, 13));
            List<String> rows = rows();
            for (int i = 0; i < rows.size(); i++) {
                int ry = PANEL_Y + HEADER_H + i * ROW_H;
                if (i == selected) {
                    gd.setColor(0xC83C82BE);
                    gd.fillRoundRect(PANEL_X + 6, ry, PANEL_W - 12, ROW_H - 2, 8, 8);
                }
                gd.setColor(0xFFFFFFFF);
                gd.drawString(rows.get(i), PANEL_X + 14, ry + 16);
            }
            if (messageT > 0f && message != null) {
                gd.setFont(Fnt.sans(true, 12));
                gd.setColor(0xFFFFD25A);
                gd.drawString(message, PANEL_X + 12, PANEL_Y + h + 18);
            }
        }
        if (cameraStatusProbe != null) {
            heist.engine.Image frame = cameraStatusProbe.currentFrame();
            int bw = 120, bh = 90, bx = 320 - bw - 10, by = 10;
            gd.setColor(0xFF000000);
            gd.fillRect(bx - 2, by - 2, bw + 4, bh + 4);
            if (frame != null) gd.drawImage(frame, bx, by, bw, bh);   // drawn raw, no rotate/scale - just a sanity check
            gd.setColor(0xFF5AC8FF);
            gd.strokeRoundRect(bx - 2, by - 2, bw + 4, bh + 4, 0, 0);
        }
        gd.dispose();
    }
}
