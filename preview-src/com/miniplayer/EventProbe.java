package com.miniplayer;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 对照探针：同一位置、同一尺寸，用四种窗口配置分别接一次 Robot 点击，
 * 定位「事件进不了窗口」到底由 shape 还是 per-pixel 透明引起。
 *
 * <p>mode 取值：plain（不透明 JWindow）/ translucent（透明无 shape）/
 * shaped（不透明 + setShape）/ shapedTranslucent（透明 + setShape，与 MiniPlayerWindow 一致）
 */
public final class EventProbe {

    private EventProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "shapedTranslucent";
        int holdMs = args.length > 1 ? Integer.parseInt(args[1]) : 700;

        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("=== mode = " + mode + " ===");
        System.out.println("defaultTransform = " + gc.getDefaultTransform().getScaleX());

        List<String> events = new ArrayList<>();
        JWindow[] holder = new JWindow[1];

        SwingUtilities.invokeAndWait(() -> {
            JWindow w = new JWindow(gc);
            holder[0] = w;
            boolean translucent = mode.contains("ranslucent");
            if (translucent) {
                w.setBackground(new Color(0, 0, 0, 0));
            }
            w.setSize(476, 92);
            if (mode.contains("shaped")) {
                w.setShape(new RoundRectangle2D.Double(0, 0, 476, 92, 24, 24));
            }
            w.setFocusableWindowState(false);
            w.setAlwaysOnTop(true);
            w.setLocation(200, 200);
            w.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    events.add("press  point=" + e.getPoint()
                            + " abs=" + e.getLocationOnScreen());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    events.add("release point=" + e.getPoint()
                            + " clickCount=" + e.getClickCount());
                }
            });
            w.setContentPane(new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(new Color(0x8F, 0xB8, 0x6A, 250));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            });
            w.setVisible(true);
            w.toFront();
        });

        Thread.sleep(holdMs);

        Rectangle bounds = holder[0].getBounds();
        Robot robot = new Robot();
        Point target = new Point(bounds.x + 204, bounds.y + 46);
        System.out.println("bounds = " + bounds + "  click at " + target);
        robot.mouseMove(target.x, target.y);
        robot.waitForIdle();
        Thread.sleep(150);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        Thread.sleep(60);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        Thread.sleep(holdMs);

        System.out.println("events = " + events.size());
        for (String s : events) {
            System.out.println("  " + s);
        }
        if (events.isEmpty()) {
            System.out.println("  (没有收到任何鼠标事件)");
        }

        SwingUtilities.invokeAndWait(() -> {
            holder[0].setVisible(false);
            holder[0].dispose();
        });
        System.exit(0);
    }
}
