package heist.engine.ui;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import heist.engine.Assets;
import heist.engine.Fnt;
import heist.engine.NibArchive;
import heist.engine.NibArchive.Obj;

/**
 * Builds a {@link UiView} tree from a compiled .nib. Outlet connections on the file's owner
 * become named views ({@link UiView#find}); event connections become {@link Binding}s that the
 * owning scene can wire to Java code by selector name.
 */
public final class NibLoader {
    public static final class Binding {
        public final String selector;
        public final UiView source;
        Binding(String selector, UiView source) { this.selector = selector; this.source = source; }
    }

    public static final class Result {
        public final List<UiView> topLevel = new ArrayList<UiView>();
        public final Map<String, UiView> outlets = new LinkedHashMap<String, UiView>();
        public final List<Binding> bindings = new ArrayList<Binding>();
        /** Every view in the archive that has no parent (includes views only reachable through outlets). */
        public final List<UiView> roots = new ArrayList<UiView>();
        public UiView view() { return outlets.get("view"); }
    }

    private final NibArchive nib;
    private final Map<Integer, UiView> built = new LinkedHashMap<Integer, UiView>();

    private NibLoader(NibArchive nib) { this.nib = nib; }

    public static Result load(String nibName) throws IOException {
        return new NibLoader(new NibArchive(Assets.bytes(nibName.endsWith(".nib") ? nibName : nibName + ".nib"))).build();
    }

    public static Result load(File f) throws IOException {
        NibLoader l = new NibLoader(NibArchive.load(f));
        return l.build();
    }

    private Result build() {
        Result r = new Result();
        Obj root = nib.objects.get(0);
        for (Object ref : array(nib.resolve(root.get("UINibObjectsKey")))) {
            Obj o = nib.resolve(ref);
            if (o != null && isView(o)) view(o.index);
        }
        for (Object ref : array(nib.resolve(root.get("UINibTopLevelObjectsKey")))) {
            UiView v = built.get(((NibArchive.Ref) ref).index);
            if (v != null) r.topLevel.add(v);
        }
        for (Object ref : array(nib.resolve(root.get("UINibConnectionsKey")))) {
            Obj c = nib.resolve(ref);
            if (c == null) continue;
            String label = string(c.get("UILabel"));
            Obj src = nib.resolve(c.get("UISource"));
            Obj dst = nib.resolve(c.get("UIDestination"));
            if (c.className.equals("UIRuntimeOutletConnection") && dst != null && isView(dst)) {
                UiView v = view(dst.index);
                if (src != null && isOwner(src)) {
                    if (v.name == null) v.name = label;
                    r.outlets.put(label, v);
                }
            } else if (c.className.equals("UIRuntimeEventConnection") && src != null && isView(src)) {
                r.bindings.add(new Binding(label, view(src.index)));
            }
        }
        for (UiView v : built.values()) if (v.parent == null) r.roots.add(v);
        for (UiView v : r.topLevel) if (r.view() == null && v.parent == null) r.outlets.put("view", v);
        return r;
    }

    private boolean isView(Obj o) {
        String c = o.className;
        return c.startsWith("UI") && (c.equals("UIView") || c.equals("UIImageView") || c.equals("UIButton")
                || c.equals("UIRoundedRectButton") || c.equals("UILabel") || c.equals("UIScrollView")
                || c.equals("UITableView") || c.equals("UIWindow") || c.equals("UIClassSwapper"));
    }

    private boolean isOwner(Obj o) {
        return o.className.equals("UIProxyObject") || "IBFilesOwner".equals(string(o.get("UIProxiedObjectIdentifier")));
    }

    private UiView view(int idx) {
        UiView done = built.get(idx);
        if (done != null) return done;
        Obj o = nib.objects.get(idx);
        String cls = o.className;
        String custom = null;
        if (cls.equals("UIClassSwapper")) {
            custom = string(o.get("UIClassName"));
            cls = string(o.get("UIOriginalClassName"));
            if (cls == null) cls = "UIView";
        }
        UiView v;
        if (cls.equals("UIImageView")) {
            UiImageView iv = new UiImageView();
            Obj img = nib.resolve(o.get("UIImage"));
            if (img != null) iv.imageName = string(img.get("UIResourceName"));
            v = iv;
        } else if (cls.equals("UIButton") || cls.equals("UIRoundedRectButton")) {
            v = button(o);
        } else if (cls.equals("UILabel")) {
            UiLabel l = new UiLabel();
            l.text = orEmpty(string(o.get("UIText")));
            Fnt f = font(nib.resolve(o.get("UIFont")));
            if (f != null) l.font = f;
            Integer tc = color(nib.resolve(o.get("UITextColor")));
            if (tc != null) l.textColor = tc;
            l.alignment = (int) o.num("UITextAlignment", 0);
            v = l;
        } else {
            v = new UiView();
        }
        v.className = custom != null ? custom : cls;
        built.put(idx, v);
        frame(o, v);
        Integer bg = color(nib.resolve(o.get("UIBackgroundColor")));
        if (bg != null) v.background = bg;
        v.hidden = bool(o.get("UIHidden"));
        v.interactive = !bool(o.get("UIUserInteractionDisabled"));
        v.clips = bool(o.get("UIClipsToBounds"));
        if (o.has("UIAlpha")) v.alpha = (float) o.num("UIAlpha", 1);
        for (Object ref : array(nib.resolve(o.get("UISubviews")))) {
            Obj s = nib.resolve(ref);
            if (s != null && isView(s)) v.add(view(s.index));
        }
        return v;
    }

