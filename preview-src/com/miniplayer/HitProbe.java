package com.miniplayer;

import javax.swing.SwingUtilities;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 命中判定探针：不开宿主，直接构造窗口并对 hitButton / hitProgressBar 求值，
 * 用于确认坐标换算（含 DPI 缩放）与按钮热区是否自洽。
 *
 * <p>只有一种尺寸（高度对齐任务栏），所以所有坐标都从 {@code currentLayout()}
 * 取，不再有"普通 / 紧凑"两种模式。</p>
 *
 * <p>回归重点：歌词文字区<b>不能</b>被判成播控三键。1.0.28 就是因为
 * {@code candidates()} 里多了一个 {@code raw * scale} 候选（本机 1.5 倍），
 * 点歌词区 x=166 被放大成 249 正好贴到 playCx，用户点一下歌词就暂停了。</p>
 */
public final class HitProbe {

    private static int failures;

    private HitProbe() {
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                dumpEnvironment();
                PluginConfig cfg = new PluginConfig();
                cfg.themeMode = PluginConfig.MODE_LIGHT;
                Constructor<MiniPlayerWindow> ctor = MiniPlayerWindow.class
                        .getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
                ctor.setAccessible(true);
                MiniPlayerWindow w = ctor.newInstance(null, cfg);

                Layout l = currentLayout(w);
                System.out.println("window size = " + w.getWidth() + "x" + w.getHeight());
                System.out.println("insets       = " + w.getInsets());

                Method hitButton = MiniPlayerWindow.class.getDeclaredMethod("hitButton", Point.class);
                hitButton.setAccessible(true);
                Method hitBar = MiniPlayerWindow.class
                        .getDeclaredMethod("hitProgressBar", Point.class);
                hitBar.setAccessible(true);

                int[][] buttons = {
                        {l.prevCx, l.ctrlY, 1},
                        {l.playCx, l.ctrlY, 2},
                        {l.nextCx, l.ctrlY, 3},
                };
                String[] names = {"HIT_NONE", "PREV", "PLAY", "NEXT"};
                for (int[] b : buttons) {
                    Point p = new Point(b[0] + l.shadow, b[1] + l.shadow);
                    int got = (int) hitButton.invoke(w, p);
                    System.out.println("center(" + p.x + "," + p.y + ") expect " + names[b[2]]
                            + " -> " + names[got]
                            + (got == b[2] ? "  OK" : "  <<< MISMATCH"));
                    check("按钮中心 " + names[b[2]], got == b[2]);
                }

                // 歌词文字区的点必须判成 HIT_NONE：这里是 1.0.28 误触播控的现场。
                // textRight 是文字区右边界，它到 prevCx 只有 16px，最容易被热区吃掉。
                for (int[] pt : new int[][]{
                        {l.textX + 20, l.ctrlY},
                        {l.textX + 60, l.ctrlY},
                        {l.textRight - 8, l.ctrlY},
                        {l.textRight, l.ctrlY},
                        {l.textX + 20, 4},
                }) {
                    Point p = new Point(pt[0] + l.shadow, pt[1] + l.shadow);
                    int got = (int) hitButton.invoke(w, p);
                    System.out.println("text(" + p.x + "," + p.y + ") -> " + names[got]);
                    check("文字区不是播控键 (" + pt[0] + "," + pt[1] + ")", got == 0);
                }

                for (int dx : new int[]{0, l.barW / 2, l.barW}) {
                    Point p = new Point(l.barX + dx + l.shadow, l.barY + l.shadow);
                    System.out.println("bar(" + p.x + "," + p.y + ") -> "
                            + hitBar.invoke(w, p));
                }

                w.dispose();
            } catch (Exception e) {
                e.printStackTrace();
                failures++;
            }
        });
        System.out.println(failures == 0 ? "PASS: hit probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static Layout currentLayout(MiniPlayerWindow w) throws Exception {
        Method m = MiniPlayerWindow.class.getDeclaredMethod("currentLayout");
        m.setAccessible(true);
        return (Layout) m.invoke(w);
    }

    private static void dumpEnvironment() {
        Toolkit kit = Toolkit.getDefaultToolkit();
        System.out.println("screenResolution = " + kit.getScreenResolution());
        System.out.println("screenSize       = " + kit.getScreenSize());
        System.out.println("uiScale prop     = " + System.getProperty("sun.java2d.uiScale"));
        System.out.println("dpiAware prop    = " + System.getProperty("sun.java2d.dpiaware"));
        System.out.println("gdkScale prop    = " + System.getProperty("sun.java2d.uiScale.enabled"));
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice dev = env.getDefaultScreenDevice();
        GraphicsConfiguration gc = dev.getDefaultConfiguration();
        System.out.println("gc bounds        = " + gc.getBounds());
        System.out.println("maxWindowBounds  = " + env.getMaximumWindowBounds());
        System.out.println("defaultTransform = " + gc.getDefaultTransform().getScaleX() + "x"
                + gc.getDefaultTransform().getScaleY());
    }
}
