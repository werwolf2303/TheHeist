package heist.engine.ui;

import heist.engine.Gfx;

import java.util.HashMap;
import java.util.Map;

/** Helper that routes pointer input to nib-built buttons and runs their actions by selector name. */
public final class UiInput {
    private final Map<String, Runnable> actions = new HashMap<String, Runnable>();
    private UiButton pressed;
    private UiView root;

    public void setRoot(UiView root) { this.root = root; }

    public void on(String selector, Runnable r) { actions.put(selector, r); }

    /** Wires every action binding found in a loaded nib to its source button. */
    public void bind(NibLoader.Result r) {
        for (NibLoader.Binding b : r.bindings) {
            if (b.source instanceof UiButton) ((UiButton) b.source).action = b.selector;
        }
    }

    /** @return true if the press landed on a button (so the scene should not treat it as a game tap) */
    public boolean press(float x, float y) {
        if (root == null) return false;
        UiView v = root.hitTest(x, y);
        if (v instanceof UiButton && ((UiButton) v).enabled) {
            pressed = (UiButton) v;
            pressed.pressed = true;
            return true;
        }
        return false;
    }

    public boolean release(float x, float y) {
        if (pressed == null) return false;
        UiButton b = pressed;
        pressed = null;
        b.pressed = false;
        UiView v = root.hitTest(x, y);
        if (v == b && b.action != null) {
            Runnable r = actions.get(b.action);
            if (r != null) r.run();
        }
        return true;
    }

    public boolean isPressing() { return pressed != null; }

    public void render(Gfx g) {
        if (root != null) root.render(g);
    }
}
