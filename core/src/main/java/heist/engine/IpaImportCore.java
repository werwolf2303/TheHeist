package heist.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Platform-neutral .ipa import: unzip, FairPlay check, Sokoban level extraction, CgBI PNG decrush. */
public final class IpaImportCore {
    /** Where a processed file's bytes should be written, and what conversion (if any) it still needs. */
    public interface Sink {
        /** Writes a file exactly as it should end up relative to the game files root. */
        void write(String relativePath, byte[] data) throws IOException;

        /** Called after every entry so the UI can show progress. */
        default void progress(int filesDone, int totalKnown) {}
    }

    private final Sink sink;
    private String appPrefix;
    private boolean gotLevels;
    private int files, pngs, failed;
    private final List<String> audioToConvert = new ArrayList<>();
    private String videoToConvert;

    public IpaImportCore(Sink sink) {
        this.sink = sink;
    }

    /** False until the Payload/&lt;name&gt;.app/ prefix is found; entries before it are skipped. */
    public boolean ready() { return appPrefix != null; }

    /** The names (as given to {@link Sink#write}) of bundled audio files that still need ffmpeg conversion to wav. */
    public List<String> audioToConvert() { return audioToConvert; }

    /** The bundled call video's name (as given to {@link Sink#write}), or null if there wasn't one. */
    public String videoToConvert() { return videoToConvert; }

    public int filesExtracted() { return files; }

    public int pngsDecrushed() { return pngs; }

    public int pngsFailed() { return failed; }

    public boolean gotSokobanLevels() { return gotLevels; }

    /** Streams the .ipa; throws on FairPlay encryption or a missing app bundle. */
    public static IpaImportCore run(InputStream ipaBytes, Sink sink) throws IOException {
        IpaImportCore core = new IpaImportCore(sink);
        ZipInputStream zis = new ZipInputStream(ipaBytes);
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                core.process(e.getName(), zis);
            }
        } finally {
            zis.close();
        }
        if (!core.ready()) {
            throw new IOException("No Payload/<name>.app/ folder found - is this really an .ipa?");
        }
        return core;
    }

    private void process(String name, InputStream entryData) throws IOException {
        if (appPrefix == null) {
            String prefix = appPrefixOf(name);
            if (prefix == null) return;   // not inside a Payload/<name>.app/ yet
            appPrefix = prefix;
        }
        if (!name.startsWith(appPrefix)) return;
        String rel = name.substring(appPrefix.length());
        if (rel.isEmpty() || skip(rel)) return;

        byte[] data = readAll(entryData);

        if (isMachO(data)) {
            // never extract the executable itself, only the data tables the port needs
            if (BinaryData.isEncrypted(data)) {
                throw new IOException("This .ipa's binary is still FairPlay-encrypted (" + rel + "). Decrypt it "
                        + "first on a jailbroken device (e.g. with Clutch or dumpdecrypted - this app is "
                        + "32-bit/iOS 4-5, so newer tools such as bfdecrypt generally do not support it) and "
                        + "re-export the .ipa.");
            }
            byte[] lv = BinaryData.sokobanLevels(data);
            if (lv != null) {
                sink.write("sokoban_levels.bin", lv);
                gotLevels = true;
            }
            return;
        }

        String lower = rel.toLowerCase();
        if (lower.endsWith(".png") && CgbiPixels.isCgbi(data)) {
            try {
                sink.write(rel, encodePng(CgbiPixels.decode(data)));
                pngs++;
            } catch (IOException ex) {
                failed++;
            }
        } else {
            sink.write(rel, data);
            if (lower.endsWith(".m4a") || lower.endsWith(".aif") || lower.endsWith(".caf")
                    || lower.endsWith(".aiff") || lower.endsWith(".mp3")) {
                audioToConvert.add(rel);
            } else if (lower.endsWith(".m4v")) {
                videoToConvert = rel;
            }
        }
        files++;
        sink.progress(files, -1);
    }

    /** Minimal PNG encoder (deflate, no filter, 8-bit RGBA) — ImageIO is not available in TeaVM. */
    private static byte[] encodePng(CgbiPixels px) throws IOException {
        int w = px.width, h = px.height;
        int[] argb = px.argb();
        byte[] raw = new byte[h * (1 + w * 4)];
        int o = 0;
        for (int y = 0; y < h; y++) {
            raw[o++] = 0;   // filter type 0 (none)
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                raw[o++] = (byte) ((p >> 16) & 0xff);
                raw[o++] = (byte) ((p >> 8) & 0xff);
                raw[o++] = (byte) (p & 0xff);
                raw[o++] = (byte) ((p >>> 24) & 0xff);
            }
        }
        java.util.zip.Deflater def = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!def.finished()) idat.write(buf, 0, def.deflate(buf));
        def.end();

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
        writeChunk(png, "IHDR", ihdr(w, h));
        writeChunk(png, "IDAT", idat.toByteArray());
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static byte[] ihdr(int w, int h) {
        byte[] b = new byte[13];
        putInt(b, 0, w);
        putInt(b, 4, h);
        b[8] = 8;    // bit depth
        b[9] = 6;    // color type: RGBA
        return b;
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes("US-ASCII");
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        out.write(typeBytes);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static String appPrefixOf(String name) {
        if (!name.startsWith("Payload/")) return null;
        int slash = name.indexOf('/', 8);
        if (slash > 0 && name.substring(8, slash).endsWith(".app")) return name.substring(0, slash + 1);
        return null;
    }

    private static boolean skip(String rel) {
        return rel.startsWith("_CodeSignature/") || rel.startsWith("SF_Info/")
                || rel.equals("embedded.mobileprovision")
                || rel.equals("ResourceRules.plist") || rel.equals("Entitlements.plist") || rel.startsWith("FBDialog.bundle/");
    }

    private static boolean isMachO(byte[] d) {
        if (d.length < 4) return false;
        int m = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        return m == 0xCAFEBABE || m == 0xFEEDFACE || m == 0xFEEDFACF || m == 0xCEFAEDFE || m == 0xCFFAEDFE;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
