package heist.android;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import heist.engine.Fnt;
import heist.engine.Gfx;
import heist.engine.Image;

/** Game drawing context over Canvas. Child contexts share the canvas and restore transform/clip on dispose. */
final class AndroidGfx extends Gfx {
    // PorterDuff.Mode.ADD was added in API 11; fall back to null (normal blend) on older devices
    private static final PorterDuffXfermode X_ADD = android.os.Build.VERSION.SDK_INT >= 11
            ? new PorterDuffXfermode(PorterDuff.Mode.ADD) : null;
    private static final PorterDuffXfermode X_DST_IN = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private static final PorterDuffXfermode X_DST_OUT = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
    private static final PorterDuffXfermode X_SRC = new PorterDuffXfermode(PorterDuff.Mode.SRC);

    private final Canvas canvas;
    private final int saveCount;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private int color = 0xFF000000;
    private float alpha = 1f;
    private int blend = NORMAL;
    private AndroidFnt font = new AndroidFnt(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD), 17f);

    AndroidGfx(Canvas canvas) {
        this.canvas = canvas;
        this.saveCount = -1;
    }

    private AndroidGfx(AndroidGfx parent) {
        this.canvas = parent.canvas;
        this.saveCount = canvas.save();
        this.color = parent.color;
        this.alpha = parent.alpha;
        this.blend = parent.blend;
        this.font = parent.font;
    }

    public Gfx create() { return new AndroidGfx(this); }

    public void dispose() {
        if (saveCount >= 0) canvas.restoreToCount(saveCount);
    }

    // ---- transform and clip ------------------------------------------------------------------------------------

    public void translate(float x, float y) { canvas.translate(x, y); }

    public void scale(float x, float y) { canvas.scale(x, y); }

    public void rotate(float radians) { canvas.rotate((float) Math.toDegrees(radians)); }

    public void rotate(float radians, float cx, float cy) { canvas.rotate((float) Math.toDegrees(radians), cx, cy); }

    public void clipRect(float x, float y, float w, float h) { canvas.clipRect(x, y, x + w, y + h); }

    public void clipSector(float cx, float cy, float radius, float startDegrees, float sweepDegrees) {
        Path p = new Path();
        p.moveTo(cx, cy);
        p.arcTo(new RectF(cx - radius, cy - radius, cx + radius, cy + radius), startDegrees, sweepDegrees);
        p.close();
        canvas.clipPath(p);
    }

    // ---- state -------------------------------------------------------------------------------------------------

    public void setColor(int argb) { color = argb; }

    public void setAlpha(float a) { alpha = Math.max(0f, Math.min(1f, a)); }

    public float getAlpha() { return alpha; }

    public void setBlend(int mode) { blend = mode; }

    public void setFont(Fnt f) { font = (AndroidFnt) f; }

    private void applyBlend() {
        switch (blend) {
            case ADD: paint.setXfermode(X_ADD); break;
            case DST_IN: paint.setXfermode(X_DST_IN); break;
            case DST_OUT: paint.setXfermode(X_DST_OUT); break;
            case SRC: paint.setXfermode(X_SRC); break;
            default: paint.setXfermode(null);
        }
    }

    private void prepareFill() {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(Math.round(((color >>> 24) & 0xff) * alpha));
        applyBlend();
    }

    // ---- shapes ------------------------------------------------------------------------------------------------

    public void fillRect(float x, float y, float w, float h) {
        prepareFill();
        canvas.drawRect(x, y, x + w, y + h, paint);
    }

    public void fillRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        prepareFill();
        dstRect.set(x, y, x + w, y + h);
        canvas.drawRoundRect(dstRect, arcW / 2f, arcH / 2f, paint);
    }

    public void strokeRoundRect(float x, float y, float w, float h, float arcW, float arcH) {
        prepareFill();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        dstRect.set(x, y, x + w, y + h);
        canvas.drawRoundRect(dstRect, arcW / 2f, arcH / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- images ------------------------------------------------------------------------------------------------

    public void drawImage(Image img, float x, float y, float w, float h) {
        android.graphics.Bitmap b = ((AndroidImage) img).bitmap;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(Math.round(255 * alpha));
        applyBlend();
        srcRect.set(0, 0, b.getWidth(), b.getHeight());
        dstRect.set(x, y, x + w, y + h);
        canvas.drawBitmap(b, srcRect, dstRect, paint);
    }

    public void drawImageTinted(Image img, float x, float y, float w, float h, float r, float g, float bl, float a) {
        android.graphics.Bitmap b = ((AndroidImage) img).bitmap;
        ColorMatrix cm = new ColorMatrix(new float[] {
                r, 0, 0, 0, 0,
                0, g, 0, 0, 0,
                0, 0, bl, 0, 0,
                0, 0, 0, a * alpha, 0 });
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(255);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        applyBlend();
        srcRect.set(0, 0, b.getWidth(), b.getHeight());
        dstRect.set(x, y, x + w, y + h);
        canvas.drawBitmap(b, srcRect, dstRect, paint);
        paint.setColorFilter(null);
    }

    // ---- text --------------------------------------------------------------------------------------------------

    private void prepareText() {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(font.face);
        paint.setTextSize(font.size);
        paint.setColor(color);
        paint.setAlpha(Math.round(((color >>> 24) & 0xff) * alpha));
        applyBlend();
    }

    public void drawString(String s, float x, float y) {
        prepareText();
        canvas.drawText(s, x, y, paint);
    }

    public float stringWidth(String s) {
        prepareText();
        return paint.measureText(s);
    }

    public float fontAscent() {
        prepareText();
        return -paint.getFontMetrics().ascent;
    }

    public float fontDescent() {
        prepareText();
        return paint.getFontMetrics().descent;
    }

    public float fontHeight() {
        prepareText();
        Paint.FontMetrics m = paint.getFontMetrics();
        return -m.ascent + m.descent + m.leading;
    }
}
