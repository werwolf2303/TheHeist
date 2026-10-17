package heist.engine.ui;

import java.util.ArrayList;
import java.util.List;

import heist.engine.Gfx;

/** Base node of the nib-built view tree (a tiny subset of UIView). Frames are in parent space. */
public class UiView {
    public String className = "UIView";
    public String name;               // outlet name on the file's owner, if any
    public float x, y, w, h;          // frame
    public Integer background;        // ARGB, null for none
    public boolean hidden;
    public float alpha = 1f;
    public boolean interactive = true;
    public boolean clips;
    public UiView parent;
    public final List<UiView> subviews = new ArrayList<UiView>();

    public void add(UiView v) {
        v.parent = this;
        subviews.add(v);
    }

    public void bringToFront() {
        if (parent != null && parent.subviews.remove(this)) parent.subviews.add(this);
    }

    /** Absolute position of this view's origin in screen space. */
    public float absX() { return x + (parent == null ? 0 : parent.absX()); }
    public float absY() { return y + (parent == null ? 0 : parent.absY()); }

    public final void render(Gfx g) {
        if (hidden || alpha <= 0f) return;
        Gfx g2 = g.create();
        try {
            g2.translate(x, y);
            if (clips) g2.clipRect(0, 0, Math.round(w), Math.round(h));
            if (alpha < 1f) g2.setAlpha(g2.getAlpha() * alpha);
            if (background != null && (background >>> 24) > 0) {
                g2.setColor(background);
                g2.fillRect(0, 0, Math.round(w), Math.round(h));
            }
            drawContent(g2);
            for (int i = 0; i < subviews.size(); i++) subviews.get(i).render(g2);
        } finally {
            g2.dispose();
        }
    }

    protected void drawContent(Gfx g) {}

    /** Deepest interactive view containing the point (given in this view's parent space). */
    public UiView hitTest(float px, float py) {
        if (hidden || alpha <= 0.01f || !interactive) return null;
        float lx = px - x, ly = py - y;
        if (lx < 0 || ly < 0 || lx >= w || ly >= h) return null;
        for (int i = subviews.size() - 1; i >= 0; i--) {
            UiView hit = subviews.get(i).hitTest(lx, ly);
            if (hit != null) return hit;
        }
        return this;
    }

    public UiView find(String outlet) {
        if (outlet.equals(name)) return this;
        for (UiView v : subviews) {
            UiView f = v.find(outlet);
            if (f != null) return f;
        }
        return null;
    }

    public String toString() {
        return className + (name != null ? "(" + name + ")" : "") + "[" + x + "," + y + " " + w + "x" + h + "]";
    }
}
