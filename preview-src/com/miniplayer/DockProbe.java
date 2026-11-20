package com.miniplayer;

import javax.swing.SwingUtilities;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 停靠探针：验证任务栏矩形探测、停靠目标矩形、布局切换与幂等性。
 *
 * <p>不开宿主，直接构造窗口后反射调用内部方法。任何一条断言失败都会
 * 让退出码变成 1，便于脚本化回归。</p>
 */
public final class DockProbe {

    private static int failures;

    private DockProbe() {
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                run();
            } catch (Throwable t) {
                failures++;
                t.printStackTrace();
            }
        });
        System.out.println(failures == 0 ? "PASS: dock probe all green"
                : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        System.out.println("screen bounds   = " + screen);
        System.out.println("defaultTransform= " + gc.getDefaultTransform().getScaleX()
                + "x" + gc.getDefaultTransform().getScaleY());

        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = newWindow(cfg);

        Method taskbarRect = MiniPlayerWindow.class.getDeclaredMethod("taskbarRect");
        taskbarRect.setAccessible(true);
        Method dockTargetRect = MiniPlayerWindow.class
                .getDeclaredMethod("dockTargetRect", Rectangle.class);
        dockTargetRect.setAccessible(true);
        Method dockToTaskbar = MiniPlayerWindow.class
                .getDeclaredMethod("dockToTaskbar", Rectangle.class);
        dockToTaskbar.setAccessible(true);
        Method undockIfDocked = MiniPlayerWindow.class.getDeclaredMethod("undockIfDocked");
        undockIfDocked.setAccessible(true);
        Method isDocked = MiniPlayerWindow.class.getDeclaredMethod("isDocked");
        isDocked.setAccessible(true);

        Rectangle bar = (Rectangle) taskbarRect.invoke(w);
        System.out.println("taskbarRect     = " + bar);
        if (bar == null) {
            System.out.println("SKIP: 当前环境没有可见任务栏，只校验 null 分支");
            check("未停靠时 isDocked=false", Boolean.FALSE.equals(isDocked.invoke(w)));
            w.dispose();
            return;
        }

        check("taskbarRect 高度 > 0", bar.height > 0);
        check("taskbarRect 落在屏幕内", screen.contains(bar));

        Rectangle beforeSize = w.getBounds();
        System.out.println("size before     = " + beforeSize.width + "x" + beforeSize.height);

        Rectangle target = (Rectangle) dockTargetRect.invoke(w, bar);
        System.out.println("dockTargetRect  = " + target);
        check("dockTarget 落在任务栏内", bar.contains(target));

        dockToTaskbar.invoke(w, bar);
        Rectangle docked = w.getBounds();
        System.out.println("docked size     = " + docked.width + "x" + docked.height);
        // 尺寸只有一种：停靠不改变大小，只改变位置
        check("停靠前后尺寸相同", docked.width == beforeSize.width
                && docked.height == beforeSize.height);
        check("停靠后高度等于任务栏高", docked.height == bar.height);
        check("停靠后 isDocked=true", Boolean.TRUE.equals(isDocked.invoke(w)));
        check("停靠后位置等于目标位置", docked.x == target.x && docked.y == target.y);

        undockIfDocked.invoke(w);
        Rectangle restored = w.getBounds();
        System.out.println("restored size   = " + restored.width + "x" + restored.height);
        check("抽出后尺寸不变", restored.width == beforeSize.width
                && restored.height == beforeSize.height);
        check("抽出后 isDocked=false", Boolean.FALSE.equals(isDocked.invoke(w)));

        // 幂等：已经取消停靠后再调一次不应变化
        undockIfDocked.invoke(w);
        Rectangle again = w.getBounds();
        check("重复抽出保持尺寸不变", again.width == beforeSize.width
                && again.height == beforeSize.height);

        // 启动恢复：上次停靠 + 记住了 x，重开时必须回到那个 x，而不是任务栏最左边。
        // 构造窗口时 getX() 还是 0，直接用它会夹到 bar.x —— 这就是"重开跑到最左"的成因。
        Rectangle bar2 = (Rectangle) taskbarRect.invoke(w);
        PluginConfig restoreCfg = new PluginConfig();
        restoreCfg.themeMode = PluginConfig.MODE_LIGHT;
        restoreCfg.docked = true;
        restoreCfg.windowX = bar2.x + bar2.width / 2;
        restoreCfg.windowY = bar2.y;
        MiniPlayerWindow relaunched = newWindow(restoreCfg);
        int wantX = Math.max(bar2.x,
                Math.min(bar2.x + bar2.width - relaunched.getWidth(), restoreCfg.windowX));
        System.out.println("restore x: want=" + wantX + " got=" + relaunched.getX());
        check("重开后回到上次的横向位置", Math.abs(relaunched.getX() - wantX) <= 1);
        check("重开后仍在任务栏内", bar2.contains(relaunched.getBounds()));
        check("重开后未被夹到最左边", relaunched.getX() > bar2.x + 1
                || restoreCfg.windowX - bar2.x <= 1);
        relaunched.dispose();

        // 首次就以停靠态启动（没有任何记忆坐标）也不能贴最左，应落在任务栏中部
        PluginConfig freshCfg = new PluginConfig();
        freshCfg.themeMode = PluginConfig.MODE_LIGHT;
        freshCfg.docked = true;
        MiniPlayerWindow fresh = newWindow(freshCfg);
        System.out.println("fresh docked x  = " + fresh.getX() + " bar=" + bar2);
        check("首次停靠不贴最左边", fresh.getX() > bar2.x + 1);
        check("首次停靠仍在任务栏内", bar2.contains(fresh.getBounds()));
        fresh.dispose();

        w.dispose();
    }

    private static MiniPlayerWindow newWindow(PluginConfig cfg) throws Exception {
        Constructor<MiniPlayerWindow> ctor = MiniPlayerWindow.class
                .getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, cfg);
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + label);
        if (!ok) {
            failures++;
        }
    }
}
