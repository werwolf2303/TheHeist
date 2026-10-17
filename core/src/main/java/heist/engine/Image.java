package heist.engine;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A raster picture owned by a platform. Pixels are 32 bit ARGB, not premultiplied, in row-major order. The game never
 * touches the platform's own picture type; it only goes through this interface.
 */
public interface Image {
    int getWidth();

    int getHeight();

    /** Copies a rectangle of pixels into {@code argb} (row {@code stride} apart, starting at {@code offset}). */
    void getPixels(int[] argb, int offset, int stride, int x, int y, int w, int h);

    /** Replaces a rectangle of pixels from {@code argb}. */
    void setPixels(int[] argb, int offset, int stride, int x, int y, int w, int h);

    /** Fills the whole picture with one colour (0 makes it fully transparent). */
    void clear(int argb);

    /** A drawing context that paints onto this picture. */
    Gfx createGraphics();

    /** Writes the picture as a PNG file. */
    void writePng(OutputStream out) throws IOException;
}
