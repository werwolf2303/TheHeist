package heist.engine;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import heist.platform.Host;

/**
 * Particle system driven by the emitter configuration files the game ships ({@code .pex}: an XML
 * description with the particle texture embedded as gzipped, base64 encoded PNG). Both emitter types are
 * supported: gravity (type 0) and radial (type 1).
 *
 * Positions inside the emitter are in the file's coordinate space (y points up). {@link #draw} maps the
 * emitter's source position to a screen point and flips y.
 */
public final class ParticleEmitter {
    // configuration
    private float srcX, srcY, srcVarX, srcVarY;
    private float speed, speedVar, life, lifeVar, angle, angleVar;
    private float gravX, gravY, radialAccel, radialAccelVar, tangAccel, tangAccelVar;
    private final float[] startColor = new float[4], startColorVar = new float[4];
    private final float[] endColor = new float[4], endColorVar = new float[4];
    private int maxParticles;
    private float startSize, startSizeVar, endSize, endSizeVar;
    private float duration;
    private int type;
    private float maxRadius, maxRadiusVar, minRadius, rotatePerSecond, rotatePerSecondVar;
    private boolean additive;
    private Image texture;

    // state
    private static final class P {
        float x, y, startX, startY, dx, dy, radialAcc, tangAcc;
        float angle, degreesPerSecond, radius, radiusDelta;
        float life, maxLife;
        float r, g, b, a, dr, dg, db, da;
        float size, dSize;
    }

    private final List<P> particles = new ArrayList<P>();
    private final Random rnd = new Random();
    private float emitCounter, elapsed;
    private boolean active = true;

