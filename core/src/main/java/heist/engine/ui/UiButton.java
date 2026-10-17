package heist.engine.ui;

import heist.engine.Image;

import heist.engine.Assets;
import heist.engine.Fnt;
import heist.engine.Gfx;
import heist.engine.Sprite;

/** UIButton: a normal and a highlighted image, optional title, and an action selector name. */
public class UiButton extends UiView {
    public String normalImage, highlightedImage, disabledImage, selectedImage;
    public String title;
    public int titleColor = 0xFFFFFFFF;
    public Fnt font;
    public String action;       // e.g. "backButtonPressed:"
    public boolean pressed, enabled = true, selected;

    protected void drawContent(Gfx g) {
        String img = normalImage;
        if (!enabled && disabledImage != null) img = disabledImage;
        else if (pressed && highlightedImage != null) img = highlightedImage;
        else if (selected && selectedImage != null) img = selectedImage;
        if (img != null) {
            Sprite s = Assets.image(img);
            float x = (w - s.w) / 2f, y = (h - s.h) / 2f;
            if (img != normalImage && normalImage != null) {
                // an oversized glow picture (the back and reset buttons): line its symbol up with the normal picture's
                Sprite n = Assets.image(normalImage);
                if (Math.abs(n.w - s.w) > 1f || Math.abs(n.h - s.h) > 1f) {
                    float[] o = align(n, s);
                    // the normal picture snaps to whole points when drawn, so the glow picture is placed from that same spot
                    x = Math.round((w - n.w) / 2f) + o[0];
                    y = Math.round((h - n.h) / 2f) + o[1];
                }
            }
            Gfx gs = g.create();
            gs.translate(x, y);                       // fractional offsets stay exact
            s.draw(gs, 0, 0);
            gs.dispose();
        }
        if (title != null && !title.isEmpty()) {
            if (font != null) g.setFont(font);
            g.setColor(titleColor);
            g.drawString(title, (w - g.stringWidth(title)) / 2f, (h - g.fontHeight()) / 2f + g.fontAscent());
        }
    }

    private static final java.util.Map<String, float[]> ALIGN = new java.util.HashMap<String, float[]>();

    /** The bright symbol of a button picture: true where a pixel is opaque and almost white. */
    private static boolean[] core(Image im) {
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] m = new boolean[w * h];
        for (int i = 0; i < m.length; i++) {
            int p = px[i];
            m[i] = (p >>> 24) > 200 && Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 225;
        }
        return m;
    }

    /**
     * Where the glow picture {@code s} has to sit, relative to the normal picture {@code n}, so that the two symbols lie
     * exactly on top of each other: the offset (in points) with the best overlap of the bright symbols.
     */
    private static float[] align(Sprite n, Sprite s) {
        String key = System.identityHashCode(n) + ":" + System.identityHashCode(s);
        float[] r = ALIGN.get(key);
        if (r != null) return r;
        Image ni = n.img, si = s.img;
        boolean[] mn = core(ni), ms = core(si);
        int nw = ni.getWidth(), nh = ni.getHeight(), sw = si.getWidth(), sh = si.getHeight();
        int[] px = new int[mn.length], py = new int[mn.length];
        int count = 0;
        for (int i = 0; i < mn.length; i++) if (mn[i]) { px[count] = i % nw; py[count] = i / nw; count++; }
        int bestScore = -1, bestX = 0, bestY = 0;
        // the pressed picture sits at (ox, oy) in the normal picture's pixels
        for (int oy = -sh + 1; oy < nh; oy++) {
            for (int ox = -sw + 1; ox < nw; ox++) {
                int score = 0;
                for (int i = 0; i < count; i++) {
                    int x = px[i] - ox, y = py[i] - oy;
                    if (x >= 0 && y >= 0 && x < sw && y < sh && ms[y * sw + x]) score++;
                }
                if (score > bestScore) { bestScore = score; bestX = ox; bestY = oy; }
            }
        }
        if (count == 0 || bestScore <= 0) { bestX = (nw - sw) / 2; bestY = (nh - sh) / 2; }
        r = new float[] { bestX * (n.w / nw), bestY * (n.h / nh) };
        ALIGN.put(key, r);
        return r;
    }
}
