package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;

/**
 * 配置热更新探针：验证「设置面板改一项，只该动这一项对应的东西」。
 *
 * <p>这里的核心回归是显隐判定：早先 {@code applyConfigOnEdt} 写的是
 * {@code if (fresh.enabled != isShowingMini())}，于是用户用右键菜单的「隐藏迷你条」
 * 把窗口藏起来之后（此时 {@code enabled} 仍是 {@code true}），他改任何别的设置项
 * ——比如不透明度——都会被判成"该显示"，窗口自己弹回来。正确的判据是
 * 「{@code enabled} 这个开关本身翻转过」，即 {@code config.enabled != fresh.enabled}。</p>
 *
 * <p>断言四条：</p>
 * <ul>
 *   <li>手工隐藏（模拟右键菜单）后，改无关设置不会把窗口弹回来；</li>
 *   <li>{@code enabled} 由 true 变 false 时窗口隐藏；</li>
 *   <li>{@code enabled} 由 false 变 true 时窗口显示；</li>
 *   <li>改无关设置时，那一项本身仍然被应用（这里看 opacity）。</li>
 * </ul>
 */
public final class ConfigApplyProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0
                ? "PASS: config apply probe all green"
                : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        cfg.enabled = true;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        // applyConfig 是静态入口，靠 instance 找窗口；构造器不会写它，这里手动绑定
        Field inst = MiniPlayerWindow.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, w);

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        Method hide = MiniPlayerWindow.class.getDeclaredMethod("hideMini");
        hide.setAccessible(true);
        Method apply = MiniPlayerWindow.class.getDeclaredMethod("applyConfig", PluginConfig.class);
        apply.setAccessible(true);

        try {
            onEdt(() -> invoke(show, w));
            Thread.sleep(200);
            check("初始状态悬浮条可见", w.isShowingMini());

            // --- 1) 模拟右键菜单「隐藏迷你条」：enabled 仍是 true ---
            onEdt(() -> invoke(hide, w));
            Thread.sleep(150);
            check("手工隐藏后不可见", !w.isShowingMini());

            // --- 2) 只改不透明度，enabled 没变：不许弹回来 ---
            PluginConfig onlyOpacity = snapshot(cfg, true, 0.8f);
            onEdt(() -> invoke(apply, null, onlyOpacity));
            Thread.sleep(150);
            check("改无关设置不会把窗口弹回来", !w.isShowingMini());
            check("无关设置本身仍被应用（opacity=0.8）", Math.abs(cfg.opacity - 0.8f) < 1e-4f);

            // --- 3) enabled true -> false：应隐藏 ---
            onEdt(() -> invoke(show, w));
            Thread.sleep(150);
            check("重新显示后可见", w.isShowingMini());
            PluginConfig disabled = snapshot(cfg, false, 0.8f);
            onEdt(() -> invoke(apply, null, disabled));
            Thread.sleep(150);
            check("enabled 由 true 变 false 后隐藏", !w.isShowingMini());
            check("config.enabled 跟着变成 false", !cfg.enabled);

            // --- 4) enabled false -> true：应显示 ---
            PluginConfig enabled = snapshot(cfg, true, 1.0f);
            onEdt(() -> invoke(apply, null, enabled));
            Thread.sleep(200);
            check("enabled 由 false 变 true 后显示", w.isShowingMini());
            check("config.enabled 跟着变回 true", cfg.enabled);

            // --- 5) 再改无关项，刚才显示出来的窗口不该被藏起来 ---
            PluginConfig again = snapshot(cfg, true, 0.6f);
            onEdt(() -> invoke(apply, null, again));
            Thread.sleep(150);
            check("显示状态下改无关项保持可见", w.isShowingMini());
        } finally {
            onEdt(() -> {
                try {
                    w.setVisible(false);
                    w.dispose();
                } catch (Throwable ignored) {
                    // 清理失败不影响结论
                }
            });
            inst.set(null, null);
        }
    }

    /** 从现有配置拷一份，只覆盖 enabled 与 opacity，其余保持，避免 applyConfigOnEdt 误判别的项。 */
    private static PluginConfig snapshot(PluginConfig base, boolean enabled, float opacity) {
        PluginConfig c = new PluginConfig();
        c.enabled = enabled;
        c.themeMode = base.themeMode;
        c.locked = base.locked;
        c.autoHideControls = base.autoHideControls;
        c.hideWhenPaused = base.hideWhenPaused;
        c.opacity = opacity;
        return c;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void invoke(Method m, Object target, Object... args) {
        try {
            m.invoke(target, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            throw new IllegalStateException(cause);
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
