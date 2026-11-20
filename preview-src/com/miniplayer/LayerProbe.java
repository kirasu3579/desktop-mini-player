package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 窗口层级探针：验证 {@link WindowLayer} 的 FFM 绑定在这个 JVM 上真的可用。
 *
 * <p>{@link WindowLayer} 全是 {@code invokeExact} 调用，签名写错一个字节
 * （{@code JAVA_INT} 写成 {@code JAVA_LONG}）在编译期毫无问题，只在运行到那一行时
 * 抛 {@code WrongMethodTypeException}。所以在预览阶段就把每个方法都戳一遍，
 * 免得等到用户双击封面才发现"唤起没反应"。</p>
 *
 * <p>断言分两层：</p>
 * <ul>
 *   <li><b>硬断言</b>：{@code available()} 必须为真（宿主 cfg 带
 *       {@code --enable-native-access=ALL-UNNAMED}，Windows 上没理由不可用）、
 *       {@code hwndOf(this)} 必须非 0、{@code trayCovers}/{@code fullscreenForeground}
 *       必须能返回而不抛；</li>
 *   <li><b>软观测</b>：只打印不判定——层级真实性依赖当时桌面上有什么窗口，
 *       探针环境里没有全屏应用，断言"一定不是全屏"会变成对环境的假设。</li>
 * </ul>
 */
