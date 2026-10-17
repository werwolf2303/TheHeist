package heist.platform;

import java.io.IOException;

import heist.engine.Fnt;
import heist.engine.Image;

/** Everything the game needs from the system it runs on. Implement once per port, register with {@link Host#set}. */
public interface Platform {
    /** A transparent picture of the given size, which can be drawn onto. */
    Image createImage(int width, int height);

    /** Decodes a PNG or JPEG file; null if the data cannot be read. */
    Image decodeImage(byte[] encoded);

    /** Like decodeImage but with a name; override to return a cached result. */
    default Image decodeImageNamed(String name, byte[] encoded) { return decodeImage(encoded); }

    /** Called after an image is decoded; platforms may record the name for debug tools. */
    default void imageLoaded(Image image, String assetName) {}

    /** The system sans serif face, optionally bold. */
    Fnt sansFont(boolean bold, float size);

    /** Loads "&lt;name&gt;.ttf" from the game files at the given point size. */
    Fnt loadFont(String name, float size) throws IOException;

    /** Where the game's own files (pictures, sounds, plists, nibs) come from. */
    AssetSource assets();

    /** Where the player's progress is kept. */
    Storage storage();

    /** How sound reaches the speakers. */
    AudioBackend audio();

    /** Called after a blob write; platforms can pre-populate their decode cache. */
    default void blobWritten(String name, byte[] data, Image decoded) {}

    /** Non-fatal diagnostic; shown in the developer log, not to the player. */
    void warn(String message);

    void error(String message);

    /** True on iPhone (proximity sensor present); false on iPod touch (speaker button lit but disabled). */
    boolean hasProximitySensor();

    /** Live front-facing camera feed for the prize video call's self-view, or null to play without one. */
    CameraPreview frontCamera();
}
