package heist.game;

import heist.engine.Gfx;

import heist.engine.Assets;
import heist.engine.Sprite;

/**
 * The segmented progress meter (GameProgressBar): 4px left/right caps and a stretched 1px middle,
 * "meter_<colour>_*cap.png". The fill animates over 2s; gold bars pulse a glow layer and silver bars
 * mean the 100% run. Tick marks show how many points the next level-up needs.
 */
public final class ProgressBarView {
    public String color = "blue";
    public int fullWidth = 231;
    public float percent;
    private float shown;           // animated percent
    private float fromPercent, animT = 1f;
    public boolean animate;
    private boolean golden;
    private int ticks;
    private float glowT;

    public boolean isSilver() { return "silver".equals(color); }

    public void drawGolden() { golden = true; color = "gold"; animate = false; update(1f); shown = 1f; }

    public void drawSilver() { golden = false; color = "silver"; }

    public void setColor(String c) { color = c; }

    public void setTicks(int pointsRequired) {
        ticks = pointsRequired;
    }

    /** Sets a new target percentage (animated over 2s when {@link #animate} is on). */
    public void update(float p) {
        percent = Math.max(0f, Math.min(1f, p));
        if (animate) { fromPercent = shown; animT = 0f; }
        else { shown = percent; animT = 1f; }
    }

    public boolean isAnimating() { return animT < 1f; }

    public void tick(float dt) {
        if (animT < 1f) {
            animT = Math.min(1f, animT + dt / 2f);
            float k = heist.engine.Ease.inOut(animT);   // ease in/out
            shown = fromPercent + (percent - fromPercent) * k;
        }
        glowT += dt;
    }

    public void draw(Gfx g, float x, float y) {
        Gfx g2 = g.create();
        g2.translate(x, y);
        int middle = (int) (fullWidth * shown) - 8;
        if ((int) (fullWidth * shown) >= 1) {
            drawFill(g2, "meter_" + color, middle, 1f);
            if (golden) {
                // pulsing glow: alpha 0..1 autoreverse every 1s
                float a = 0.5f - 0.5f * (float) Math.cos(glowT * Math.PI / 1.0);
                drawFill(g2, "meter_gold_glow", middle, a);
            }
        }
        if (ticks > 1) {
            // drawProgressBarTicks: n segments have n - 1 dividers, the i-th at ceil(width / n * i) (the meter's own
            // x is one point right of the tick coordinates, hence the - 1)
            Sprite tick = Assets.image("game_meter_tick.png");
            for (int i = 1; i < ticks; i++) tick.draw(g2, (float) Math.ceil(fullWidth / (float) ticks * i) - 1f, 10);
        }
        g2.dispose();
    }

    private void drawFill(Gfx g, String base, int middleWidth, float alpha) {
        if (alpha <= 0f) return;
        Gfx g2 = g.create();
        if (alpha < 1f) g2.setAlpha(alpha);
        Sprite left = Assets.image(base + "_leftcap.png"), mid = Assets.image(base + "_middle.png"),
                right = Assets.image(base + "_rightcap.png");
        if (middleWidth < 1) {
            // tiny fill: just the middle sliver between the caps
            mid.draw(g2, 4, 0, Math.max(0, middleWidth + 8 - 8), 8);
            right.draw(g2, 4, 0);
        } else {
            left.draw(g2, 0, 0);
            mid.draw(g2, 4, 0, middleWidth, 8);
            right.draw(g2, 4 + middleWidth, 0);
        }
        g2.dispose();
    }
}
