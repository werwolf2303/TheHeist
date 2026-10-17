package heist.desktop.tools;

import java.io.File;
import java.nio.charset.Charset;

import heist.engine.NibArchive;

/** Prints every object of a compiled .nib (or all nibs in a folder) for inspection. */
public final class NibDump {
    private NibDump() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: NibDump <file.nib | folder>");
            System.exit(2);
        }
        File f = new File(args[0]);
        File[] list = f.isDirectory() ? f.listFiles() : new File[] { f };
        java.util.Arrays.sort(list);
        for (File n : list) {
            if (!n.getName().endsWith(".nib")) continue;
            System.out.println("==== " + n.getName());
            NibArchive a = NibArchive.load(n);
            for (NibArchive.Obj o : a.objects) {
                System.out.println("#" + o.index + " " + o.className);
                for (int i = 0; i < o.keys.size(); i++) {
                    System.out.println("    " + o.keys.get(i) + " = " + show(o.values.get(i)));
                }
            }
        }
    }

    private static String show(Object v) {
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            boolean text = b.length > 0;
            for (byte x : b) if (x != 0 && (x < 32 || x > 126)) { text = false; break; }
            if (text) return "\"" + new String(b, Charset.forName("US-ASCII")).replace("\0", "") + "\"";
            StringBuilder sb = new StringBuilder("<" + b.length + "B ");
            for (int i = 0; i < Math.min(b.length, 40); i++) sb.append(String.format("%02x", b[i]));
            return sb.append(b.length > 40 ? "..>" : ">").toString();
        }
        return String.valueOf(v);
    }
}
