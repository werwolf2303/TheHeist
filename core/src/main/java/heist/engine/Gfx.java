package heist.engine;

/**
 * The game's drawing context. Child contexts ({@link #create}) restore the transform and clip on {@link #dispose};
 * alpha, blend mode and font are inherited by children. Colours are 32-bit ARGB.
 */
public abstract class Gfx {
    public static final int NORMAL = 0;  // src over dst
    public static final int ADD = 1;     // src + dst (glowing particles)
    public static final int DST_IN = 2;  // keep dst where src is opaque (mask)
    public static final int DST_OUT = 3; // punch hole in dst
    public static final int SRC = 4;     // src replaces dst

    /** A drawing context for a picture. */
    public static Gfx of(Image image) { return image.createGraphics(); }

    /** Names the group the following drawing belongs to (for layer inspection tools). */
    public void group(String name) {}

    /** Groups draws into a named layer. Returns false when the layer is off; endLayer must still be called. */
    public boolean beginLayer(String name) { return true; }

    public void endLayer() {}

    public abstract Gfx create();

    /** Restores transform and clip to what they were at {@link #create}. */
    public abstract void dispose();

    public abstract void translate(float x, float y);

    public abstract void scale(float x, float y);

    /** Rotation in radians, clockwise on screen. */
    public abstract void rotate(float radians);

    public abstract void rotate(float radians, float cx, float cy);

    public abstract void clipRect(float x, float y, float w, float h);

    /**
     * Clips to a pie slice: the wedge of the circle around (cx, cy) that starts at {@code startDegrees} (0 = to the
     * right, positive = clockwise on screen) and sweeps {@code sweepDegrees} clockwise.
     */
    public abstract void clipSector(float cx, float cy, float radius, float startDegrees, float sweepDegrees);

    public abstract void setColor(int argb);

    public void setColor(int r, int g, int b, int a) { setColor((a << 24) | (r << 16) | (g << 8) | b); }

    /** Multiplies everything drawn after it (0..1). */
    public abstract void setAlpha(float a);

    public abstract float getAlpha();

    /** One of {@link #NORMAL}, {@link #ADD}, {@link #DST_IN}, {@link #DST_OUT}, {@link #SRC}. */
    public abstract void setBlend(int mode);

    public abstract void setFont(Fnt f);

    public abstract void fillRect(float x, float y, float w, float h);

    /** arcW/arcH are the full corner ellipse diameters. */
    public abstract void fillRoundRect(float x, float y, float w, float h, float arcW, float arcH);

    /** A one unit wide outline. */
    public abstract void strokeRoundRect(float x, float y, float w, float h, float arcW, float arcH);

    /** Draws the whole image stretched into the rectangle. */
    public abstract void drawImage(Image img, float x, float y, float w, float h);

    /** Draws the whole image at its pixel size. */
    public void drawImage(Image img, float x, float y) {
        drawImage(img, x, y, img.getWidth(), img.getHeight());
    }

    /** Draws an image multiplied by a colour (each channel 0..1, alpha 0..1), for tinted particles. */
    public abstract void drawImageTinted(Image img, float x, float y, float w, float h, float r, float g, float b, float a);

    /** Draws text with the baseline at y. */
    public abstract void drawString(String s, float x, float y);

    public abstract float stringWidth(String s);

    public abstract float fontAscent();

    public abstract float fontDescent();

    /** Ascent + descent + leading. */
    public abstract float fontHeight();
}
