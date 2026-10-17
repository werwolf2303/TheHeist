package heist.desktop.debug;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/** Debug window: layer list (toggle on/off per group or image) + 3D exploded-stack view. */
public final class LayerWindow {
    private static Window anchor;
    private static LayerWindow instance;

    private final JFrame frame = new JFrame("Layers");
    private final View3D view = new View3D();
    private final JPanel list = new JPanel();
    private final JScrollPane listScroll;
    private String listSignature = "";
    private final Timer timer;

    private LayerWindow() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) { LayerTrace.enabled = false; close(); }
        });
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(280, 600));
        listScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        final JSlider spacing = new JSlider(0, 60, view.spacing);
        spacing.setPreferredSize(new Dimension(110, 20));
        spacing.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) { view.spacing = spacing.getValue(); }
        });
        final JCheckBox ghosts = new JCheckBox("Switched-off layers as ghosts", true);
        ghosts.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { view.ghosts = ghosts.isSelected(); }
        });
        final JCheckBox spin = new JCheckBox("Spin");
        spin.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { view.spin = spin.isSelected(); }
        });
        final JCheckBox outlines = new JCheckBox("Outlines", true);
        outlines.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { view.outlines = outlines.isSelected(); }
        });
        JButton reset = new JButton("Reset view");
        reset.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { view.resetView(); spacing.setValue(view.spacing); }
        });
        JButton all = new JButton("All on");
        all.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { LayerTrace.showAll(); listSignature = ""; }
        });
        controls.add(new JLabel("Depth"));
        controls.add(spacing);
        controls.add(outlines);
        controls.add(ghosts);
        controls.add(spin);
        controls.add(reset);
        controls.add(all);

        JPanel right = new JPanel(new BorderLayout());
        right.add(controls, BorderLayout.NORTH);
        right.add(view, BorderLayout.CENTER);
        JLabel hint = new JLabel("  drag: rotate   wheel: zoom   double click: reset   click a layer name in the list to highlight it");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        right.add(hint, BorderLayout.SOUTH);

        frame.setLayout(new BorderLayout());
        frame.add(listScroll, BorderLayout.WEST);
        frame.add(right, BorderLayout.CENTER);
        frame.setSize(1000, 700);

        timer = new Timer(33, new ActionListener() {
            public void actionPerformed(ActionEvent e) { tick(); }
        });
    }

    // ---- life cycle (called from the game thread) -------------------------------------------------------------

    public static void setAnchor(Window w) { anchor = w; }

    /** Opens or raises the window next to the game. */
    public static void show() {
        if (GraphicsEnvironment.isHeadless()) return;
        LayerTrace.enabled = true;
        SwingUtilities.invokeLater(new Runnable() { public void run() { open(); } });
    }

    public static boolean isOpen() { return LayerTrace.enabled; }

    private static void open() {
        if (instance == null) instance = new LayerWindow();
        LayerWindow w = instance;
        if (!w.frame.isVisible()) {
            if (anchor != null) {
                w.frame.setLocation(anchor.getX() + anchor.getWidth() + 8, anchor.getY());
                w.frame.setSize(Math.max(900, w.frame.getWidth()), Math.max(anchor.getHeight(), 600));
            }
            w.frame.setVisible(true);
            w.timer.start();
        } else {
            w.frame.toFront();
        }
    }

    private static void close() {
        if (instance != null) {
            instance.timer.stop();
            instance.frame.setVisible(false);
        }
    }

    private void tick() {
        refreshList();
        view.repaint();
    }

    // ---- the layer list ---------------------------------------------------------------------------------------

    private void refreshList() {
        List<LayerTrace.Entry> frameEntries = LayerTrace.frame();
        // group -> name -> count, in order of first drawing
        final Map<String, Map<String, Integer>> groups = new LinkedHashMap<String, Map<String, Integer>>();
        final Map<String, BufferedImage> thumbs = new LinkedHashMap<String, BufferedImage>();
        StringBuilder sig = new StringBuilder();
        for (LayerTrace.Entry e : frameEntries) {
            Map<String, Integer> names = groups.get(e.group);
            if (names == null) { names = new LinkedHashMap<String, Integer>(); groups.put(e.group, names); }
            Integer c = names.get(e.name);
            names.put(e.name, c == null ? 1 : c + 1);
            if (!thumbs.containsKey(e.name)) thumbs.put(e.name, e.img);
        }
        for (Map.Entry<String, Map<String, Integer>> g : groups.entrySet()) {
            sig.append('[').append(g.getKey()).append(LayerTrace.isGroupHidden(g.getKey())).append(']');
            for (Map.Entry<String, Integer> n : g.getValue().entrySet()) {
                sig.append(n.getKey()).append(n.getValue()).append(LayerTrace.isNameHidden(n.getKey())).append(';');
            }
        }
        String s = sig.toString();
        if (s.equals(listSignature)) return;
        listSignature = s;
        final int scrollY = listScroll.getVerticalScrollBar().getValue();
        list.removeAll();
        for (final Map.Entry<String, Map<String, Integer>> g : groups.entrySet()) {
            final String group = g.getKey();
            final JCheckBox gb = new JCheckBox("<html><b>" + group + "</b></html>", !LayerTrace.isGroupHidden(group));
            gb.setAlignmentX(Component.LEFT_ALIGNMENT);
            gb.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { LayerTrace.setGroupHidden(group, !gb.isSelected()); listSignature = ""; }
            });
            list.add(gb);
            for (final Map.Entry<String, Integer> n : g.getValue().entrySet()) {
                final String name = n.getKey();
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.add(Box.createHorizontalStrut(18));
                final JCheckBox cb = new JCheckBox("", !LayerTrace.isNameHidden(name));
                cb.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { LayerTrace.setNameHidden(name, !cb.isSelected()); listSignature = ""; }
                });
                row.add(cb);
                BufferedImage t = thumbs.get(name);
                if (t != null) row.add(new JLabel(new ImageIcon(thumbnail(t, 28))));
                JLabel label = new JLabel(name + (n.getValue() > 1 ? "  x" + n.getValue() : ""));
                label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
                label.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) { view.highlight = name.equals(view.highlight) ? null : name; }
                });
                row.add(label);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
                list.add(row);
            }
            list.add(Box.createVerticalStrut(6));
        }
        list.revalidate();
        list.repaint();
        SwingUtilities.invokeLater(new Runnable() { public void run() { listScroll.getVerticalScrollBar().setValue(scrollY); } });
    }

    private static BufferedImage thumbnail(BufferedImage src, int box) {
        float k = Math.min(box / (float) src.getWidth(), box / (float) src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * k)), h = Math.max(1, Math.round(src.getHeight() * k));
        BufferedImage out = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // checkerboard so transparent art is visible
        for (int y = 0; y < box; y += 7) for (int x = 0; x < box; x += 7) {
            g.setColor(((x / 7 + y / 7) & 1) == 0 ? new Color(70, 70, 70) : new Color(95, 95, 95));
            g.fillRect(x, y, 7, 7);
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, (box - w) / 2, (box - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    // ---- the 3D side view ---------------------------------------------------------------------------------------

    private static final class View3D extends JPanel {
        volatile int spacing = 14;
        volatile boolean ghosts = true, spin, outlines = true;
        volatile String highlight;
        private double yaw = Math.toRadians(-55), pitch = Math.toRadians(12), zoom = 1.0;
        private int lastX, lastY;

        View3D() {
            setBackground(new Color(24, 26, 32));
            MouseAdapter m = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    lastX = e.getX();
                    lastY = e.getY();
                    if (e.getClickCount() == 2) resetView();
                }
                public void mouseDragged(MouseEvent e) {
                    yaw += (e.getX() - lastX) * 0.01;
                    pitch = Math.max(-1.45, Math.min(1.45, pitch + (e.getY() - lastY) * 0.01));
                    lastX = e.getX();
                    lastY = e.getY();
                }
                public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom = Math.max(0.2, Math.min(5.0, zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1)));
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        void resetView() {
            yaw = Math.toRadians(-55);
            pitch = Math.toRadians(12);
            zoom = 1.0;
            spacing = 14;
        }

        private final class Placed {
            LayerTrace.Entry e;
            double z, depth;
            int index;
        }

        // view state of the frame being painted
        private double cosY, sinY, cosP, sinP, scale, cx, cy;
        private static final double PERSPECTIVE = 1500.0;

        /** Perspective projection of a point of the layer stack: logical (x, y) of the screen at depth z. */
        private void project(double x, double y, double z, double[] out) {
            double X = x - 160, Y = y - 240;
            double x1 = X * cosY + z * sinY;
            double z1 = -X * sinY + z * cosY;
            double y1 = Y * cosP - z1 * sinP;
            double z2 = Y * sinP + z1 * cosP;                      // towards the viewer
            double f = PERSPECTIVE / Math.max(50.0, PERSPECTIVE - z2);
            out[0] = cx + scale * x1 * f;
            out[1] = cy + scale * y1 * f;
            out[2] = z2;
        }

        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setPaint(new GradientPaint(0, 0, new Color(38, 42, 54), 0, getHeight(), new Color(16, 17, 22)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (spin) yaw += 0.012;

            List<LayerTrace.Entry> entries = LayerTrace.frame();
            int n = entries.size();
            cosY = Math.cos(yaw);
            sinY = Math.sin(yaw);
            cosP = Math.cos(pitch);
            sinP = Math.sin(pitch);
            scale = Math.min(getWidth() / 560.0, getHeight() / 760.0) * zoom;
            cx = getWidth() / 2.0;
            cy = getHeight() / 2.0;
            double zMid = (n - 1) * spacing / 2.0;
            List<Placed> placed = new ArrayList<Placed>();
            double[] c = new double[3];
            for (int i = 0; i < n; i++) {
                LayerTrace.Entry e = entries.get(i);
                if (e.hidden && !ghosts) continue;
                Placed pl = new Placed();
                pl.e = e;
                pl.index = i;
                pl.z = i * spacing - zMid;
                // sort by centre-depth of each layer's own rectangle
                Point2D mid = e.tx.transform(new Point2D.Double(e.img.getWidth() / 2.0, e.img.getHeight() / 2.0), null);
                project(mid.getX(), mid.getY(), pl.z, c);
                pl.depth = c[2];
                placed.add(pl);
            }
            Collections.sort(placed, new Comparator<Placed>() {
                public int compare(Placed a, Placed b) {
                    int k = Double.compare(a.depth, b.depth);
                    return k != 0 ? k : (a.index - b.index);
                }
            });
            for (Placed pl : placed) paintLayer(g, pl);
            g.dispose();
            drawLabels(g0, n);
        }

        /** Draws the layer as an affinely-mapped triangle grid (close to exact perspective). */
        private void paintLayer(Graphics2D g, Placed pl) {
            LayerTrace.Entry e = pl.e;
            int w = e.img.getWidth(), h = e.img.getHeight();
            int nx = Math.max(1, Math.min(6, w / 40)), ny = Math.max(1, Math.min(8, h / 40));
            double[][][] src = new double[ny + 1][nx + 1][];
            double[][][] dst = new double[ny + 1][nx + 1][];
            Point2D.Double pt = new Point2D.Double();
            for (int j = 0; j <= ny; j++) {
                for (int i = 0; i <= nx; i++) {
                    double u = w * i / (double) nx, v = h * j / (double) ny;
                    pt.setLocation(u, v);
                    Point2D logical = e.tx.transform(pt, null);
                    double[] o = new double[3];
                    project(logical.getX(), logical.getY(), pl.z, o);
                    src[j][i] = new double[] { u, v };
                    dst[j][i] = o;
                }
            }
            Graphics2D gl = (Graphics2D) g.create();
            if (e.hidden) gl.setComposite(AlphaComposite.SrcOver.derive(0.10f));
            else gl.setComposite(e.composite);
            gl.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    triangle(gl, e.img, src[j][i], src[j][i + 1], src[j + 1][i], dst[j][i], dst[j][i + 1], dst[j + 1][i]);
                    triangle(gl, e.img, src[j][i + 1], src[j + 1][i + 1], src[j + 1][i], dst[j][i + 1], dst[j + 1][i + 1], dst[j + 1][i]);
                }
            }
            gl.dispose();
            if (outlines || e.name.equals(highlight)) {
                Path2D.Double q = new Path2D.Double();
                q.moveTo(dst[0][0][0], dst[0][0][1]);
                for (int i = 1; i <= nx; i++) q.lineTo(dst[0][i][0], dst[0][i][1]);
                for (int j = 1; j <= ny; j++) q.lineTo(dst[j][nx][0], dst[j][nx][1]);
                for (int i = nx - 1; i >= 0; i--) q.lineTo(dst[ny][i][0], dst[ny][i][1]);
                for (int j = ny - 1; j >= 0; j--) q.lineTo(dst[j][0][0], dst[j][0][1]);
                q.closePath();
                boolean hl = e.name.equals(highlight);
                g.setStroke(new BasicStroke(hl ? 2.5f : 1f));
                g.setColor(hl ? new Color(255, 200, 40) : e.hidden ? new Color(255, 80, 80, 70) : new Color(255, 255, 255, 55));
                g.draw(q);
            }
        }

        private void triangle(Graphics2D g, BufferedImage img, double[] s0, double[] s1, double[] s2, double[] d0, double[] d1, double[] d2) {
            // affine map from image-pixel triangle to projected screen triangle
            double sx1 = s1[0] - s0[0], sy1 = s1[1] - s0[1], sx2 = s2[0] - s0[0], sy2 = s2[1] - s0[1];
            double det = sx1 * sy2 - sx2 * sy1;
            if (Math.abs(det) < 1e-9) return;
            double dx1 = d1[0] - d0[0], dy1 = d1[1] - d0[1], dx2 = d2[0] - d0[0], dy2 = d2[1] - d0[1];
            double m00 = (dx1 * sy2 - dx2 * sy1) / det, m01 = (dx2 * sx1 - dx1 * sx2) / det;
            double m10 = (dy1 * sy2 - dy2 * sy1) / det, m11 = (dy2 * sx1 - dy1 * sx2) / det;
            double m02 = d0[0] - m00 * s0[0] - m01 * s0[1], m12 = d0[1] - m10 * s0[0] - m11 * s0[1];
            // clip to a slightly grown triangle to close seams between neighbours
            double gx = (d0[0] + d1[0] + d2[0]) / 3, gy = (d0[1] + d1[1] + d2[1]) / 3;
            Path2D.Double tri = new Path2D.Double();
            double[][] ds = { d0, d1, d2 };
            for (int i = 0; i < 3; i++) {
                double px = ds[i][0] - gx, py = ds[i][1] - gy, len = Math.max(1e-6, Math.hypot(px, py));
                double qx = ds[i][0] + px / len * 0.7, qy = ds[i][1] + py / len * 0.7;
                if (i == 0) tri.moveTo(qx, qy); else tri.lineTo(qx, qy);
            }
            tri.closePath();
            Graphics2D gt = (Graphics2D) g.create();
            gt.clip(tri);
            try {
                gt.drawImage(img, new AffineTransform(m00, m10, m01, m11, m02, m12), null);
            } catch (RuntimeException ex) {
                gt.setComposite(AlphaComposite.SrcOver);
                gt.drawImage(img, new AffineTransform(m00, m10, m01, m11, m02, m12), null);
            }
            gt.dispose();
        }

        private void drawLabels(Graphics g0, int n) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(200, 205, 215));
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            g.drawString(n + " layers in this frame (back to front: first drawn = rearmost)", 10, getHeight() - 24);
            if (highlight != null) {
                g.setColor(new Color(255, 200, 40));
                g.drawString("highlighted: " + highlight, 10, getHeight() - 8);
            }
            g.dispose();
        }
    }
}
