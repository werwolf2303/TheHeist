package heist.web;

import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.canvas.TextMetrics;

import heist.engine.Fnt;
import heist.engine.Gfx;
import heist.engine.Image;

/**
 * Canvas2D drawing context. {@link #create}/{@link #dispose} map to save()/restore(): works because the engine
 * always nests them in strict LIFO order — Canvas2D has one shared context per canvas, unlike Graphics2D.
 */
final class WebGfx extends Gfx {
    private final CanvasRenderingContext2D ctx;
    private int color = 0xFF000000;
    private float alpha = 1f;
    private int blend = NORMAL;
    private WebFnt font = new WebFnt("sans-serif", true, 17f);

    WebGfx(CanvasRenderingContext2D ctx) {
        this.ctx = ctx;
    }

    public Gfx create() {
        ctx.save();
        WebGfx child = new WebGfx(ctx);
        child.color = color;
        child.alpha = alpha;
        child.blend = blend;
        child.font = font;
        return child;
    }

    public void dispose() { ctx.restore(); }

    public void translate(float x, float y) { ctx.translate(x, y); }

    public void scale(float x, float y) { ctx.scale(x, y); }

    public void rotate(float radians) { ctx.rotate(radians); }

    public void rotate(float radians, float cx, float cy) {
        ctx.translate(cx, cy);
        ctx.rotate(radians);
        ctx.translate(-cx, -cy);
    }

    public void clipRect(float x, float y, float w, float h) {
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        ctx.clip();
    }

    /** The game's angles are clockwise from the right, matching Canvas2D's arc() directly (no sign flip needed,
     *  unlike AWT's counter-clockwise convention - see AwtGfx.clipSector for that comparison). */
    public void clipSector(float cx, float cy, float radius, float startDegrees, float sweepDegrees) {
        double start = Math.toRadians(startDegrees), end = Math.toRadians(startDegrees + sweepDegrees);
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, radius, start, end, sweepDegrees < 0);
        ctx.closePath();
        ctx.clip();
    }

    public void setColor(int argb) { color = argb; }

    public void setAlpha(float a) { alpha = Math.max(0f, Math.min(1f, a)); }

    public float getAlpha() { return alpha; }

    public void setBlend(int mode) { blend = mode; }

    public void setFont(Fnt f) { font = (WebFnt) f; }

    /** "rgba(r, g, b, a)" for a 32 bit ARGB colour, Canvas2D's fillStyle/strokeStyle format. */
    static String cssColor(int argb) {
        int a = (argb >>> 24) & 0xff, r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        return "rgba(" + r + "," + g + "," + b + "," + (a / 255f) + ")";
    }

    /** Canvas2D's globalCompositeOperation name for one of {@link Gfx}'s blend modes. */
    private static String compositeOp(int mode) {
        switch (mode) {
            case ADD: return "lighter";
            case DST_IN: return "destination-in";
            case DST_OUT: return "destination-out";
            case SRC: return "copy";
            default: return "source-over";
        }
    }

    private void prepare(float extraAlpha) {
        ctx.setGlobalCompositeOperation(compositeOp(blend));
        ctx.setGlobalAlpha(Math.max(0f, Math.min(1f, extraAlpha)));
    }

    private void prepareFill() {
        prepare(alpha);
        ctx.setFillStyle(cssColor(color));
    }

    public void fillRect(float x, float y, float w, float h) {
        prepareFill();
        ctx.fillRect(x, y, w, h);
    }

    public void fillRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        prepareFill();
        pathRoundRect(x, y, w, h, arcW, arcH);
        ctx.fill();
    }

    public void strokeRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        prepare(alpha);
        ctx.setStrokeStyle(cssColor(color));
        ctx.setLineWidth(1);
        pathRoundRect(x, y, w, h, arcW, arcH);
        ctx.stroke();
    }

    private void pathRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        float rx = Math.min(arcW / 2f, w / 2f), ry = Math.min(arcH / 2f, h / 2f);
        ctx.beginPath();
        ctx.moveTo(x + rx, y);
        ctx.lineTo(x + w - rx, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + ry);
        ctx.lineTo(x + w, y + h - ry);
        ctx.quadraticCurveTo(x + w, y + h, x + w - rx, y + h);
        ctx.lineTo(x + rx, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - ry);
        ctx.lineTo(x, y + ry);
        ctx.quadraticCurveTo(x, y, x + rx, y);
        ctx.closePath();
    }

    public void drawImage(Image img, float x, float y, float w, float h) {
        prepare(alpha);
        ctx.drawImage(((WebImage) img).canvas, x, y, w, h);
    }

    /** Per-channel multiply tint (particles). Canvas2D composite ops can't express a per-channel multiply, so reads/writes pixels. */
    public void drawImageTinted(Image img, float x, float y, float w, float h, float r, float g, float b, float a) {
        WebImage src = (WebImage) img;
        int ow = Math.max(1, Math.round(w)), oh = Math.max(1, Math.round(h));
        int tw = src.getWidth(), th = src.getHeight();
        WebImage scratch = scratch(ow, oh);
        scratch.ctx.setGlobalCompositeOperation("copy");
        scratch.ctx.drawImage(src.canvas, 0, 0, tw, th, 0, 0, ow, oh);

        int[] px = new int[ow * oh];
        scratch.getPixels(px, 0, ow, 0, 0, ow, oh);
        float af = a * alpha;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int ta = (p >>> 24) & 0xff;
            int oa = Math.round((ta / 255f) * af * 255f);
            int or = Math.round(((p >> 16) & 0xff) * r);
            int og = Math.round(((p >> 8) & 0xff) * g);
            int ob = Math.round((p & 0xff) * b);
            px[i] = (oa << 24) | (or << 16) | (og << 8) | ob;
        }
        scratch.setPixels(px, 0, ow, 0, 0, ow, oh);

        ctx.setGlobalCompositeOperation(compositeOp(blend));
        ctx.setGlobalAlpha(1f);
        ctx.drawImage(scratch.canvas, 0, 0, ow, oh, Math.round(x), Math.round(y), ow, oh);
    }

    /** One shared scratch canvas reused across every tinted draw (there is only one game thread/event loop). */
    private static WebImage tintScratch;

    private static WebImage scratch(int w, int h) {
        if (tintScratch == null || tintScratch.getWidth() < w || tintScratch.getHeight() < h) {
            tintScratch = new WebImage(Math.max(w, 64), Math.max(h, 64));
        } else {
            tintScratch.ctx.clearRect(0, 0, tintScratch.getWidth(), tintScratch.getHeight());
        }
        return tintScratch;
    }

    public void drawString(String s, float x, float y) {
        prepareFill();
        ctx.setFont(font.cssFont());
        ctx.setTextBaseline("alphabetic");
        ctx.fillText(s, x, y);
    }

    public float stringWidth(String s) {
        ctx.setFont(font.cssFont());
        return (float) ctx.measureText(s).getWidth();
    }

    private TextMetrics metrics() {
        ctx.setFont(font.cssFont());
        return ctx.measureText("Ag");
    }

    public float fontAscent() { return (float) metrics().getFontBoundingBoxAscent(); }

    public float fontDescent() { return (float) metrics().getFontBoundingBoxDescent(); }

    public float fontHeight() {
        TextMetrics m = metrics();
        return (float) (m.getFontBoundingBoxAscent() + m.getFontBoundingBoxDescent());
    }
}
