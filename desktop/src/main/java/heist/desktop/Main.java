package heist.desktop;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import heist.debug.DebugMenu;
import heist.desktop.debug.LayerTrace;
import heist.desktop.debug.LayerWindow;
import heist.desktop.debug.TraceGfx;
import heist.engine.Game;
import heist.engine.Key;
import heist.engine.Save;
import heist.game.HintCall;
import heist.game.HubScene;
import heist.game.Nav;
import heist.platform.Host;
import heist.tools.IpaImporter;

/**
 * The desktop port.
 *
 * <pre>
 *   java -jar theheist.jar                       run the game (assets/ next to where you start it)
 *   java -jar theheist.jar --import game.ipa     import the game files from your own IPA, then run
 *   java -jar theheist.jar --assets dir          use a different folder for the game files
 *   java -jar theheist.jar --data dir            keep the save in a different folder
 *
 * Keys: Escape = back, D = developer menu (animations, scene states, calls, layer window).
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        File assets = new File("assets");
        File data = new File(".");
        File ipa = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--assets") && i + 1 < args.length) assets = new File(args[++i]);
            else if (args[i].equals("--data") && i + 1 < args.length) data = new File(args[++i]);
            else if (args[i].equals("--import") && i + 1 < args.length) ipa = new File(args[++i]);
        }
        if (ipa != null) {
            int rc = IpaImporter.importIpa(ipa, assets);
            if (rc != 0 && rc != 3) System.exit(rc);
        }
        DesktopPlatform platform = new DesktopPlatform(assets, data);
        Host.set(platform);
        if (!heist.engine.Assets.isInstalled()) {
            String msg = "Game assets not found in '" + assets.getPath() + "'.\n\n"
                    + "This project does not include any game files. Import them from your own copy of\n"
                    + "the app with:\n\n    java -jar theheist.jar --import \"path/to/The Heist.ipa\"\n";
            System.err.println(msg);
            try { JOptionPane.showMessageDialog(null, msg, "The Heist", JOptionPane.INFORMATION_MESSAGE); }
            catch (Throwable ignored) { }
            System.exit(1);
        }
        Save.load();
        Runtime.getRuntime().addShutdownHook(new Thread() { public void run() { HintCall.onExit(); } });
        Game game = new Game();
        Nav.init(game);
        HubScene hub = new HubScene();
        Nav.setHub(hub);
        game.setScene(hub);
        game.setOverlay(new DebugMenu(new Runnable() { public void run() { LayerWindow.show(); } },
                new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return LayerWindow.isOpen(); } }));
        new Window(game, platform.assetsDir()).run();
    }

    /** Swing window: game canvas + input wiring. */
    private static final class Window {
        private final Game game;
        private final JFrame frame = new JFrame("The Heist");
        private final Canvas canvas = new Canvas();
        private BufferedImage backbuffer;
        private volatile boolean running;

        Window(Game game, File assets) {
            this.game = game;
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            canvas.setPreferredSize(new Dimension(Game.W * 2, Game.H * 2));
            canvas.setBackground(Color.BLACK);
            canvas.setIgnoreRepaint(true);
            frame.add(canvas);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setMinimumSize(new Dimension(Game.W, Game.H + 30));
            frame.setIconImages(icons(assets));
            LayerWindow.setAnchor(frame);
            installInput();
        }

        /** All available icon sizes for the window and taskbar. */
        private static List<java.awt.Image> icons(File assets) {
            List<java.awt.Image> icons = new ArrayList<java.awt.Image>();
            for (String name : new String[] { "Icon-Small.png", "Icon-Small-50.png", "Icon.png", "Icon-72.png", "Icon-Small@2x.png", "Icon@2x.png" }) {
                try {
                    File f = new File(assets, name);
                    if (f.isFile()) {
                        BufferedImage img = ImageIO.read(f);
                        if (img != null) icons.add(img);
                    }
                } catch (Exception ignored) {}
            }
            return icons;
        }

        private void installInput() {
            MouseAdapter m = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) game.touchDown(e.getX(), e.getY());
                }
                public void mouseDragged(MouseEvent e) { game.touchMove(e.getX(), e.getY()); }
                public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) game.touchUp(e.getX(), e.getY());
                }
            };
            canvas.addMouseListener(m);
            canvas.addMouseMotionListener(m);
            canvas.addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE: game.key(Key.BACK); break;
                        case KeyEvent.VK_D: game.key(Key.DEBUG); break;
                        case KeyEvent.VK_UP: game.key(Key.UP); break;
                        case KeyEvent.VK_DOWN: game.key(Key.DOWN); break;
                        case KeyEvent.VK_ENTER:
                        case KeyEvent.VK_SPACE: game.key(Key.SELECT); break;
                        default: break;
                    }
                }
            });
            canvas.setFocusable(true);
        }

        void run() {
            frame.setVisible(true);
            canvas.createBufferStrategy(2);
            canvas.requestFocus();
            running = true;
            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = Math.min(0.05f, (now - last) / 1e9f);
                last = now;
                game.update(dt);
                render();
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
        }

        private void render() {
            BufferStrategy bs = canvas.getBufferStrategy();
            if (bs == null) return;
            do {
                do {
                    Graphics2D screen = (Graphics2D) bs.getDrawGraphics();
                    int cw = canvas.getWidth(), ch = canvas.getHeight();
                    // additive blend fails on some accelerated surfaces; use an offscreen buffer
                    if (backbuffer == null || backbuffer.getWidth() != cw || backbuffer.getHeight() != ch) {
                        backbuffer = new BufferedImage(Math.max(1, cw), Math.max(1, ch), BufferedImage.TYPE_INT_RGB);
                    }
                    AwtGfx real = new AwtGfx(backbuffer.createGraphics(), true);
                    if (LayerTrace.enabled) {
                        float[] box = Game.letterbox(cw, ch);
                        LayerTrace.beginFrame(new java.awt.geom.AffineTransform(box[0], 0, 0, box[0], box[1], box[2]));
                        TraceGfx traced = new TraceGfx(real);
                        game.render(traced, cw, ch);
                        LayerTrace.endFrame();
                    } else {
                        game.render(real, cw, ch);
                    }
                    real.dispose();
                    screen.drawImage(backbuffer, 0, 0, null);
                    screen.dispose();
                } while (bs.contentsRestored());
                bs.show();
            } while (bs.contentsLost());
        }
    }
}
