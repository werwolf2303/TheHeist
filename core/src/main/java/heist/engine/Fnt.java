package heist.engine;

import heist.platform.Host;

/** A typeface at a point size. The platform supplies the actual font object behind it. */
public abstract class Fnt {
    public final float size;

    protected Fnt(float size) {
        this.size = size;
    }

    /** The same typeface at another point size. */
    public abstract Fnt withSize(float s);

    /** The system sans serif face, optionally bold. */
    public static Fnt sans(boolean bold, float size) {
        return Host.get().sansFont(bold, size);
    }
}
