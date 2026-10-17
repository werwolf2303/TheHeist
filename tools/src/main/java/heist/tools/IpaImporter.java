package heist.tools;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import heist.engine.BinaryData;

/**
 * Extracts game resources from a The Heist .ipa: decrushes PNGs, converts audio to wav via ffmpeg (if on PATH),
 * and extracts the Sokoban level table from the Mach-O binary (but never writes the binary itself).
 */
public final class IpaImporter {
    private IpaImporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: IpaImporter <game.ipa> [outputDir=assets]");
            System.exit(2);
        }
        int rc = importIpa(new File(args[0]), new File(args.length > 1 ? args[1] : "assets"));
        System.exit(rc);
    }

    /** Returns 0 on success, 1 on fatal error, 3 if some files failed. */
    public static int importIpa(File ipa, File out) throws IOException {
        if (!ipa.isFile()) {
            System.err.println("IPA not found: " + ipa);
            return 1;
        }
        if (!out.isDirectory() && !out.mkdirs()) {
            System.err.println("cannot create " + out);
            return 1;
        }
        ZipFile zip = new ZipFile(ipa);
        int files = 0, pngs = 0, failed = 0;
        boolean gotLevels = false;
        List<File> audio = new ArrayList<File>();
        try {
            String prefix = findAppPrefix(zip);
            if (prefix == null) {
                System.err.println("No Payload/<name>.app/ folder found - is this really an .ipa?");
                return 1;
            }
            System.out.println("Importing from " + prefix);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(prefix)) continue;
                String rel = e.getName().substring(prefix.length());
                if (skip(rel)) continue;
                File dest = new File(out, rel);
                File parent = dest.getParentFile();
                if (!parent.isDirectory()) parent.mkdirs();
                byte[] data = read(zip.getInputStream(e));
                if (isMachO(data)) {
                    // skip the binary; only extract the embedded data tables
                    if (BinaryData.isEncrypted(data)) {
                        System.err.println("This .ipa's binary is still FairPlay-encrypted (" + rel + ").");
                        System.err.println("Decrypt it first on a jailbroken device (e.g. with Clutch or");
                        System.err.println("dumpdecrypted - this app is 32-bit/iOS 4-5, so newer tools such");
                        System.err.println("as bfdecrypt generally do not support it) and re-export the .ipa.");
                        return 1;
                    }
                    byte[] lv = BinaryData.sokobanLevels(data);
                    if (lv != null) {
                        write(new File(out, "sokoban_levels.bin"), lv);
                        System.out.println("Extracted Sokoban level table from " + rel + " (" + lv.length + " bytes).");
                        gotLevels = true;
                    }
                    continue;
                }
                String lower = rel.toLowerCase();
                if (lower.endsWith(".png") && CgbiDecoder.isCgbi(data)) {
                    try {
                        BufferedImage img = CgbiDecoder.decode(data);
                        ImageIO.write(img, "png", dest);
                        pngs++;
                    } catch (IOException ex) {
                        System.err.println("  ! " + rel + ": " + ex.getMessage());
                        failed++;
                    }
                } else {
                    write(dest, data);
                    if (lower.endsWith(".m4a") || lower.endsWith(".aif") || lower.endsWith(".caf")
                            || lower.endsWith(".aiff") || lower.endsWith(".mp3")) {
                        audio.add(dest);
                    }
                }
                files++;
            }
        } finally {
            zip.close();
        }
        if (!gotLevels) System.err.println("Warning: could not find the Sokoban level table in the executable.");
        System.out.println("Extracted " + files + " files (" + pngs + " PNGs decrushed, " + failed + " failed).");
        convertAudio(audio);
        convertVideos(out);
        write(new File(out, ".imported"), "theheist-java importer v1\n".getBytes("UTF-8"));
        if (!new File(out, "Puzzles.plist").isFile()) {
            System.err.println("Warning: Puzzles.plist is missing - this does not look like The Heist.");
            return 1;
        }
        System.out.println("Done. Run the game with tools/run.sh");
        return failed == 0 ? 0 : 3;
    }

    private static void convertAudio(List<File> audio) {
        if (audio.isEmpty()) return;
        if (!hasFfmpeg()) {
            System.err.println("ffmpeg not found on PATH: skipping audio conversion (" + audio.size()
                    + " files). Install ffmpeg and re-run the import to get sound.");
            return;
        }
        int ok = 0;
        for (File f : audio) {
            String name = f.getName();
            File wav = new File(f.getParentFile(), name.substring(0, name.lastIndexOf('.')) + ".wav");
            if (wav.exists()) { f.delete(); ok++; continue; } // already converted / bundle ships a wav
            try {
                Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-i", f.getPath(),
                        "-acodec", "pcm_s16le", wav.getPath()).redirectErrorStream(true).start();
                drain(p.getInputStream());
                if (p.waitFor() == 0) { ok++; f.delete(); }
                else System.err.println("  ! ffmpeg failed for " + name);
            } catch (Exception ex) {
                System.err.println("  ! " + name + ": " + ex.getMessage());
            }
        }
        System.out.println("Converted " + ok + "/" + audio.size() + " audio files to wav.");
    }

    /** Splits .m4v files into jpg frames at 12 fps and a wav soundtrack via ffmpeg. */
    private static void convertVideos(File out) {
        File[] vids = out.listFiles();
        if (vids == null) return;
        for (File f : vids) {
            if (!f.getName().toLowerCase().endsWith(".m4v")) continue;
            if (!hasFfmpeg()) {
                System.err.println("ffmpeg not found on PATH: skipping " + f.getName() + " (the call video will not play).");
                return;
            }
            String base = f.getName().substring(0, f.getName().length() - 4);
            File frames = new File(out, "secureCallVideo");
            frames.mkdirs();
            try {
                Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-i", f.getPath(), "-vf", "fps=12",
                        "-q:v", "3", new File(frames, "%04d.jpg").getPath()).redirectErrorStream(true).start();
                drain(p.getInputStream());
                boolean ok = p.waitFor() == 0;
                p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-i", f.getPath(), "-vn", "-acodec", "pcm_s16le",
                        new File(out, base + ".wav").getPath()).redirectErrorStream(true).start();
                drain(p.getInputStream());
                ok &= p.waitFor() == 0;
                if (ok) f.delete();
                else System.err.println("  ! ffmpeg failed for " + f.getName());
            } catch (Exception ex) {
                System.err.println("  ! " + f.getName() + ": " + ex.getMessage());
            }
        }
    }

    private static boolean hasFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            drain(p.getInputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String findAppPrefix(ZipFile zip) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith("Payload/")) {
                int slash = n.indexOf('/', 8);
                if (slash > 0 && n.substring(8, slash).endsWith(".app")) return n.substring(0, slash + 1);
            }
        }
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

    private static byte[] read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[1024];
        while (in.read(buf) > 0) { /* discard */ }
    }

    private static void write(File f, byte[] data) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
    }
}
