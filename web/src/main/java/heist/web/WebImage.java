package heist.web;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

import org.teavm.jso.browser.Window;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.canvas.ImageData;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.typedarrays.Uint8ClampedArray;

import heist.engine.Gfx;
import heist.engine.Image;

/**
 * An {@link Image} backed by an offscreen HTMLCanvasElement - every picture (loaded art, a thumbnail
 * snapshot, a composited mask layer) gets its own canvas, exactly as every other port gives every picture
 * its own BufferedImage/Bitmap. Pixels are always 32 bit ARGB as {@link Image} requires; a canvas's own
 * ImageData is RGBA with straight (non-premultiplied) alpha, so {@link #getPixels}/{@link #setPixels} convert
 * between the two channel orders.
 */
final class WebImage implements Image {
    final HTMLCanvasElement canvas;
    final CanvasRenderingContext2D ctx;

    WebImage(int w, int h) {
        HTMLDocument doc = Window.current().getDocument();
        canvas = (HTMLCanvasElement) doc.createElement("canvas");
        canvas.setWidth(Math.max(1, w));
        canvas.setHeight(Math.max(1, h));
        ctx = (CanvasRenderingContext2D) canvas.getContext("2d");
    }

    /** Wraps an existing canvas (e.g. a live camera capture canvas) without allocating a new one. */
    WebImage(HTMLCanvasElement existing) {
        canvas = existing;
        ctx = (CanvasRenderingContext2D) existing.getContext("2d");
    }

    public int getWidth() { return canvas.getWidth(); }

    public int getHeight() { return canvas.getHeight(); }

    public void getPixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        ImageData data = ctx.getImageData(x, y, w, h);
        Uint8ClampedArray px = data.getData();
        int si = 0;
        for (int row = 0; row < h; row++) {
            int d = offset + row * stride;
            for (int col = 0; col < w; col++) {
                int r = px.get(si), g = px.get(si + 1), b = px.get(si + 2), a = px.get(si + 3);
                argb[d + col] = (a << 24) | (r << 16) | (g << 8) | b;
                si += 4;
            }
        }
    }

    public void setPixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        Uint8ClampedArray px = new Uint8ClampedArray(w * h * 4);
        int si = 0;
        for (int row = 0; row < h; row++) {
            int s = offset + row * stride;
            for (int col = 0; col < w; col++) {
                int p = argb[s + col];
                px.set(si, (p >> 16) & 0xff);
                px.set(si + 1, (p >> 8) & 0xff);
                px.set(si + 2, p & 0xff);
                px.set(si + 3, (p >>> 24) & 0xff);
                si += 4;
            }
        }
        ctx.putImageData(new ImageData(px, w, h), x, y);
    }

    public void clear(int argb) {
        ctx.save();
        ctx.setGlobalCompositeOperation("copy");
        ctx.setFillStyle(WebGfx.cssColor(argb));
        ctx.fillRect(0, 0, getWidth(), getHeight());
        ctx.restore();
    }

    public Gfx createGraphics() { return new WebGfx(ctx); }

    /** canvas.toDataURL encodes synchronously; HTMLCanvasElement.toBlob isn't bound in this TeaVM version. */
    public void writePng(OutputStream out) throws IOException {
        String dataUrl = canvas.toDataURL("image/png");
        String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
        out.write(Base64.getDecoder().decode(b64));
    }
}
