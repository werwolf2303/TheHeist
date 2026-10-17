package heist.desktop.tools;

import java.io.File;
import java.util.Arrays;

import heist.engine.ui.NibLoader;
import heist.engine.ui.UiButton;
import heist.engine.ui.UiImageView;
import heist.engine.ui.UiLabel;
import heist.engine.ui.UiView;

/** Prints the decoded view hierarchy, outlets and actions of nibs (a quick way to inspect the UI). */
public final class NibTree {
    private NibTree() {}

    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        File[] list = f.isDirectory() ? f.listFiles() : new File[] { f };
        Arrays.sort(list);
        for (File n : list) {
            if (!n.getName().endsWith(".nib")) continue;
            System.out.println("==== " + n.getName());
            NibLoader.Result r = NibLoader.load(n);
            for (UiView v : r.roots) print(v, 1);
            for (String k : r.outlets.keySet()) System.out.println("  outlet " + k + " -> " + r.outlets.get(k));
            for (NibLoader.Binding b : r.bindings) System.out.println("  action " + b.selector + " <- " + b.source);
        }
    }

    private static void print(UiView v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v);
        if (v instanceof UiImageView) sb.append(" image=").append(((UiImageView) v).imageName);
        if (v instanceof UiButton) {
            UiButton b = (UiButton) v;
            sb.append(" normal=").append(b.normalImage).append(" hi=").append(b.highlightedImage);
            if (b.title != null) sb.append(" title=").append(b.title);
        }
        if (v instanceof UiLabel) sb.append(" text=").append(((UiLabel) v).text);
        if (v.hidden) sb.append(" HIDDEN");
        System.out.println(sb);
        for (UiView c : v.subviews) print(c, depth + 1);
    }
}