    private UiButton button(Obj o) {
        UiButton b = new UiButton();
        Obj content = nib.resolve(o.get("UIButtonStatefulContent"));
        if (content != null) {
            // NSMutableDictionary: alternating key (NSNumber state) / value (UIButtonContent)
            List<Object> kv = array(content);
            for (int i = 0; i + 1 < kv.size(); i += 2) {
                Obj key = nib.resolve(kv.get(i)), val = nib.resolve(kv.get(i + 1));
                if (key == null || val == null) continue;
                int state = (int) key.num("NS.intval", 0);
                Obj img = nib.resolve(val.get("UIImage"));
                String res = img == null ? null : string(img.get("UIResourceName"));
                if (state == 0) {
                    b.normalImage = res;
                    Integer tc = color(nib.resolve(val.get("UITitleColor")));
                    if (tc != null) b.titleColor = tc;
                    String t = string(val.get("UITitle"));
                    if (t != null) b.title = t;
                } else if (state == 1) b.highlightedImage = res;
                else if (state == 2) b.disabledImage = res;
                else if (state == 4) b.selectedImage = res;
            }
        }
        b.font = font(nib.resolve(o.get("UIFont")));
        return b;
    }

    private void frame(Obj o, UiView v) {
        float[] bounds = floats(o.get("UIBounds"), 4);
        float[] center = floats(o.get("UICenter"), 2);
        if (bounds != null) { v.w = bounds[2]; v.h = bounds[3]; }
        if (center != null) { v.x = center[0] - v.w / 2f; v.y = center[1] - v.h / 2f; }
    }

    private static float[] floats(Object data, int count) {
        if (!(data instanceof byte[])) return null;
        byte[] d = (byte[]) data;
        if (d.length < 1 + count * 4) return null;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) out[i] = b.getFloat(1 + i * 4);
        return out;
    }

    private Integer color(Obj o) {
        if (o == null) return null;
        if (o.has("UIWhite")) {
            float w = (float) o.num("UIWhite", 1);
            return argb(w, w, w, (float) o.num("UIAlpha", 1));
        }
        if (o.has("UIRed")) {
            return argb(clamp(o.num("UIRed", 0)), clamp(o.num("UIGreen", 0)), clamp(o.num("UIBlue", 0)),
                    clamp(o.num("UIAlpha", 1)));
        }
        return null;
    }

    private static int argb(float r, float g, float b, float a) {
        return (Math.round(a * 255) << 24) | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static float clamp(double v) { return (float) Math.max(0, Math.min(1, v)); }

    private Fnt font(Obj o) {
        if (o == null) return null;
        String name = string(o.get("UIFontName"));
        float size = (float) o.num("UIFontPointSize", 17);
        boolean bold = name != null && name.toLowerCase().contains("bold");
        return Fnt.sans(bold, Math.round(size));
    }

    /** Elements of an NSArray / NSMutableArray / NSMutableDictionary (stored as repeated empty keys). */
    private List<Object> array(Obj o) {
        List<Object> out = new ArrayList<Object>();
        if (o == null) return out;
        for (int i = 0; i < o.keys.size(); i++) {
            if (o.keys.get(i).equals("UINibEncoderEmptyKey")) out.add(o.values.get(i));
        }
        return out;
    }

    private String string(Object ref) {
        Obj o = nib.resolve(ref);
        if (o == null) return null;
        if (o.has("NS.bytes")) return o.str("NS.bytes", null);
        return null;
    }

    private static boolean bool(Object o) { return Boolean.TRUE.equals(o); }

    private static String orEmpty(String s) { return s == null ? "" : s; }
}
