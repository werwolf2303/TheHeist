package heist.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;

import heist.engine.CgbiPixels;

/**
 * Wraps {@link CgbiPixels} (the shared, platform-neutral decoding core, in :core) into a BufferedImage for
 * the desktop/Android build-time importer. The web port's own importer uses CgbiPixels directly instead,
 * since java.awt isn't available in a TeaVM/WasmGC build.
 */
public final class CgbiDecoder {
    private CgbiDecoder() {}

    public static boolean isCgbi(byte[] d) { return CgbiPixels.isCgbi(d); }

    public static BufferedImage decode(byte[] d) throws IOException {
        CgbiPixels px = CgbiPixels.decode(d);
        BufferedImage img = new BufferedImage(px.width, px.height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, px.width, px.height, px.argb(), 0, px.width);
        return img;
    }
}
