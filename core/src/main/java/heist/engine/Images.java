package heist.engine;

import heist.platform.Host;

/** Picture helpers built only on {@link Image} and {@link Gfx}, so they work the same on every platform. */
public final class Images {
    private Images() {}

    /** A copy of a rectangle of the picture. */
    public static Image crop(Image src, int x, int y, int w, int h) {
        Image out = Host.get().createImage(w, h);
        Gfx g = out.createGraphics();
        g.setBlend(Gfx.SRC);
        g.translate(-x, -y);
        g.drawImage(src, 0, 0);
        g.dispose();
        return out;
    }

    /** The picture scaled to a new size with a filtered (smooth) step. */
    public static Image scale(Image src, int w, int h) {
        Image out = Host.get().createImage(w, h);
        Gfx g = out.createGraphics();
        g.setBlend(Gfx.SRC);
        g.drawImage(src, 0, 0, w, h);
        g.dispose();
        return out;
    }
}
