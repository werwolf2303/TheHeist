package heist.game;

import heist.engine.Image;
import heist.platform.Host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import heist.engine.Fnt;
import heist.engine.Gfx;

import heist.engine.Assets;
import heist.engine.Sprite;

/** Horizontally scrolling level strip (LevelListController): thumbnails, difficulty pips, padlocks, badges. */
public final class LevelStrip {
    public static final int SLOT = 86, THUMB_Y = 19, VIEW_W = 320, VIEW_H = 104;

    private final PuzzleType type;
    private float scroll;                 // content offset x (>= 0)
    private final float[] glowT;          // difficulty glow animation per level (-1 = idle)
    private final float[] unlockT;        // unlock animation per level (-1 = idle)
    private final float[] unlockDelay;
    private final float[] badgeT;         // completed badge fade-in (-1 = done)
    private final boolean[] badgeShown;   // the badge has appeared; a freshly finished level has none until its end animation
    private final float[] rattleT;        // lock rattle animation
    private final boolean[] unlockedState;
    private final Image[] completedThumb;
    private final float[] iconAlpha;     // the thumbnail picture alone fades while a level opens or closes (lastLevelImage)
    private final Image[] shapedThumb;      // the solved picture cut to the unsolved icon's shape and size
    private int pressedLevel;
    private float scrollAnimFrom, scrollAnimTo, scrollAnimT = 1f;

    public LevelStrip(PuzzleType type) {
        this.type = type;
        int n = type.levels;
        glowT = new float[n + 1]; unlockT = new float[n + 1]; badgeT = new float[n + 1]; badgeShown = new boolean[n + 1]; rattleT = new float[n + 1];
        unlockDelay = new float[n + 1];
        unlockedState = new boolean[n + 1];
        completedThumb = new Image[n + 1];
        shapedThumb = new Image[n + 1];
        iconAlpha = new float[n + 1];
        java.util.Arrays.fill(iconAlpha, 1f);
        for (int i = 1; i <= n; i++) {
            glowT[i] = unlockT[i] = rattleT[i] = -1f;
            badgeT[i] = -1f;
            badgeShown[i] = Progress.isCompleted(type, i);
            unlockedState[i] = Progress.isUnlocked(type, i);
            loadCompletedThumb(i);
        }
        scroll = initialScroll();
    }

    public int contentWidth() { return type.levels * SLOT; }

    private float maxScroll() { return Math.max(0, contentWidth() - VIEW_W); }

    // ---- thumbnails -----------------------------------------------------------------------

    private static final String THUMB_PREFIX = "LevelCompleted-";

    /** Deletes the stored snapshots of finished puzzles. */
    static void clearThumbnails() {
        for (String name : Host.get().storage().listBlobNames(THUMB_PREFIX)) Host.get().storage().deleteBlob(name);
    }

    /** Sets the opacity of a level's thumbnail picture (the pips, lock and badge stay as they are). */
    public void setIconAlpha(int level, float a) { if (level >= 1 && level < iconAlpha.length) iconAlpha[level] = Math.max(0f, Math.min(1f, a)); }

    /** As in the original (UIImageUtil documentPath): LevelCompleted-G#L#.png. */
    private String completedBlobName(int level) { return THUMB_PREFIX + "G" + type.gameId + "L" + level + ".png"; }

    private void loadCompletedThumb(int level) {
        completedThumb[level] = null;
        shapedThumb[level] = null;
        if (!Progress.isCompleted(type, level)) return;
        try {
            byte[] data = Host.get().storage().readBlob(completedBlobName(level));
            if (data != null) completedThumb[level] = Host.get().decodeImage(data);
        } catch (IOException ignored) { }
    }

