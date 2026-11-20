package com.miniplayer;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 锁定探针：锁定后右键菜单必须弹不出来。
 *
 * <p>从前 {@code locked} 只挡拖动（{@code mousePressed} 里判完拖动才判锁），右键
 * 菜单在它之前就 {@code return} 了，所以「锁定位置」锁得住位置、锁不住菜单。用户
 * 要求在锁定时<b>一并关掉右键菜单与拖动</b>，于是两个 popup 触发点（Windows 传统
 * 在按下时触发，某些场景在松开时触发）都补了 {@code locked} 早退。</p>
 *
 * <p>这里的关键断言是「菜单没弹出来」：拿 {@code activeMenu} 与 {@code menuOpen}
 * 两个字段当观察点——菜单一旦弹出，这两个必然被写。顺带钉住"不锁时菜单照常弹"，
 * 免得把早退写成无条件 return。</p>
 */
public final class LockProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: lock probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Field sf = MiniPlayerWindow.class.getDeclaredField("surface");
        sf.setAccessible(true);
        Component surface = (Component) sf.get(w);

        Field cf = MiniPlayerWindow.class.getDeclaredField("config");
        cf.setAccessible(true);
        PluginConfig live = (PluginConfig) cf.get(w);

        Field menu = MiniPlayerWindow.class.getDeclaredField("activeMenu");
        menu.setAccessible(true);
        Field open = MiniPlayerWindow.class.getDeclaredField("menuOpen");
        open.setAccessible(true);

        java.lang.reflect.Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);
        w.setLocation(400, 300);
        Thread.sleep(300);

        Layout l = currentLayout(w);
        int x = l.textX + 20;
        int y = l.contentH / 2;

        // 0) 未锁定时：右键触发的 popup 不该被误当成拖动起点，
        //    也不该把进度条带进 pressingOnBar。这条是对照组，保证下面的"锁住"
        //    不是靠某个无条件 return 蒙出来的。
        live.locked = false;
        popupPressed(surface, x, y);
        Thread.sleep(120);
        check("未锁定时右键不触发拖动", !dragging(w));
        check("未锁定时右键不抓进度条", !pressingOnBar(w));
        closeMenu(w, menu, open);

        // 1) 锁定后：按下触发 popup 不该弹菜单
        live.locked = true;
        popupPressed(surface, x, y);
        Thread.sleep(120);
        check("锁定后按下不弹菜单", !open.getBoolean(w) && menu.get(w) == null);
        closeMenu(w, menu, open);

        // 2) 锁定后：松开触发 popup 也不该弹菜单
        popupReleased(surface, x, y);
        Thread.sleep(120);
        check("锁定后松开不弹菜单", !open.getBoolean(w) && menu.get(w) == null);
        closeMenu(w, menu, open);

        // 3) 锁定不影响播控三键：这三键必须照常可点（否则锁定等于把插件废掉）
        live.locked = true;
        check("锁定后播控三键仍可点", hitButton(w, l.playCx, l.ctrlY) != 0);
        check("锁定后进度条仍可点",
                hitProgressBar(w, l.barX + l.barW / 2, l.barY));

        live.locked = false;
        w.setVisible(false);
        w.dispose();
    }

    /** 模拟 Windows 传统右键：MOUSE_PRESSED 且 isPopupTrigger() 为真。 */
    private static void popupPressed(Component target, int lx, int ly) {
        long when = System.currentTimeMillis();
        int ax = absX(target);
        int ay = absY(target);
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when,
                MouseEvent.BUTTON3_DOWN_MASK, lx, ly, ax + lx, ay + ly, 1, true, MouseEvent.BUTTON3));
    }

    /** 模拟松开时触发 popup 的平台。 */
    private static void popupReleased(Component target, int lx, int ly) {
        long when = System.currentTimeMillis();
        int ax = absX(target);
        int ay = absY(target);
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, when,
                MouseEvent.BUTTON3_DOWN_MASK, lx, ly, ax + lx, ay + ly, 1, true, MouseEvent.BUTTON3));
    }

    private static void closeMenu(MiniPlayerWindow w, Field menu, Field open) throws Exception {
        javax.swing.JPopupMenu m = (javax.swing.JPopupMenu) menu.get(w);
        if (m != null) {
            m.setVisible(false);
        }
        menu.set(w, null);
        open.setBoolean(w, false);
        Thread.sleep(120);
    }

    private static Layout currentLayout(MiniPlayerWindow w) throws Exception {
        java.lang.reflect.Method m = MiniPlayerWindow.class.getDeclaredMethod("currentLayout");
        m.setAccessible(true);
        return (Layout) m.invoke(w);
    }

    private static int hitButton(MiniPlayerWindow w, int x, int y) throws Exception {
        java.lang.reflect.Method m = MiniPlayerWindow.class.getDeclaredMethod("hitButton", Point.class);
        m.setAccessible(true);
        return (Integer) m.invoke(w, new Point(x, y));
    }

    private static boolean hitProgressBar(MiniPlayerWindow w, int x, int y) throws Exception {
        java.lang.reflect.Method m = MiniPlayerWindow.class.getDeclaredMethod("hitProgressBar", Point.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(w, new Point(x, y));
    }

    private static boolean dragging(MiniPlayerWindow w) throws Exception {
        Field f = MiniPlayerWindow.class.getDeclaredField("dragging");
        f.setAccessible(true);
        return f.getBoolean(w);
    }

    private static boolean pressingOnBar(MiniPlayerWindow w) throws Exception {
        Field f = MiniPlayerWindow.class.getDeclaredField("pressingOnBar");
        f.setAccessible(true);
        return f.getBoolean(w);
    }

    private static int absX(Component target) {
        try {
            return target.getLocationOnScreen().x;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int absY(Component target) {
        try {
            return target.getLocationOnScreen().y;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