    public ParticleEmitter(byte[] pex) {
        try {
            PexElement root = PexElement.parse(pex);
            PexElement tex = first(root, "texture");
            if (tex != null && tex.attr("data").length() > 0) {
                byte[] raw = base64Decode(tex.attr("data"));
                java.io.InputStream in = new ByteArrayInputStream(raw);
                if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) in = new GZIPInputStream(in);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
                byte[] png = bo.toByteArray();
                texture = decodeTexture(png);
            }
            srcX = attr(root, "sourcePosition", "x");
            srcY = attr(root, "sourcePosition", "y");
            srcVarX = attr(root, "sourcePositionVariance", "x");
            srcVarY = attr(root, "sourcePositionVariance", "y");
            speed = val(root, "speed");
            speedVar = val(root, "speedVariance");
            life = val(root, "particleLifeSpan");
            lifeVar = val(root, "particleLifespanVariance");
            angle = val(root, "angle");
            angleVar = val(root, "angleVariance");
            gravX = attr(root, "gravity", "x");
            gravY = attr(root, "gravity", "y");
            radialAccel = val(root, "radialAcceleration");
            tangAccel = val(root, "tangentialAcceleration");
            radialAccelVar = val(root, "radialAccelVariance");
            tangAccelVar = val(root, "tangentialAccelVariance");
            color(root, "startColor", startColor);
            color(root, "startColorVariance", startColorVar);
            color(root, "finishColor", endColor);
            color(root, "finishColorVariance", endColorVar);
            maxParticles = (int) val(root, "maxParticles");
            startSize = val(root, "startParticleSize");
            startSizeVar = val(root, "startParticleSizeVariance");
            endSize = val(root, "finishParticleSize");
            endSizeVar = val(root, "FinishParticleSizeVariance");
            duration = val(root, "duration");
            type = (int) val(root, "emitterType");
            maxRadius = val(root, "maxRadius");
            maxRadiusVar = val(root, "maxRadiusVariance");
            minRadius = val(root, "minRadius");
            rotatePerSecond = val(root, "rotatePerSecond");
            rotatePerSecondVar = val(root, "rotatePerSecondVariance");
            additive = (int) val(root, "blendFuncDestination") == 1;
        } catch (Exception e) {
            throw new RuntimeException("cannot read particle file: " + e, e);
        }
    }

    private String id = "?";

    public static ParticleEmitter load(String name) {
        try {
            ParticleEmitter e = new ParticleEmitter(Assets.bytes(name));
            e.id = name;
            return e;
        } catch (java.io.IOException ex) {
            throw new RuntimeException("cannot read particle file " + name, ex);
        }
    }

    /** PNG goes through the platform decoder; the game also ships TIFF textures, which platform decoders may not read. */
    private static Image decodeTexture(byte[] d) {
        boolean tiff = d.length > 8 && ((d[0] == 'M' && d[1] == 'M' && d[2] == 0 && d[3] == 42)
                || (d[0] == 'I' && d[1] == 'I' && d[2] == 42 && d[3] == 0));
        if (!tiff) return Host.get().decodeImage(d);
        try {
            return decodeTiff(d);
        } catch (RuntimeException e) {
            Host.get().warn("[particles] cannot read a TIFF texture: " + e);
            return null;
        }
    }

    private static int u16(byte[] d, int o, boolean le) {
        return le ? (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) : ((d[o] & 0xff) << 8) | (d[o + 1] & 0xff);
    }

    private static int u32(byte[] d, int o, boolean le) {
        return le ? u16(d, o, true) | (u16(d, o + 2, true) << 16) : (u16(d, o, false) << 16) | u16(d, o + 2, false);
    }

    /** Uncompressed 8 bit RGB / RGBA TIFF, chunky layout, the form the emitter files carry. */
    private static Image decodeTiff(byte[] d) {
        boolean le = d[0] == 'I';
        int ifd = u32(d, 4, le);
        int count = u16(d, ifd, le);
        int w = 0, h = 0, compression = 1, samples = 3, rowsPerStrip = Integer.MAX_VALUE, extra = 0;
        int[] offsets = null, sizes = null;
        for (int i = 0; i < count; i++) {
            int e = ifd + 2 + i * 12;
            int tag = u16(d, e, le), type = u16(d, e + 2, le), n = u32(d, e + 4, le);
            int valueAt = e + 8;
            int unit = type == 3 ? 2 : 4;                 // SHORT or LONG
            int at = n * unit > 4 ? u32(d, valueAt, le) : valueAt;
            switch (tag) {
                case 256: w = type == 3 ? u16(d, valueAt, le) : u32(d, valueAt, le); break;
                case 257: h = type == 3 ? u16(d, valueAt, le) : u32(d, valueAt, le); break;
                case 259: compression = u16(d, valueAt, le); break;
                case 277: samples = u16(d, valueAt, le); break;
                case 278: rowsPerStrip = type == 3 ? u16(d, valueAt, le) : u32(d, valueAt, le); break;
                case 338: extra = u16(d, at, le); break;
                case 273: case 279: {
                    int[] v = new int[n];
                    for (int k = 0; k < n; k++) v[k] = type == 3 ? u16(d, at + 2 * k, le) : u32(d, at + 4 * k, le);
                    if (tag == 273) offsets = v; else sizes = v;
                    break;
                }
                default: break;
            }
        }
        if (compression != 1 || w <= 0 || h <= 0 || offsets == null || (samples != 3 && samples != 4)) {
            throw new IllegalArgumentException("unsupported TIFF layout");
        }
        int[] px = new int[w * h];
        int row = 0;
        for (int s = 0; s < offsets.length && row < h; s++) {
            int rows = Math.min(rowsPerStrip, h - row);
            for (int r = 0; r < rows; r++, row++) {
                int o = offsets[s] + r * w * samples;
                for (int x = 0; x < w; x++) {
                    int red = d[o] & 0xff, green = d[o + 1] & 0xff, blue = d[o + 2] & 0xff;
                    int alpha = samples == 4 ? d[o + 3] & 0xff : 255;
                    if (samples == 4 && extra == 1 && alpha > 0 && alpha < 255) {   // associated alpha: undo the premultiply
                        red = Math.min(255, red * 255 / alpha);
                        green = Math.min(255, green * 255 / alpha);
                        blue = Math.min(255, blue * 255 / alpha);
                    }
                    px[row * w + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                    o += samples;
                }
            }
        }
        Image b = Host.get().createImage(w, h);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static PexElement first(PexElement root, String tag) { return root.child(tag); }

    private static float attr(PexElement root, String tag, String a) {
        PexElement e = first(root, tag);
        if (e == null || !e.hasAttr(a)) return 0f;
        return Float.parseFloat(e.attr(a));
    }

    private static float val(PexElement root, String tag) { return attr(root, tag, "value"); }

    private static void color(PexElement root, String tag, float[] out) {
        out[0] = attr(root, tag, "red");
        out[1] = attr(root, tag, "green");
        out[2] = attr(root, tag, "blue");
        out[3] = attr(root, tag, "alpha");
    }

    /** Standard base64 decoding, tolerant of line breaks (what Base64.getMimeDecoder() was used for) - the
     *  .pex texture payload is wrapped at a fixed column width, which the plain decoder would reject. */
    private static byte[] base64Decode(String s) {
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') clean.append(c);
        }
        return java.util.Base64.getDecoder().decode(clean.toString());
    }

    /**
     * A minimal reader for the .pex format's shape only: a flat tree of elements, each with plain string
     * attributes and no text content, no CDATA, no comments, no namespaces, no entity escapes beyond the
     * five XML predefines (not needed in practice - the texture's only "exotic" content is base64, which
     * never contains '&lt;', '&amp;' etc.). A full DOM parser (javax.xml.parsers.DocumentBuilderFactory)
     * pulls in reflection and an SPI provider lookup that the web port's TeaVM-compiled runtime cannot
     * support; this format does not need anything close to that.
     */
    private static final class PexElement {
        final String tag;
        final Map<String, String> attrs = new HashMap<String, String>();
        final List<PexElement> children = new ArrayList<PexElement>();

        PexElement(String tag) { this.tag = tag; }

        String attr(String name) { String v = attrs.get(name); return v == null ? "" : v; }

        boolean hasAttr(String name) { return attrs.containsKey(name); }

        /** The first direct child with this tag name, or null. */
        PexElement child(String tag) {
            for (PexElement c : children) if (c.tag.equals(tag)) return c;
            return null;
        }

        static PexElement parse(byte[] xml) {
            String s = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
            int[] pos = { 0 };
            skipProlog(s, pos);
            return parseElement(s, pos);
        }

        private static void skipProlog(String s, int[] pos) {
            while (true) {
                int i = pos[0];
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                if (s.startsWith("<?", i)) { i = s.indexOf("?>", i) + 2; }
                else if (s.startsWith("<!--", i)) { i = s.indexOf("-->", i) + 3; }
                else { pos[0] = i; return; }
                pos[0] = i;
            }
        }

        private static PexElement parseElement(String s, int[] pos) {
            int i = pos[0];
            if (s.charAt(i) != '<') throw new IllegalArgumentException("expected '<' at " + i);
            i++;
            int tagStart = i;
            while (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '-' || s.charAt(i) == ':') i++;
            PexElement e = new PexElement(s.substring(tagStart, i));
            // attributes
            while (true) {
                while (Character.isWhitespace(s.charAt(i))) i++;
                char c = s.charAt(i);
                if (c == '/' || c == '>') break;
                int nameStart = i;
                while (s.charAt(i) != '=' && !Character.isWhitespace(s.charAt(i))) i++;
                String name = s.substring(nameStart, i);
                while (Character.isWhitespace(s.charAt(i))) i++;
                i++; // '='
                while (Character.isWhitespace(s.charAt(i))) i++;
                char quote = s.charAt(i);
                i++;
                int valStart = i;
                while (s.charAt(i) != quote) i++;
                e.attrs.put(name, unescape(s.substring(valStart, i)));
                i++; // closing quote
            }
            if (s.charAt(i) == '/') {
                pos[0] = i + 2; // "/>"
                return e;
            }
            i++; // '>'
            pos[0] = i;
            // children or text, until the matching close tag
            while (true) {
                skipWhitespaceOnly(s, pos);
                if (s.startsWith("</", pos[0])) {
                    pos[0] = s.indexOf('>', pos[0]) + 1;
                    return e;
                }
                if (s.charAt(pos[0]) == '<') {
                    e.children.add(parseElement(s, pos));
                } else {
                    // text content between tags: this format never uses it, so just skip to the next '<'
                    pos[0] = s.indexOf('<', pos[0]);
                }
            }
        }

        private static void skipWhitespaceOnly(String s, int[] pos) {
            int i = pos[0];
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            pos[0] = i;
        }

        private static String unescape(String s) {
            if (s.indexOf('&') < 0) return s;
            return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&apos;", "'").replace("&amp;", "&");
        }
    }

    public float getLife() { return life; }
    public void setLife(float v) { life = v; }
    public float getLifeVariance() { return lifeVar; }
    public void setLifeVariance(float v) { lifeVar = v; }
    public float getStartSize() { return startSize; }
    public void setStartSize(float v) { startSize = v; }
    public void setSourcePosition(float x, float y) { srcX = x; srcY = y; }
    public boolean isActive() { return active; }
    public void stop() { active = false; }
    public int count() { return particles.size(); }
    public boolean isFinished() { return !active && particles.isEmpty(); }

    private float r11() { return rnd.nextFloat() * 2f - 1f; }

    private void emit() {
        P p = new P();
        p.maxLife = p.life = Math.max(0f, life + lifeVar * r11());
        if (p.life <= 0f) return;
        p.x = srcX + srcVarX * r11();
        p.y = srcY + srcVarY * r11();
        p.startX = srcX;
        p.startY = srcY;
        float s0 = Math.max(0f, startSize + startSizeVar * r11());
        float s1 = Math.max(0f, endSize + endSizeVar * r11());
        p.size = s0;
        p.dSize = (s1 - s0) / p.life;
        float[] c0 = new float[4], c1 = new float[4];
        for (int i = 0; i < 4; i++) {
            c0[i] = clamp(startColor[i] + startColorVar[i] * r11());
            c1[i] = clamp(endColor[i] + endColorVar[i] * r11());
        }
        p.r = c0[0]; p.g = c0[1]; p.b = c0[2]; p.a = c0[3];
        p.dr = (c1[0] - c0[0]) / p.life;
        p.dg = (c1[1] - c0[1]) / p.life;
        p.db = (c1[2] - c0[2]) / p.life;
        p.da = (c1[3] - c0[3]) / p.life;
        float a = (float) Math.toRadians(angle + angleVar * r11());
        if (type == 0) {
            float sp = speed + speedVar * r11();
            p.dx = (float) Math.cos(a) * sp;
            p.dy = (float) Math.sin(a) * sp;
            p.radialAcc = radialAccel + radialAccelVar * r11();
            p.tangAcc = tangAccel + tangAccelVar * r11();
        } else {
            float startR = maxRadius + maxRadiusVar * r11();
            float endR = minRadius;
            p.radius = startR;
            p.radiusDelta = (endR - startR) / p.life;
            p.angle = a;
            p.degreesPerSecond = (float) Math.toRadians(rotatePerSecond + rotatePerSecondVar * r11());
        }
        particles.add(p);
    }

    private static float clamp(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    public void update(float dt) {
        if (active && life + lifeVar > 0f) {
            float rate = maxParticles / Math.max(0.0001f, life);
            emitCounter += dt;
            float step = 1f / rate;
            int guard = 0;
            while (particles.size() < maxParticles && emitCounter > step && guard++ < 2000) {
                emit();
                emitCounter -= step;
            }
            if (emitCounter > step) emitCounter = 0f;
            elapsed += dt;
            if (duration != -1f && duration < elapsed) active = false;
        }
        for (int i = particles.size() - 1; i >= 0; i--) {
            P p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0f) { particles.remove(i); continue; }
            if (type == 0) {
                float rx = p.x - p.startX, ry = p.y - p.startY;
                float len = (float) Math.sqrt(rx * rx + ry * ry);
                if (len < 0.0001f) len = 0.0001f;
                rx /= len;
                ry /= len;
                float tx = -ry, ty = rx;
                p.dx += (rx * p.radialAcc + tx * p.tangAcc + gravX) * dt;
                p.dy += (ry * p.radialAcc + ty * p.tangAcc + gravY) * dt;
                p.x += p.dx * dt;
                p.y += p.dy * dt;
            } else {
                p.angle += p.degreesPerSecond * dt;
                p.radius += p.radiusDelta * dt;
                p.x = p.startX - (float) Math.cos(p.angle) * p.radius;
                p.y = p.startY - (float) Math.sin(p.angle) * p.radius;
            }
            p.r += p.dr * dt;
            p.g += p.dg * dt;
            p.b += p.db * dt;
            p.a += p.da * dt;
            p.size = Math.max(0f, p.size + p.dSize * dt);
        }
    }

    /** Draws with the emitter's source position placed at the screen point (sx, sy). */
    public void draw(Gfx g, float sx, float sy) {
        if (texture == null || particles.isEmpty()) return;
        if (!g.beginLayer("fx: " + id)) { g.endLayer(); return; }
        try {
            drawParticles(g, sx, sy);
        } finally {
            g.endLayer();
        }
    }

    private void drawParticles(Gfx g, float sx, float sy) {
        Gfx gg = g.create();
        if (additive) gg.setBlend(Gfx.ADD);
        for (P p : particles) {
            int s = Math.round(p.size);
            if (s < 1) continue;
            float a = clamp(p.a);
            if (a <= 0.002f) continue;
            float px = sx + (p.x - srcX);
            float py = sy - (p.y - srcY);
            gg.drawImageTinted(texture, Math.round(px - s / 2f), Math.round(py - s / 2f), s, s,
                    clamp(p.r), clamp(p.g), clamp(p.b), a);
        }
        gg.dispose();
    }
}
