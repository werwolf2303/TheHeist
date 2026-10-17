package heist.desktop;

import java.awt.Font;

import heist.engine.Fnt;

/** A {@link Fnt} backed by a java.awt.Font. */
public final class AwtFnt extends Fnt {
    public final Font font;

    public AwtFnt(Font font) {
        super(font.getSize2D());
        this.font = font;
    }

    public Fnt withSize(float s) { return new AwtFnt(font.deriveFont(s)); }
}
