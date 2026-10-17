package heist.web;

import heist.engine.Fnt;

/**
 * A {@link Fnt} backed by a CSS font string Canvas2D understands directly ("bold 17px sans-serif", or a named
 * custom family once {@link WebFontLoader} has it registered).
 */
final class WebFnt extends Fnt {
    final String cssFamily;
    final boolean bold;

    WebFnt(String cssFamily, boolean bold, float size) {
        super(size);
        this.cssFamily = cssFamily;
        this.bold = bold;
    }

    public Fnt withSize(float s) { return new WebFnt(cssFamily, bold, s); }

    /** The string Canvas2D's font property expects. */
    String cssFont() {
        return (bold ? "bold " : "") + size + "px " + cssFamily;
    }
}
