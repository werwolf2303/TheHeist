package heist.desktop.debug;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

import heist.desktop.AwtGfx;
import heist.desktop.AwtImage;
import heist.engine.Fnt;
import heist.engine.Gfx;
import heist.engine.Image;

/** {@link Gfx} wrapper that forwards draw calls to a real context and records them in {@link LayerTrace}. */
public final class TraceGfx extends Gfx {
    private final AwtGfx real;
    private final AffineTransform cur;  // user space → view pixels
    private Area clip;                  // in view pixels; null = no clip
    private int color = 0xFF000000;
    private float alpha = 1f;
    private int blend = NORMAL;
    private Fnt font;

    /** The root context of a frame, over the real context of the view. */
    public TraceGfx(AwtGfx real) {
        this.real = real;
        this.cur = new AffineTransform();
    }

    private TraceGfx(TraceGfx parent) {
        this.real = (AwtGfx) parent.real.create();
        this.cur = new AffineTransform(parent.cur);
        this.clip = parent.clip == null ? null : new Area(parent.clip);
        this.color = parent.color;
        this.alpha = parent.alpha;
        this.blend = parent.blend;
        this.font = parent.font;
    }

    // ---- contexts --------------------------------------------------------------------------------------------

    public Gfx create() { return new TraceGfx(this); }

    public void dispose() { real.dispose(); }

    public void group(String name) {
        LayerTrace.group(name);
        real.group(name);
    }

    public boolean beginLayer(String name) { return LayerTrace.beginScope(name); }

    public void endLayer() { LayerTrace.endScope(); }

    // ---- transform and clip ----------------------------------------------------------------------------------

    public void translate(float x, float y) { cur.translate(x, y); real.translate(x, y); }

    public void scale(float x, float y) { cur.scale(x, y); real.scale(x, y); }

    public void rotate(float radians) { cur.rotate(radians); real.rotate(radians); }

    public void rotate(float radians, float cx, float cy) { cur.rotate(radians, cx, cy); real.rotate(radians, cx, cy); }

    public void clipRect(float x, float y, float w, float h) {
        clipTo(new Rectangle2D.Float(x, y, w, h));
        real.clipRect(x, y, w, h);
    }

    public void clipSector(float cx, float cy, float radius, float startDegrees, float sweepDegrees) {
        clipTo(new Arc2D.Float(cx - radius, cy - radius, radius * 2, radius * 2, -startDegrees, -sweepDegrees, Arc2D.PIE));
        real.clipSector(cx, cy, radius, startDegrees, sweepDegrees);
    }

    private void clipTo(Shape userShape) {
        Area a = new Area(cur.createTransformedShape(userShape));
        if (clip == null) clip = a; else clip.intersect(a);
    }

    // ---- state -----------------------------------------------------------------------------------------------

    public void setColor(int argb) { color = argb; real.setColor(argb); }

    public void setAlpha(float a) { alpha = Math.max(0f, Math.min(1f, a)); real.setAlpha(a); }

    public float getAlpha() { return alpha; }

    public void setBlend(int mode) { blend = mode; real.setBlend(mode); }

    public void setFont(Fnt f) { font = f; real.setFont(f); }

    // ---- recording helpers -----------------------------------------------------------------------------------

    private AffineTransform rel() {
        AffineTransform t = new AffineTransform(LayerTrace.baseInverse());
        t.concatenate(cur);
        return t;
    }

    private boolean raster(String name, Rectangle2D boundsUser, final Op op) {
        final AffineTransform rel = rel();
        Rectangle2D b = rel.createTransformedShape(boundsUser).getBounds2D();
        if (clip != null) b = b.createIntersection(LayerTrace.baseInverse().createTransformedShape(clip).getBounds2D());
        final Area logicalClip = clip == null ? null : new Area(LayerTrace.baseInverse().createTransformedShape(clip));
        final int c = color, bl = blend;
        final float a = alpha;
        final Fnt f = font;
        return LayerTrace.rasterOp(name, b, AwtGfx.composite(blend, alpha), new LayerTrace.RasterPainter() {
            public void paint(Graphics2D target, boolean scope) {
                if (logicalClip != null) target.clip(logicalClip);
                target.transform(rel);
                AwtGfx g = new AwtGfx(target, true);
                g.setColor(c);
                if (f != null) g.setFont(f);
                if (scope) { g.setBlend(bl); g.setAlpha(a); }
                op.paint(g);
            }
        });
    }

    private interface Op { void paint(Gfx g); }

    // ---- shapes ----------------------------------------------------------------------------------------------

    public void fillRect(final float x, final float y, final float w, final float h) {
        if (raster("fill #" + hex(color), new Rectangle2D.Float(x, y, w, h), new Op() {
            public void paint(Gfx g) { g.fillRect(x, y, w, h); }
        })) real.fillRect(x, y, w, h);
    }

    public void fillRoundRect(final float x, final float y, final float w, final float h, final float aw, final float ah) {
        if (raster("fill #" + hex(color), new Rectangle2D.Float(x, y, w, h), new Op() {
            public void paint(Gfx g) { g.fillRoundRect(x, y, w, h, aw, ah); }
        })) real.fillRoundRect(x, y, w, h, aw, ah);
    }

    public void strokeRoundRect(final float x, final float y, final float w, final float h, final float aw, final float ah) {
        if (raster("outline #" + hex(color), new Rectangle2D.Float(x - 1, y - 1, w + 2, h + 2), new Op() {
            public void paint(Gfx g) { g.strokeRoundRect(x, y, w, h, aw, ah); }
        })) real.strokeRoundRect(x, y, w, h, aw, ah);
    }

    private static String hex(int argb) { return String.format("%06x", argb & 0xFFFFFF); }

    // ---- images ----------------------------------------------------------------------------------------------

    public void drawImage(Image img, float x, float y, float w, float h) {
        AwtImage ai = (AwtImage) img;
        AffineTransform tx = rel();
        tx.translate(x, y);
        tx.scale(w / ai.getWidth(), h / ai.getHeight());
        String name = ai.name != null ? ai.name : "image " + ai.getWidth() + "x" + ai.getHeight();
        if (LayerTrace.imageOp(name, ai.img, tx, AwtGfx.composite(blend, alpha))) real.drawImage(img, x, y, w, h);
    }

    public void drawImageTinted(final Image img, final float x, final float y, final float w, final float h,
                                final float r, final float g, final float b, final float a) {
        if (raster("tinted image", new Rectangle2D.Float(x, y, w, h), new Op() {
            public void paint(Gfx gg) { gg.drawImageTinted(img, x, y, w, h, r, g, b, a); }
        })) real.drawImageTinted(img, x, y, w, h, r, g, b, a);
    }

    // ---- text ------------------------------------------------------------------------------------------------

    public void drawString(final String s, final float x, final float y) {
        Rectangle2D bounds = new Rectangle2D.Float(x, y - real.fontAscent(), real.stringWidth(s) + 1, real.fontAscent() + real.fontDescent());
        if (raster("text \"" + (s.length() > 24 ? s.substring(0, 24) + "..." : s) + "\"", bounds, new Op() {
            public void paint(Gfx g) { g.drawString(s, x, y); }
        })) real.drawString(s, x, y);
    }

    public float stringWidth(String s) { return real.stringWidth(s); }

    public float fontAscent() { return real.fontAscent(); }

    public float fontDescent() { return real.fontDescent(); }

    public float fontHeight() { return real.fontHeight(); }
}
