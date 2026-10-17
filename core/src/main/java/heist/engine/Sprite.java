package heist.engine;

/** A drawable image with a logical (point) size, so @2x art renders at its intended size. */
public final class Sprite {
    public final Image img;
    public final float w, h;

    public Sprite(Image img, float scale) {
        this.img = img;
        this.w = img.getWidth() * scale;
        this.h = img.getHeight() * scale;
    }

    public void draw(Gfx g, float x, float y) {
        draw(g, x, y, w, h);
    }

    public void draw(Gfx g, float x, float y, float dw, float dh) {
        g.drawImage(img, Math.round(x), Math.round(y), Math.round(dw), Math.round(dh));
    }

    public void drawCentered(Gfx g, float cx, float cy) {
        draw(g, cx - w / 2, cy - h / 2);
    }

    public void draw(Gfx g, float x, float y, float dw, float dh, float alpha) {
        if (alpha >= 0.999f) { draw(g, x, y, dw, dh); return; }
        if (alpha <= 0f) return;
        float old = g.getAlpha();
        g.setAlpha(alpha);
        draw(g, x, y, dw, dh);
        g.setAlpha(old);
    }

    public void drawAlpha(Gfx g, float x, float y, float alpha) {
        draw(g, x, y, w, h, alpha);
    }
}
