package heist.android;

import android.graphics.Typeface;

import heist.engine.Fnt;

/** A {@link Fnt} backed by an Android Typeface. */
final class AndroidFnt extends Fnt {
    final Typeface face;

    AndroidFnt(Typeface face, float size) {
        super(size);
        this.face = face;
    }

    public Fnt withSize(float s) { return new AndroidFnt(face, s); }
}
