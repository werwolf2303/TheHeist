package heist.desktop.debug;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Records every draw call in a frame (image, transform, composite, group). Read by {@link LayerWindow}. */
public final class LayerTrace {
    /** One drawn layer. */
    public static final class Entry {
        public final String group, name;
        public final BufferedImage img;
        /** Image pixel rect → 320x480 logical screen. */
        public final AffineTransform tx;
        public final Composite composite;
        public final boolean hidden;

        Entry(String group, String name, BufferedImage img, AffineTransform tx, Composite composite, boolean hidden) {
            this.group = group;
            this.name = name;
            this.img = img;
            this.tx = tx;
            this.composite = composite;
            this.hidden = hidden;
        }
    }

    interface RasterPainter {
        /** scope=true: inside a folded scope layer — preserve blend and alpha. */
        void paint(Graphics2D target, boolean scope);
    }

    public static volatile boolean enabled;

    private static final Set<String> hiddenNames = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> hiddenGroups = Collections.synchronizedSet(new HashSet<String>());
    private static List<Entry> building = new ArrayList<Entry>();
    private static volatile List<Entry> last = new ArrayList<Entry>();
    private static AffineTransform baseInverse = new AffineTransform();
    private static String group = "Scene";
    /** True once the scene started drawing in this frame (the clear of the letterbox bars before it is not a layer). */
    private static boolean active;

    private LayerTrace() {}

    // ---- recording (game thread) -------------------------------------------------------------------------------

    static AffineTransform baseInverse() { return baseInverse; }

    static boolean isActive() { return active; }

    /** base: 320x480 logical → view pixels (letterbox offset and scale). */
    public static void beginFrame(AffineTransform base) {
        building = new ArrayList<Entry>();
        group = "Scene";
        active = false;
        scopeName = null;
        try { baseInverse = base.createInverse(); } catch (Exception e) { baseInverse = new AffineTransform(); }
    }

    public static void endFrame() {
        last = building;
    }

    /** Sets the group for subsequent layers; first call of a frame activates recording. */
    static void group(String name) {
        active = true;
        if (enabled) group = name;
    }

    private static boolean hiddenNow(String name) {
        return hiddenNames.contains(name) || hiddenGroups.contains(group);
    }

    // a scope folds all its draw calls into one layer (particle emitters can be hundreds of images)
    private static String scopeName;
    private static boolean scopeHidden;
    private static BufferedImage scopeBuf;
    private static Rectangle scopeDirty;

    /** Returns false if this scope is hidden (caller must skip drawing). */
    static boolean beginScope(String name) {
        if (!enabled || !active) return true;
        scopeName = name;
        scopeHidden = hiddenNow(name);
        scopeBuf = null;
        scopeDirty = null;
        return !scopeHidden;
    }

    static void endScope() {
        if (!enabled || scopeName == null) return;
        if (scopeBuf != null && scopeDirty != null && !scopeDirty.isEmpty()) {
            BufferedImage part = scopeBuf.getSubimage(scopeDirty.x, scopeDirty.y, scopeDirty.width, scopeDirty.height);
            building.add(new Entry(group, scopeName, part, AffineTransform.getTranslateInstance(scopeDirty.x, scopeDirty.y),
                    AlphaComposite.SrcOver, false));
        } else if (scopeHidden) {
            building.add(new Entry(group, scopeName, new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new AffineTransform(),
                    AlphaComposite.SrcOver, true));
        }
        scopeName = null;
        scopeBuf = null;
    }

    /** Returns false if this layer is hidden (caller must skip drawing). */
    static boolean imageOp(String name, BufferedImage img, AffineTransform tx, Composite comp) {
        if (!active) return true;
        if (scopeName != null) {
            if (scopeHidden) return false;
            Rectangle2D b = tx.createTransformedShape(new Rectangle2D.Double(0, 0, img.getWidth(), img.getHeight())).getBounds2D();
            Rectangle r = clampToScreen(b);
            if (r == null) return true;
            if (scopeBuf == null) scopeBuf = new BufferedImage(320, 480, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gb = scopeBuf.createGraphics();
            gb.setComposite(comp);
            gb.drawImage(img, tx, null);
            gb.dispose();
            scopeDirty = scopeDirty == null ? r : scopeDirty.union(r);
            return true;
        }
        boolean hide = hiddenNow(name);
        building.add(new Entry(group, name, img, tx, comp, hide));
        return !hide;
    }

    /** logicalBounds: pixel footprint on the 320x480 screen. Returns false if this layer is hidden. */
    static boolean rasterOp(String name, Rectangle2D logicalBounds, Composite comp, RasterPainter p) {
        if (!active) return true;
        Rectangle r = clampToScreen(logicalBounds);
        if (scopeName != null) {
            if (scopeHidden) return false;
            if (r == null) return true;
            if (scopeBuf == null) scopeBuf = new BufferedImage(320, 480, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gb = scopeBuf.createGraphics();
            p.paint(gb, true);
            gb.dispose();
            scopeDirty = scopeDirty == null ? r : scopeDirty.union(r);
            return true;
        }
        boolean hide = hiddenNow(name);
        if (r == null) return !hide;
        BufferedImage buf = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gb = buf.createGraphics();
        gb.translate(-r.x, -r.y);
        p.paint(gb, false);
        gb.dispose();
        building.add(new Entry(group, name, buf, AffineTransform.getTranslateInstance(r.x, r.y), comp, hide));
        return !hide;
    }

    private static Rectangle clampToScreen(Rectangle2D b) {
        int x0 = (int) Math.max(0, Math.floor(b.getMinX() - 1)), y0 = (int) Math.max(0, Math.floor(b.getMinY() - 1));
        int x1 = (int) Math.min(320, Math.ceil(b.getMaxX() + 1)), y1 = (int) Math.min(480, Math.ceil(b.getMaxY() + 1));
        if (x1 <= x0 || y1 <= y0) return null;
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    // ---- reading and switching (any thread) --------------------------------------------------------------------

    public static List<Entry> frame() { return last; }

    public static boolean isNameHidden(String name) { return hiddenNames.contains(name); }

    public static boolean isGroupHidden(String name) { return hiddenGroups.contains(name); }

    public static void setNameHidden(String name, boolean hide) {
        if (hide) hiddenNames.add(name); else hiddenNames.remove(name);
    }

    public static void setGroupHidden(String name, boolean hide) {
        if (hide) hiddenGroups.add(name); else hiddenGroups.remove(name);
    }

    public static void showAll() {
        hiddenNames.clear();
        hiddenGroups.clear();
    }
}
