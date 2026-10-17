package heist.desktop;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.RescaleOp;
import java.awt.image.WritableRaster;

import heist.engine.Fnt;
import heist.engine.Gfx;
import heist.engine.Image;

/** The game's drawing context over a Graphics2D. */
public final class AwtGfx extends Gfx {
    private final Graphics2D g;
    private int color = 0xFF000000;
    private float alpha = 1f;
    private int blend = NORMAL;
    private AwtFnt font = new AwtFnt(new Font(Font.SANS_SERIF, Font.BOLD, 17));

    /** root=true: this context owns the surface, so rendering hints are applied here. */
    public AwtGfx(Graphics2D g, boolean root) {
        this.g = g;
        if (root) AwtImage.applyHints(g);
    }

    private AwtGfx(AwtGfx parent) {
        this.g = (Graphics2D) parent.g.create();
        this.color = parent.color;
        this.alpha = parent.alpha;
        this.blend = parent.blend;
        this.font = parent.font;
    }

    /** Underlying Graphics2D, for tools that need direct access. */
    public Graphics2D g2() { return g; }

    /** Returns the Java2D composite for a blend mode and alpha. */
    public static Composite composite(int blend, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        switch (blend) {
            case ADD: return ADD_COMPOSITE;
            case DST_IN: return AlphaComposite.getInstance(AlphaComposite.DST_IN, a);
            case DST_OUT: return AlphaComposite.getInstance(AlphaComposite.DST_OUT, a);
            case SRC: return AlphaComposite.getInstance(AlphaComposite.SRC, a);
            default: return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a);
        }
    }

    public Gfx create() { return new AwtGfx(this); }

    public void dispose() { g.dispose(); }

    // ---- transform and clip ------------------------------------------------------------------------------------

    public void translate(float x, float y) { g.translate((double) x, (double) y); }

    public void scale(float x, float y) { g.scale(x, y); }

    public void rotate(float radians) { g.rotate(radians); }

    public void rotate(float radians, float cx, float cy) { g.rotate(radians, cx, cy); }

    public void clipRect(float x, float y, float w, float h) { g.clip(new Rectangle2D.Float(x, y, w, h)); }

    public void clipSector(float cx, float cy, float radius, float startDegrees, float sweepDegrees) {
        // AWT angles run counter-clockwise, the game's clockwise
        g.clip(new Arc2D.Float(cx - radius, cy - radius, radius * 2, radius * 2, -startDegrees, -sweepDegrees, Arc2D.PIE));
    }

    // ---- state -------------------------------------------------------------------------------------------------

    public void setColor(int argb) { color = argb; }

    public void setAlpha(float a) { alpha = Math.max(0f, Math.min(1f, a)); }

    public float getAlpha() { return alpha; }

    public void setBlend(int mode) { blend = mode; }

    public void setFont(Fnt f) { font = (AwtFnt) f; }

    private void prepare(float extraAlpha) { g.setComposite(composite(blend, extraAlpha)); }

    private void prepareFill() {
        prepare(alpha);
        g.setColor(new Color(color, true));
    }

    // ---- shapes ------------------------------------------------------------------------------------------------

    public void fillRect(float x, float y, float w, float h) {
        prepareFill();
        g.fill(new Rectangle2D.Float(x, y, w, h));
    }

    public void fillRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        prepareFill();
        g.fill(new RoundRectangle2D.Float(x, y, w, h, arcW, arcH));
    }

    public void strokeRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        prepareFill();
        java.awt.Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, arcW, arcH));
        g.setStroke(old);
    }

    // ---- images ------------------------------------------------------------------------------------------------

    public void drawImage(Image img, float x, float y, float w, float h) {
        BufferedImage bi = ((AwtImage) img).img;
        prepare(alpha);
        g.drawImage(bi, new AffineTransform(w / bi.getWidth(), 0, 0, h / bi.getHeight(), x, y), null);
    }

    // scales to the on-screen size before tinting; RescaleOp over the full source texture was dominant cost for particles
    public void drawImageTinted(Image img, float x, float y, float w, float h, float r, float gr, float b, float a) {
        BufferedImage bi = ((AwtImage) img).img;
        int ow = Math.max(1, Math.round(w)), oh = Math.max(1, Math.round(h));
        int tw = bi.getWidth(), th = bi.getHeight();
        BufferedImage tinted = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        float af = a * alpha;
        for (int oy = 0; oy < oh; oy++) {
            int ty = Math.min(th - 1, oy * th / oh);
            for (int ox = 0; ox < ow; ox++) {
                int tx = Math.min(tw - 1, ox * tw / ow);
                int argb = bi.getRGB(tx, ty);
                float ta = ((argb >>> 24) & 0xff) / 255f;
                int oaV = Math.round(ta * af * 255f);
                int orV = Math.round(((argb >> 16) & 0xff) * r);
                int ogV = Math.round(((argb >> 8) & 0xff) * gr);
                int obV = Math.round((argb & 0xff) * b);
                tinted.setRGB(ox, oy, (oaV << 24) | (orV << 16) | (ogV << 8) | obV);
            }
        }
        Composite old = g.getComposite();
        prepare(1f);
        g.drawImage(tinted, Math.round(x), Math.round(y), ow, oh, null);
        g.setComposite(old);
    }

    // ---- text --------------------------------------------------------------------------------------------------

    private FontRenderContext frc() { return g.getFontRenderContext(); }

    public void drawString(String s, float x, float y) {
        prepareFill();
        g.setFont(font.font);
        g.drawString(s, x, y);
    }

    public float stringWidth(String s) { return (float) font.font.getStringBounds(s, frc()).getWidth(); }

    private LineMetrics metrics() { return font.font.getLineMetrics("Ag", frc()); }

    public float fontAscent() { return metrics().getAscent(); }

    public float fontDescent() { return metrics().getDescent(); }

    public float fontHeight() {
        LineMetrics m = metrics();
        return m.getAscent() + m.getDescent() + m.getLeading();
    }

    // ---- additive blending -------------------------------------------------------------------------------------

    private static final Composite ADD_COMPOSITE = new Composite() {
        public CompositeContext createContext(ColorModel srcCm, ColorModel dstCm, RenderingHints hints) {
            return new CompositeContext() {
                public void dispose() {}

                public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
                    int w = Math.min(src.getWidth(), dstIn.getWidth());
                    int h = Math.min(src.getHeight(), dstIn.getHeight());
                    int[] sp = new int[4], dp = new int[4];
                    int sb = src.getNumBands(), db = dstIn.getNumBands();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            src.getPixel(x, y, sp);
                            dstIn.getPixel(x, y, dp);
                            // the source is non-premultiplied ARGB: scale its colour by its alpha
                            int sa = sb > 3 ? sp[3] : 255;
                            for (int c = 0; c < 3; c++) dp[c] = Math.min(255, dp[c] + sp[c] * sa / 255);
                            if (db > 3) dp[3] = Math.max(dp[3], sa);
                            dstOut.setPixel(x, y, dp);
                        }
                    }
                }
            };
        }
    };
}