public final class LayerProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: layer probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        check("WindowLayer 可用（FFM 绑定成功）", WindowLayer.available());

        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);
        w.setLocation(400, 300);
        Thread.sleep(400);

        // 1) HWND 解析：Java 窗口 → Win32 句柄。
        //    这里最容易踩的是缩放：HWND 的矩形是物理像素，Java 的是逻辑像素，
        //    本机 1.5 倍缩放下直接比逻辑值永远匹配不上。
        long hwnd = WindowLayer.hwndOf(w);
        System.out.println("hwnd = 0x" + Long.toHexString(hwnd));
        System.out.println("displayable=" + w.isDisplayable() + " showing=" + w.isShowing()
                + " size=" + w.getSize() + " transform="
                + (w.getGraphicsConfiguration() == null ? "null"
                   : w.getGraphicsConfiguration().getDefaultTransform().getScaleX()));
        dumpOwnWindows();
        check("迷你条 HWND 解析成功", hwnd != 0L);

        // 2) 三个查询接口必须不抛
        boolean tray = WindowLayer.trayCovers(hwnd);
        boolean fs = WindowLayer.fullscreenForeground(hwnd);
        System.out.println("trayCovers=" + tray + " fullscreenForeground=" + fs);

        // 2b) trayCovers 的语义必须与真实 z 序一致：
        //     刚挂上 TOPMOST 时我们应当排在任务栏前面，判据应返回 false；
        //     摘掉 TOPMOST 后任务栏一定压上来，判据应返回 true。
        //     这是把它从"恒真"改成"真判据"之后必须守住的行为。
        check("挂 TOPMOST 时任务栏不压我们", !WindowLayer.trayCovers(hwnd));
        WindowLayer.setTopmost(hwnd, false);
        Thread.sleep(120);
        check("普通层级时任务栏压着我们", WindowLayer.trayCovers(hwnd));
        WindowLayer.setTopmost(hwnd, true);
        Thread.sleep(120);

        // 3) 层级切换：挂上再摘掉，各返回 true（实测 SetWindowPos 组合恒返回 1）
        check("可挂 TOPMOST", WindowLayer.setTopmost(hwnd, true));
        Thread.sleep(120);
        check("可摘 TOPMOST", WindowLayer.setTopmost(hwnd, false));
        Thread.sleep(120);
        check("可再挂回 TOPMOST", WindowLayer.setTopmost(hwnd, true));
        Thread.sleep(120);

        // 3b) 层内提权：这是"被任务栏挤到下面"时的唯一解。
        //     SetWindowPos(HWND_TOPMOST) 对已经挂在 TOPMOST 层的窗口是层内空操作，
        //     所以之前"判到被压住 → 重新挂 TOPMOST"会无限循环、用户看到窗口一直
        //     待在任务栏下面。raiseWithinTopmost 改用 HWND_TOP。
        int exBefore = exStyle(hwnd);
        System.out.println("exstyle before raise = 0x" + Integer.toHexString(exBefore));
        check("层内提权返回成功", WindowLayer.raiseWithinTopmost(hwnd));
        Thread.sleep(120);
        int exAfter = exStyle(hwnd);
        System.out.println("exstyle after raise  = 0x" + Integer.toHexString(exAfter));
        // WS_EX_TOPMOST = 0x8：提权不能把 TOPMOST 属性弄丢，否则就掉层了
        check("提权后仍保留 TOPMOST 属性", (exAfter & 0x8) != 0);
        check("提权后任务栏不压我们", !WindowLayer.trayCovers(hwnd));
        // 幂等：正常状态下反复提权不该让判据翻来覆去（否则就是新的每秒抖动来源）
        WindowLayer.raiseWithinTopmost(hwnd);
        WindowLayer.raiseWithinTopmost(hwnd);
        Thread.sleep(120);
        check("连续提权 3 次后仍稳定", !WindowLayer.trayCovers(hwnd));

        // 4) 宿主主窗定位：探针环境里没有宿主主窗，所以这里只要求"不抛、返回 0"
        long main = WindowLayer.findMainWindowHwnd(hwnd);
        System.out.println("host main hwnd = 0x" + Long.toHexString(main));
        check("主窗查询不抛异常", true);
        check("不会把迷你条自己当成主窗", main != hwnd);

        // 4b) 标题读取必须走 InternalGetWindowText：GetWindowTextW 对非本线程窗口会
        //     同步 SendMessage(WM_GETTEXT) 并阻塞，在 EDT 上调用直接让宿主挂死
        //     （宿主写 TERMINATED_HANG / UNRESPONSIVE 的那次事故）。
        //     这里在有限时间内读一次，超时就说明走了阻塞路径。
        long t0 = System.nanoTime();
        boolean titleRead = titleHandlesQuickly(hwnd);
        long costMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("title read cost = " + costMs + "ms");
        check("标题读取不阻塞（<200ms）", titleRead && costMs < 200);

        // 5) syncLayer 在真实窗口上跑一遍：不能抛，且窗口仍在屏幕上
        Method sync = MiniPlayerWindow.class.getDeclaredMethod("syncLayer");
        sync.setAccessible(true);
        Field last = MiniPlayerWindow.class.getDeclaredField("lastLayerCheckAt");
        last.setAccessible(true);
        last.setLong(w, 0L);
        sync.invoke(w);
        Thread.sleep(150);
        check("syncLayer 执行后窗口仍可见", w.isShowingMini());

        w.setVisible(false);
        w.dispose();
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    /** 读窗口扩展样式（{@code GWL_EXSTYLE = -20}）；{@code WS_EX_TOPMOST} 是 0x8。 */
    private static int exStyle(long hwnd) {
        try {
            java.lang.invoke.MethodHandle h = user32("GetWindowLongW",
                    java.lang.foreign.FunctionDescriptor.of(
                            java.lang.foreign.ValueLayout.JAVA_INT,
                            java.lang.foreign.ValueLayout.ADDRESS,
                            java.lang.foreign.ValueLayout.JAVA_INT));
            return (int) h.invokeExact(java.lang.foreign.MemorySegment.ofAddress(hwnd), -20);
        } catch (Throwable t) {
            System.out.println("  (exStyle 读取失败: " + t + ")");
            return 0;
        }
    }

    /** 反射调 {@code WindowLayer.titleOf}：验证它用的不是会阻塞的 {@code GetWindowTextW}。 */
    private static boolean titleHandlesQuickly(long hwnd) {
        try {
            Method m = WindowLayer.class.getDeclaredMethod("titleOf",
                    java.lang.foreign.Arena.class, long.class);
            m.setAccessible(true);
            try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                Object title = m.invoke(null, arena, hwnd);
                System.out.println("  title = [" + title + "]");
            }
            return true;
        } catch (Throwable t) {
            System.out.println("  (titleOf 调用失败: " + t + ")");
            return false;
        }
    }

    private static java.lang.invoke.MethodHandle user32(
            String name, java.lang.foreign.FunctionDescriptor fd) throws Throwable {
        java.lang.foreign.SymbolLookup lib = java.lang.foreign.SymbolLookup.libraryLookup(
                "user32", java.lang.foreign.Arena.global());
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(
                lib.find(name).orElseThrow(), fd);
    }

    /** 诊断用：列出本进程所有顶层窗口的 Java 侧身份与尺寸。 */
    private static void dumpOwnWindows() {
        for (java.awt.Window win : java.awt.Window.getWindows()) {
            System.out.println("  win " + win.getClass().getName()
                    + " displayable=" + win.isDisplayable()
                    + " showing=" + win.isShowing()
                    + " size=" + win.getSize()
                    + " at=" + win.getLocation());
        }
    }
}
