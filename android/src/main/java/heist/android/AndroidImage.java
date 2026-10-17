package heist.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.io.IOException;
import java.io.OutputStream;

import heist.engine.Gfx;
import heist.engine.Image;

/** An {@link Image} backed by an ARGB_8888 Bitmap. */
final class AndroidImage implements Image {
    final Bitmap bitmap;

    AndroidImage(Bitmap bitmap) { this.bitmap = bitmap; }

    static AndroidImage create(int w, int h) {
        return new AndroidImage(Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888));
    }

    public int getWidth() { return bitmap.getWidth(); }

    public int getHeight() { return bitmap.getHeight(); }

    public void getPixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        bitmap.getPixels(argb, offset, stride, x, y, w, h);
    }

    public void setPixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        bitmap.setPixels(argb, offset, stride, x, y, w, h);
    }

    public void clear(int argb) { bitmap.eraseColor(argb); }

    public Gfx createGraphics() { return new AndroidGfx(new Canvas(bitmap)); }

    public void writePng(OutputStream out) throws IOException {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("cannot write the picture");
    }
}
