package heist.desktop.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import heist.desktop.AwtGfx;
import heist.engine.Key;
import heist.engine.Save;
import heist.engine.Scene;
import heist.game.Disarm;
import heist.game.HubScene;
import heist.game.Nav;
import heist.game.PuzzleScene;
import heist.game.PuzzleType;

/**
 * Headless renderer for comparing the port's artwork with the original.
 * Usage: Screenshot &lt;assetsDir&gt; &lt;PuzzleName&gt; &lt;level&gt; &lt;out.png&gt; [scale]
 *        Screenshot &lt;assetsDir&gt; Hub &lt;script&gt; &lt;out.png&gt; [scale]
 * Script: comma-separated steps — wait:&lt;s&gt;  shot:&lt;file&gt;  tap:&lt;x&gt;:&lt;y&gt;  open:&lt;0-3&gt;  prefs  points:&lt;n&gt;
 */
public final class Screenshot {
    private Screenshot() {}

    public static void main(String[] args) throws Exception {
        ToolSetup.init(new File(args[0]));
        if (System.getProperty("heist.disarm") != null) {
            Save.put("disarmLevel", Integer.parseInt(System.getProperty("heist.disarm")));
            Save.put("displaySecureCallIntroductionCompleted", true);
            Save.put("animationSecuritySequenceCompleted", true);
        }
        Disarm.reload();
        int scale = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        Scene s;
        if (args[1].equals("Hub")) {
            HubScene hub = new HubScene();
            Nav.setHub(hub);
            hub.onEnter();
            hub.advance(0.1f);
            run(hub, args[2]);
            s = current;
        } else {
            PuzzleType t = PuzzleType.byName(args[1]);
            PuzzleScene p = Nav.create(t, Integer.parseInt(args[2]));
            p.onEnter();
            s = p;
        }
        write(s, scale, args[3]);
    }

    private static int shotScale = 2;

    static void write(Scene s, int scale, String out) throws Exception {
        BufferedImage img = new BufferedImage(320 * scale, 480 * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        AwtGfx g = new AwtGfx(g2, true);
        g.scale(scale, scale);
        s.render(g);
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out);
    }

    private static HubScene current;

    private static void run(HubScene first, String script) {
        current = first;
        float[][] button = { { 40, 20 }, { 120, 20 }, { 200, 20 }, { 280, 20 } };
        for (String step : script.split(",")) {
            step = step.trim();
            if (step.isEmpty()) continue;
            String[] p = step.split(":");
            if (Nav.hub() != current) { current = Nav.hub(); current.onEnter(); } // the debug menu rebuilds the room
            HubScene hub = current;
            if (p[0].equals("wait")) hub.advance(Float.parseFloat(p[1]));
            else if (p[0].equals("press")) hub.pointerPressed(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
            else if (p[0].equals("move")) hub.pointerDragged(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
            else if (p[0].equals("release")) hub.pointerReleased(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
            else if (p[0].equals("key")) hub.keyPressed(Key.valueOf(p[1]));
            else if (p[0].equals("shot")) {
                try { write(hub, shotScale, p[1]); } catch (Exception e) { throw new RuntimeException(e); }
            }
            else if (p[0].equals("tap")) tap(hub, Float.parseFloat(p[1]), Float.parseFloat(p[2]));
            else if (p[0].equals("open")) {
                int i = Integer.parseInt(p[1]);
                tap(hub, button[i][0], 387 + button[i][1]);
                hub.advance(0.6f);
            } else if (p[0].equals("prefs")) {
                tap(hub, 296, 446 + 15 - (hub.isLevelSelectorShowing() ? 104 : 0) * 0);
                hub.advance(0.6f);
            } else if (p[0].equals("points")) {
                for (PuzzleType t : PuzzleType.all()) { }
            }
        }
    }

    private static void tap(HubScene hub, float x, float y) {
        hub.pointerPressed(x, y);
        hub.advance(0.05f);
        hub.pointerReleased(x, y);
    }
}
