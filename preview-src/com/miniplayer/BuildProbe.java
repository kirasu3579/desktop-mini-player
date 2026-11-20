package com.miniplayer;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 建窗链路探针：用<b>非 EDT 线程</b>复现插件 {@code start()} 的执行环境，
 * 验证 {@code ensureCreated} 是否真的同步返回了窗口实例。
 *
 * <p>这是「日志说 window not created」那个 bug 的回归测试：
 * 修之前这里必须返回 null，修之后必须返回非 null 且 isShowing 为 true。</p>
 */
public final class BuildProbe {

    public static void main(String[] args) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            System.out.println("worker onEdt=" + SwingUtilities.isEventDispatchThread());
            PluginConfig cfg = new PluginConfig();
            cfg.themeMode = PluginConfig.MODE_LIGHT;
            MiniPlayerWindow w = MiniPlayerWindow.ensureCreated(cfg);
            System.out.println("ensureCreated returned " + (w == null ? "null" : w.getClass().getSimpleName()));
            if (w != null) {
                w.showWindow();
                result.set(w);
            }
        }, "pf4j-fake-start");
        worker.start();
        worker.join(8000);
        System.out.println("worker alive after join = " + worker.isAlive());

        Thread.sleep(800);
        Object w = result.get();
        if (w == null) {
            System.out.println("FAIL: 非 EDT 调用时窗口没建出来（就是用户看到的\"看不到窗口\"）");
            System.exit(2);
        }
        MiniPlayerWindow win = (MiniPlayerWindow) w;
        System.out.println("PASS: showing=" + win.isShowing() + " visible=" + win.isVisible()
                + " at=" + win.getLocation() + " size=" + win.getSize());
        System.out.println("instance() == returned ? " + (MiniPlayerWindow.instance() == win));

        SwingUtilities.invokeAndWait(() -> {
            win.setVisible(false);
            win.dispose();
        });
        System.exit(0);
    }
}
