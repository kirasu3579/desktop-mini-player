package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 唤起宿主的防死锁探针。
 *
 * <p><b>为什么需要它</b>：1.0.30 在用户机器上把宿主整个卡死过。宿主自己写的
 * {@code safe-mode-session.properties} 是这样的：</p>
 * <pre>
 * failure.kind=TERMINATED_HANG
 * failure.pluginId=com.miniplayer.desktop-mini-player
 * outcome=UNRESPONSIVE
 * </pre>
 * <p>根因是唤起链路跑在 AWT EDT 上，而它要遍历全进程窗口并读标题；
 * {@code GetWindowTextW} 对非本线程拥有的窗口会 {@code SendMessage(WM_GETTEXT)}
 * 并同步等待对方线程处理，宿主里 Compose/Skiko 的辅助帧、Tray、IME 宿主各由别的
 * 线程托管，互相等就成了死锁。日志停在 {@code dumpOwnFrames} 最后一行，
 * 24 秒后宿主判定 hang。</p>
 *
 * <p>修法两条，这个探针各守一条：</p>
 * <ul>
 *   <li>标题改读 {@code InternalGetWindowText}（不发消息、不阻塞）；</li>
 *   <li>{@code openHostWindow()} 只负责起后台线程，EDT 上必须立刻返回。</li>
 * </ul>
 *
 * <p>断言方式：把调用放在 EDT 上跑，用 watch dog 计时。如果哪天有人把链路改回
 * 同步执行，这里会直接超时失败，而不是等用户报告宿主卡死。</p>
 */
public final class ActivateProbe {

    /** EDT 上单次点击调度的上限：只起线程的话应该是亚毫秒级，给足余量。 */
    private static final long EDT_BUDGET_MS = 200L;

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0
                ? "PASS: activate probe all green"
                : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        check("WindowLayer 可用", WindowLayer.available());

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
        Thread.sleep(300);

        long selfHwnd = WindowLayer.hwndOf(w);
        System.out.println("self hwnd = 0x" + Long.toHexString(selfHwnd));

        // 1) 直接调 HostWindow.activate：在探针进程里没有宿主主窗（本进程只有这个
        //    JWindow，没有 SunAwtFrame），所以它应该安静地返回 false 且不抛。
        //    这正是"用户把主界面彻底关掉后点封面"该有的行为：什么都不做。
        boolean activated;
        try {
            activated = HostWindow.activate(selfHwnd);
            System.out.println("HostWindow.activate -> " + activated);
            check("找不到主窗时安静返回", !activated);
        } catch (Throwable t) {
            System.out.println("HostWindow.activate 抛了: " + t);
            check("找不到主窗时安静返回", false);
        }

        // 2) 核心断言：EDT 上调 openHostWindow 必须立刻返回。
        //    用一个 CountDownLatch 在 EDT 里跑，主线程等它 —— 等不到就是被阻塞了。
        Method open = MiniPlayerWindow.class.getDeclaredMethod("openHostWindow");
        open.setAccessible(true);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        long[] costMs = new long[1];
        Throwable[] err = new Throwable[1];
        javax.swing.SwingUtilities.invokeLater(() -> {
            long t0 = System.nanoTime();
            try {
                open.invoke(w);
            } catch (InvocationTargetException e) {
                err[0] = e.getCause() == null ? e : e.getCause();
            } catch (Throwable t) {
                err[0] = t;
            } finally {
                costMs[0] = (System.nanoTime() - t0) / 1_000_000L;
                done.countDown();
            }
        });
        boolean returned = done.await(EDT_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!returned) {
            // 这里不 System.exit(2) 直接退：先让断言机制把失败原因打出来，
            // 否则 CI 里只看到非零退出码、看不到是哪条挂了
            System.out.println("FAIL: activate blocked EDT (> " + EDT_BUDGET_MS + "ms)");
            failures++;
        } else {
            System.out.println("EDT 上 openHostWindow 耗时 = " + costMs[0] + "ms");
            check("EDT 上唤起宿主立即返回", costMs[0] < EDT_BUDGET_MS);
            check("EDT 上调用不抛异常", err[0] == null);
            if (err[0] != null) {
                System.out.println("  异常: " + err[0]);
            }
        }

        // 3) 连点封面不该堆积线程：第二次点击在第一次还没跑完时应被 AtomicBoolean 挡掉，
        //    但无论挡没挡住，EDT 都不能因此变慢。
        long t0 = System.nanoTime();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                open.invoke(w);
                open.invoke(w);
            } catch (Throwable ignored) {
                // 并发路径下这里失败不影响"没阻塞"这个结论
            }
        });
        long burstCost = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("连点两次封面（EDT 上同步跑完）总耗时 = " + burstCost + "ms");
        check("连点封面不阻塞 EDT", burstCost < EDT_BUDGET_MS);

        // 给后台线程一点时间收尾，避免它和后面的 dispose 抢
        Thread.sleep(600);

        w.setVisible(false);
        w.dispose();
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
