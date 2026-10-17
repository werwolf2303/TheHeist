package heist.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import heist.engine.IpaImportCore;

/**
 * Web .ipa import: runs {@link IpaImportCore}, persists files to OPFS under "game/", and hands audio/video
 * to {@link FfmpegBridge} for codec conversion.
 */
final class WebImportFlow {
    private static final String GAME_PREFIX = "game/";

    interface ProgressListener {
        /** phase is a short label ("Unpacking", "Converting audio 12/59", ...) for the loading bar's text. */
        void onProgress(String phase, float fraction);

        void onDone();

        void onError(String message);
    }

    private final WebPlatform platform;
    private final WebAssetSource assets;
    private final ProgressListener listener;

    WebImportFlow(WebPlatform platform, WebAssetSource assets, ProgressListener listener) {
        this.platform = platform;
        this.assets = assets;
        this.listener = listener;
    }

    /** Called once the player has picked a file; does the whole import, then calls back on the listener. */
    void importFrom(byte[] ipaBytes) {
        try {
            listener.onProgress("Unpacking", 0f);
            final List<String> written = new ArrayList<>();
            IpaImportCore.Sink sink = new IpaImportCore.Sink() {
                public void write(String path, byte[] data) throws IOException {
                    Opfs.write(GAME_PREFIX + path, data);
                    assets.put(path, data);
                    written.add(path);
                }

                public void progress(int filesDone, int totalKnown) {
                    // total entry count isn't known from a stream; show count, not fraction
                    listener.onProgress("Unpacking (" + filesDone + " files)", 0f);
                }
            };
            IpaImportCore core = IpaImportCore.run(new ByteArrayInputStream(ipaBytes), sink);

            if (!core.gotSokobanLevels()) {
                System.err.println("[import] warning: could not find the Sokoban level table in the executable.");
            }

            convertAudio(core.audioToConvert());
            convertVideo(core.videoToConvert());

            if (!assets.exists("Puzzles.plist")) {
                listener.onError("Puzzles.plist is missing - this does not look like The Heist.");
                return;
            }
            listener.onProgress("Loading images", 0.95f);
            platform.preDecodeImages();
            listener.onProgress("Done", 1f);
            listener.onDone();
        } catch (IOException e) {
            listener.onError(e.getMessage());
        } catch (RuntimeException e) {
            listener.onError("Import failed: " + e);
        }
    }

    private void convertAudio(List<String> names) throws IOException {
        int n = names.size();
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            listener.onProgress("Converting audio (" + (i + 1) + "/" + n + ")", (i + 1f) / Math.max(1, n));
            byte[] source = Opfs.read(GAME_PREFIX + name);
            byte[] wav = FfmpegBridge.toWav(source, extOf(name));
            String wavName = withExt(name, "wav");
            Opfs.write(GAME_PREFIX + wavName, wav);
            assets.put(wavName, wav);
        }
    }

    private void convertVideo(String name) throws IOException {
        if (name == null) return;
        listener.onProgress("Converting call video", 0.5f);
        byte[] source = Opfs.read(GAME_PREFIX + name);
        String base = name.substring(0, name.length() - 4);

        FfmpegBridge.VideoResult result = FfmpegBridge.toVideoFrames(source, 12);
        for (int i = 0; i < result.frames.length; i++) {
            String framePath = "secureCallVideo/" + String.format("%04d.jpg", i + 1);
            Opfs.write(GAME_PREFIX + framePath, result.frames[i]);
            assets.put(framePath, result.frames[i]);
        }

        String wavName = base + ".wav";
        Opfs.write(GAME_PREFIX + wavName, result.wav);
        assets.put(wavName, result.wav);
    }

    /** Returns true if a previous import was found in OPFS and loaded. */
    static boolean loadExisting(WebPlatform platform, WebAssetSource assets) {
        List<String> names = Opfs.listNames(GAME_PREFIX);
        if (names.isEmpty()) return false;
        for (String path : names) {
            try {
                byte[] data = Opfs.read(path);
                if (data != null) assets.put(path.substring(GAME_PREFIX.length()), data);
            } catch (IOException e) {
                System.err.println("[import] could not reload " + path + ": " + e);
            }
        }
        if (!assets.exists("Puzzles.plist")) return false;
        platform.preDecodeImages();
        return true;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String withExt(String name, String ext) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "." + ext;
    }
}
