package heist.desktop;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.ImageIO;

import heist.engine.Gfx;
import heist.engine.Image;

/** An {@link Image} backed by a BufferedImage (always 32 bit ARGB). */
public final class AwtImage implements Image {
    public final BufferedImage img;
    /** The game file this picture came from, if it was loaded from one (shown by the layer window). */
    public volatile String name;

    AwtImage(BufferedImage img) { this.img = img; }

    static AwtImage create(int w, int h) {
        return new AwtImage(new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB));
    }

    /** Wraps a decoded picture, converting it to ARGB if it came in another layout. */
    static AwtImage of(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return new AwtImage(src);
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return new AwtImage(out);
    }

    public int getWidth() { return img.getWidth(); }

    public int getHeight() { return img.getHeight(); }

    public void getPixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        img.getRGB(x, y, w, h, argb, offset, stride);
    }

    public void setPixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        img.setRGB(x, y, w, h, argb, offset, stride);
    }

    public void clear(int argb) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new java.awt.Color(argb, true));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
    }

    public Gfx createGraphics() { return new AwtGfx(img.createGraphics(), true); }

    public void writePng(OutputStream out) throws IOException {
        ImageIO.write(img, "png", out);
    }

    /** The drawing quality the game was made for: smooth scaling, smooth edges, smooth text. */
    static void applyHints(java.awt.Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