    /** Stores a 64px snapshot of the finished puzzle, like the original's LevelCompleted-G#L#.png. */
    public void saveCompletedThumbnail(int level, Image snapshot) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            snapshot.writePng(out);
            byte[] data = out.toByteArray();
            Host.get().storage().writeBlob(completedBlobName(level), data);
            Host.get().blobWritten(completedBlobName(level), data, snapshot);
        } catch (IOException ignored) { }
        completedThumb[level] = snapshot;
        shapedThumb[level] = null;
    }

    /**
     * The solved picture drawn into the same visible area as the unsolved icon (whose art leaves a margin and has soft
     * corners), so that every icon has the same size and shape.
     */
    private Image shaped(int level) {
        if (shapedThumb[level] != null) return shapedThumb[level];
        Sprite sp = thumbSprite(level);
        Image art = sp.img, snap = completedThumb[level];
        int w = Math.max(snap.getWidth(), Math.round(sp.w * 3)), h = Math.round(w * sp.h / sp.w);
        // visible bounds of the unsolved icon
        int ax0 = art.getWidth(), ay0 = art.getHeight(), ax1 = -1, ay1 = -1;
        int[] artPx = new int[art.getWidth() * art.getHeight()];
        art.getPixels(artPx, 0, art.getWidth(), 0, 0, art.getWidth(), art.getHeight());
        for (int y = 0; y < art.getHeight(); y++) {
            for (int x = 0; x < art.getWidth(); x++) {
                if ((artPx[y * art.getWidth() + x] >>> 24) > 8) {
                    ax0 = Math.min(ax0, x); ay0 = Math.min(ay0, y); ax1 = Math.max(ax1, x); ay1 = Math.max(ay1, y);
                }
            }
        }
        if (ax1 < 0) { ax0 = 0; ay0 = 0; ax1 = art.getWidth() - 1; ay1 = art.getHeight() - 1; }
        double kx = w / (double) art.getWidth(), ky = h / (double) art.getHeight();
        int bx = (int) Math.round(ax0 * kx), by = (int) Math.round(ay0 * ky);
        int bw = (int) Math.round((ax1 + 1 - ax0) * kx), bh = (int) Math.round((ay1 + 1 - ay0) * ky);
        Image out = Host.get().createImage(w, h);
        Gfx g = Gfx.of(out);
        g.drawImage(snap, bx, by, bw, bh);
        g.setBlend(Gfx.DST_IN);                          // keep only what the unsolved icon covers
        g.drawImage(art, 0, 0, w, h);
        g.dispose();
        shapedThumb[level] = out;
        return out;
    }

    private Sprite thumbSprite(int displayed) {
        // the thumbnail files are numbered by position in the list; each shows the puzzle the position opens
        String n = "LevelThumbnail" + type.name + displayed;
        boolean jpg = type.name.equals("Gridlock") || type.name.equals("Fifteen");
        if (jpg) return Assets.firstOf(n + ".jpg", n + ".png");
        return Assets.firstOf(n + ".png", n + ".jpg");
    }

    // ---- geometry ----------------------------------------------------------------------------

    /** Left edge of a level's thumbnail inside the scrolling content. */
    public float levelX(int level) { return slotX(level); }

    private float slotX(int level) { return (level - 1) * SLOT + (SLOT - 64) / 2f; }

    /** Thumbnail rectangle in strip content coordinates: x, y, w, h. */
    public float[] thumbRect(int level) {
        Sprite s = thumbSprite(level);
        float w = s.w, h = s.h;
        return new float[] { (level - 1) * SLOT + (SLOT - w) / 2f, THUMB_Y, w, h };
    }

    /** Strip content x -> view x. */
    public float contentToView(float cx) { return cx - scroll; }

    public float scroll() { return scroll; }

    // ---- scrolling / hit-testing ----------------------------------------------------------------

    private float dragStartX, dragStartScroll;
    private boolean dragging, moved;
    private float flingVelocity;   // px/s, positive = scrolling right
    private static final float FLING_FRICTION = 0.6f;   // velocity multiplier lost per second (exponential decay)
    // rolling velocity tracker: keeps the last N (x, t) samples for a stable fling estimate
    private static final int VEL_SAMPLES = 5;
    private final float[] velX = new float[VEL_SAMPLES];
    private final float[] velT = new float[VEL_SAMPLES];
    private int velHead;
    private float velElapsed;

    public void press(float vx, float vy) {
        dragStartX = vx; dragStartScroll = scroll; dragging = true; moved = false;
        flingVelocity = 0f;
        velHead = 0; velElapsed = 0f;
        for (int i = 0; i < VEL_SAMPLES; i++) { velX[i] = vx; velT[i] = 0f; }
        scrollAnimT = 1f;
        pressedLevel = hit(vx, vy);
    }

    public void drag(float vx) {
        if (!dragging) return;
        if (Math.abs(vx - dragStartX) > 6) moved = true;
        if (moved) {
            velX[velHead % VEL_SAMPLES] = vx;
            velT[velHead % VEL_SAMPLES] = velElapsed;
            velHead++;
            scroll = Math.max(0, Math.min(maxScroll(), dragStartScroll - (vx - dragStartX)));
        }
    }

    /** @return the tapped level (1-based) or 0 if this was a scroll / miss. */
    public int release(float vx, float vy) {
        dragging = false;
        int pressed = pressedLevel;
        pressedLevel = 0;
        if (moved) {
            // compute velocity from oldest to newest sample in the rolling window
            int oldest = velHead % VEL_SAMPLES;
            int newest = (velHead - 1 + VEL_SAMPLES) % VEL_SAMPLES;
            float dt = velT[newest] - velT[oldest];
            if (dt > 0.001f) flingVelocity = -(velX[newest] - velX[oldest]) / dt;
            else flingVelocity = 0f;
            return 0;
        }
        flingVelocity = 0f;
        int hit = hit(vx, vy);
        return hit != 0 && hit == pressed ? hit : 0;
    }

    private int hit(float vx, float vy) {
        for (int i = 1; i <= type.levels; i++) {
            float[] r = thumbRect(i);
            float x = contentToView(r[0]);
            if (vx >= x && vx < x + r[2] && vy >= r[1] && vy < r[1] + r[3]) return i;
        }
        return 0;
    }

    public boolean isLocked(int level) { return !Progress.isUnlocked(type, level) && !Progress.isCompleted(type, level); }

    public void setPressedVisual(int level) { pressedLevel = level; }

    /** Scrolls so the first not-yet-finished level is in view (original scrollToNextLockedLevel/initial). */
    private float initialScroll() {
        int target = 0;
        for (int i = 1; i <= type.levels; i++) {
            if (!Progress.isCompleted(type, i)) { target = i - 1; break; }
            target = i - 1;
        }
        float x = target * SLOT - (VIEW_W - SLOT) / 2f;
        return Math.max(0, Math.min(maxScroll(), x));
    }

    public void scrollTo(float x, boolean animated) {
        x = Math.max(0, Math.min(maxScroll(), x));
        if (!animated) { scroll = x; scrollAnimT = 1f; return; }
        scrollAnimFrom = scroll; scrollAnimTo = x; scrollAnimT = 0f;
    }

    public void scrollToCurrent() { scrollTo(initialScroll(), true); }

    // ---- state changes (called by the hub) ----------------------------------------------------------

    public void refreshCompleted(int level) { loadCompletedThumb(level); }

    /** Fades in the completed badge and runs the difficulty glow for a freshly finished level. */
    public void applyCompletedBadge(int level) {
        if (!badgeShown[level]) {
            badgeShown[level] = true;
            badgeT[level] = 0f;           // fades in once and then stays
        }
        glowT[level] = 0f;
    }

    /** Opens every level of the given tier with the chain-unlock animation; returns how many animated. */
    public int unlockTier(int tier, boolean animated) {
        int animatedCount = 0;
        for (int i = 1; i <= type.levels; i++) {
            if (type.difficulty(i) != tier || unlockedState[i]) continue;
            unlockedState[i] = true;
            if (animated && animatedCount < 4) {
                unlockT[i] = 0f;
                unlockDelay[i] = animatedCount * 0.15f;
                animatedCount++;
            } else {
                unlockT[i] = -1f;
            }
        }
        return animatedCount;
    }

    public void rattle(int level) { rattleT[level] = 0f; }

    public void update(float dt) {
        if (dragging) {
            velElapsed += dt;
        } else if (flingVelocity != 0f) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + flingVelocity * dt));
            // exponential friction
            flingVelocity *= (float) Math.pow(1f - FLING_FRICTION * 0.1f, dt * 60f);
            if (Math.abs(flingVelocity) < 1f || scroll <= 0 || scroll >= maxScroll()) flingVelocity = 0f;
        }
        if (scrollAnimT < 1f) {
            scrollAnimT = Math.min(1f, scrollAnimT + dt / 0.5f);
            float k = 1f - (1f - scrollAnimT) * (1f - scrollAnimT);
            scroll = scrollAnimFrom + (scrollAnimTo - scrollAnimFrom) * k;
        }
        for (int i = 1; i <= type.levels; i++) {
            if (glowT[i] >= 0) { glowT[i] += dt; if (glowT[i] >= 3.0f) glowT[i] = -1f; }   // 1.5 s, autoreversed once
            if (badgeT[i] >= 0) { badgeT[i] += dt; if (badgeT[i] >= 0.25f) badgeT[i] = -1f; }
            if (rattleT[i] >= 0) { rattleT[i] += dt; if (rattleT[i] >= 0.2f) rattleT[i] = -1f; }
            if (unlockT[i] >= 0) {
                if (unlockDelay[i] > 0) unlockDelay[i] -= dt;
                else { unlockT[i] += dt; if (unlockT[i] >= 1.0f) unlockT[i] = -1f; }   // lock and chains 0.5 s, then the dimming 0.5 s
            }
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /** Draws the strip with its view origin at the current graphics origin (clipped to 320x104). */
    public void draw(Gfx g) {
        Gfx gc = g.create();
        gc.clipRect(0, 0, VIEW_W, VIEW_H);
        for (int i = 1; i <= type.levels; i++) {
            float[] r = thumbRect(i);
            // snap to integer so all elements in the slot share the same pixel origin
            float x = (float) Math.floor(contentToView(r[0]) + 0.5f);
            if (x + r[2] < -30 || x > VIEW_W + 30) continue;
            drawLevel(gc, i, x, r);
        }
        gc.dispose();
    }

    private void drawLevel(Gfx g, int level, float x, float[] r) {
        boolean completed = Progress.isCompleted(type, level);
        boolean locked = isLocked(level) && unlockT[level] < 0 && !unlockedState[level];
        boolean unlocking = unlockT[level] >= 0 || (unlockDelay[level] > 0);
        int difficulty = type.difficulty(level);

        // level number above the thumbnail
        Gfx gl = g.create();
        gl.setFont(Fnt.sans(true, 11));
        gl.setColor(67, 67, 67, 255);
        String num = String.valueOf(level);
        gl.drawString(num, x + (r[2] - gl.stringWidth(num)) / 2f, r[1] - 6);
        gl.dispose();

        // thumbnail (completed levels show the saved snapshot of the solved puzzle)
        if (iconAlpha[level] > 0f) {
        if (completed && completedThumb[level] != null) {
            Gfx gt = g.create();
            if (iconAlpha[level] < 1f) gt.setAlpha(iconAlpha[level]);
            if (type.name.equals("Sokoban")) {
                // the level icon is the layout itself at its own proportions, centred in the icon area
                Image im = completedThumb[level];
                float f = Math.max(im.getWidth(), im.getHeight()) > 100 ? 3f : 1f;
                float dw = im.getWidth() / f, dh = im.getHeight() / f;
                gt.drawImage(im, Math.round(x + (r[2] - dw) / 2f), Math.round(r[1] + (r[3] - dh) / 2f), Math.round(dw), Math.round(dh));
            } else {
                gt.drawImage(shaped(level), Math.round(x), Math.round(r[1]), Math.round(r[2]), Math.round(r[3]));
            }
            gt.dispose();
        } else {
            Gfx gt = g.create();
            if (iconAlpha[level] < 1f) gt.setAlpha(iconAlpha[level]);
            thumbSprite(level).draw(gt, x, r[1]);
            gt.dispose();
        }
        }
        boolean stillClosed = !Progress.isUnlocked(type, level) && !completed;
        if (stillClosed || unlocking) {
            float dim = 1f;
            if (unlockT[level] >= 0 && unlockDelay[level] <= 0) dim = 1f - Math.max(0f, Math.min(1f, (unlockT[level] - 0.5f) / 0.5f));
            Gfx gd = g.create();
            gd.setAlpha(0.75f * dim);
            // the dimming covers only what the icon covers (Sokoban's levels are outlines on a transparent square)
            gd.drawImage(dimLayer(thumbSprite(level)), Math.round(x), Math.round(r[1]), Math.round(r[2]), Math.round(r[3]));
            gd.dispose();
        }

        // difficulty pips under the thumbnail
        Sprite pip = Assets.image("level" + difficulty + ".png");
        float py = difficulty == 4 ? 66 : 67;
        pip.draw(g, x + (r[2] - pip.w) / 2f, r[1] + py);
        if (glowT[level] >= 0) {
            float t = glowT[level];
            float a = t < 1.5f ? t / 1.5f : Math.max(0f, 1f - (t - 1.5f) / 1.5f);
            pip.getClass();
            Assets.image("level" + difficulty + "_glow.png").drawAlpha(g, x + (r[2] - pip.w) / 2f, r[1] + py, a);
        }

        // padlock + chains
        if (stillClosed || unlocking) drawLock(g, level, x, r, difficulty);

        // completed badge, bottom-right corner
        if (completed && badgeShown[level]) {
            float a = badgeT[level] >= 0 ? Math.min(1f, badgeT[level] / 0.25f) : 1f;
            Assets.image("level_complete.png").drawAlpha(g, x + r[2] - 24, r[1] + r[3] - 26, a);
        }
    }

    private final java.util.Map<Sprite, Image> dimLayers = new java.util.HashMap<Sprite, Image>();

    /** A black layer with the icon's own shape (its alpha), used to darken locked icons. */
    private Image dimLayer(Sprite icon) {
        Image m = dimLayers.get(icon);
        if (m != null) return m;
        Image src = icon.img;
        int sw = src.getWidth(), sh = src.getHeight();
        int[] px = new int[sw * sh];
        src.getPixels(px, 0, sw, 0, 0, sw, sh);
        for (int i = 0; i < px.length; i++) px[i] &= 0xFF000000;   // black, the icon's alpha
        m = Host.get().createImage(sw, sh);
        m.setPixels(px, 0, sw, 0, 0, sw, sh);
        dimLayers.put(icon, m);
        return m;
    }

    private void drawLock(Gfx g, int level, float x, float[] r, int difficulty) {
        // LevelThumbnailView locked: the frames start at (-4, 9) ((-4, -1) for the fourth tier). Sokoban's icons use the overlay mask:
        // there the fourth tier starts at -12, and both pictures are scaled about their centre to the icon's width
        // (the overlay clipping rectangle, the icon image itself) divided by the lock's own width.
        boolean masked = type.name.equals("Sokoban");
        float chainY = difficulty == 4 ? (masked ? -12 : -1) : 9;
        Sprite chains = Assets.image("level_lock_chains_" + (difficulty - 1) + ".png");
        Sprite lock = Assets.image("level_lock_" + (difficulty - 1) + ".png");
        float lx = x - 4;
        float fit = 1f, offX = 0f, offY = 0f;
        if (masked) {
            Sprite icon = thumbSprite(level);
            fit = icon.w / lock.w;
            // the chains' frame is moved left by (clip height - clip width) / 2, which centres the lock on an icon that is
            // narrower than it is tall (Sokoban's icons are 43 to 66 points wide and 64 tall); the lock follows the chains
            offX = -(icon.h - icon.w) / 2f;
            // adjustLockedChainsByOffset, chosen per level by initLevelButtons
            if (level == 6 || level == 11 || level == 12) { offY = 9f; }
            else if (level == 13) { offX += -2f; offY = 8f; }
            else if (level == 14) { offY = -2f; }
        }
        float alpha = 1f;
        float chainDrop = 0f;
        if (unlockT[level] >= 0 && unlockDelay[level] <= 0) {
            float t = unlockT[level];
            alpha = Math.max(0f, 1f - t / 0.5f);
            chainDrop = 45f * Math.min(1f, t / 0.5f);
        }
        float rot = 0f;
        if (rattleT[level] >= 0) {
            float t = rattleT[level] / 0.05f;
            rot = (float) (-0.04363 * (1 - Math.abs(((t % 2) + 2) % 2 - 1)));
        }
        Gfx gr = g.create();
        gr.translate(lx + chains.w / 2f + offX, r[1] + chainY + chains.h / 2f + offY);
        gr.rotate(rot);
        gr.scale(fit, fit);
        if (alpha < 1f) gr.setAlpha(alpha);
        chains.draw(gr, -chains.w / 2f, -chains.h / 2f + chainDrop);
        lock.draw(gr, -lock.w / 2f, -lock.h / 2f + (difficulty == 4 ? 0 : 0));
        gr.dispose();
    }
}
