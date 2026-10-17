package heist.web;

import java.io.IOException;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * Calls into site/src/ffmpeg-bridge.js (window.heistFfmpegBridge, loaded as an ES module by the page - see
 * that file's own comment on why it's reached this way rather than imported directly into a @JSBody script).
 * This is the one part of the import pipeline that needs a real audio/video codec, which nothing in Java
 * (TeaVM-compiled or otherwise) provides - see IpaImportCore's javadoc for where this fits into the rest of
 * the import.
 */
final class FfmpegBridge {
    private FfmpegBridge() {}

    @JSBody(params = { "bytes", "ext" }, script = "return window.heistFfmpegBridge.convertToWav(bytes, ext);")
    private static native JSPromise<Uint8Array> convertToWavJs(Uint8Array bytes, String ext);

    @JSBody(params = { "bytes", "fps" }, script = "return window.heistFfmpegBridge.convertVideo(bytes, fps);")
    private static native JSPromise<JSObject> convertVideoJs(Uint8Array bytes, int fps);

    @JSBody(params = "result", script = "return result.frames;")
    private static native JSArrayReader<Uint8Array> frames(JSObject result);

    @JSBody(params = "result", script = "return result.wav;")
    private static native Uint8Array wav(JSObject result);

    static byte[] toWav(byte[] source, String sourceExt) throws IOException {
        try {
            Uint8Array result = convertToWavJs(toJs(source), sourceExt).await();
            return fromJs(result);
        } catch (RuntimeException e) {
            throw new IOException("ffmpeg could not convert " + sourceExt + " to wav: " + e, e);
        }
    }

    /** Both the jpg frames and the wav soundtrack from one video, in a single ffmpeg.wasm pass (the JS side's
     *  convertVideo() already does both together - see its own comment). */
    static final class VideoResult {
        final byte[][] frames;
        final byte[] wav;
        VideoResult(byte[][] frames, byte[] wav) { this.frames = frames; this.wav = wav; }
    }

    static VideoResult toVideoFrames(byte[] source, int fps) throws IOException {
        try {
            JSObject result = convertVideoJs(toJs(source), fps).await();
            JSArrayReader<Uint8Array> jsFrames = frames(result);
            byte[][] frames = new byte[jsFrames.getLength()][];
            for (int i = 0; i < frames.length; i++) frames[i] = fromJs(jsFrames.get(i));
            return new VideoResult(frames, fromJs(wav(result)));
        } catch (RuntimeException e) {
            throw new IOException("ffmpeg could not convert the call video: " + e, e);
        }
    }

    private static Uint8Array toJs(byte[] b) {
        Uint8Array arr = new Uint8Array(b.length);
        for (int i = 0; i < b.length; i++) arr.set(i, (short) (b[i] & 0xff));
        return arr;
    }

    private static byte[] fromJs(Uint8Array arr) {
        int n = arr.getLength();
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) arr.get(i);
        return out;
    }
}
