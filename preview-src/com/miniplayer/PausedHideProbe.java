package com.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State;

import java.awt.GraphicsConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「暂停时隐藏」探针：验证这条新选项的边界，而不只是"暂停就藏起来"。
 *
 * <p>用户要的是"播放时显示、未播放时隐藏"，但"未播放"在宿主里有好几种：
 * {@code Idle}（还没开始）、{@code Buffering}（缓冲中）、{@code Ready} 且暂停、
 * {@code Ended}（播完了）。藏错的代价是用户找不着条子——缓冲时藏起来像插件崩了，
 * 播完藏起来让人以为被卸载，刚装好就藏更是莫名其妙。所以边界被确认成<b>只有暂停时隐藏</b>，
 * 这里逐个状态钉死。</p>
 *
 * <p>另外钉住两条副作用边界：</p>
 * <ul>
 *   <li>用户从右键菜单手动隐藏的，恢复播放不能把它弹回来；</li>
 *   <li>开关关掉时要立刻把"因暂停而藏"的那次显示回来。</li>
 * </ul>
 */
public final class PausedHideProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: paused-hide probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        cfg.hideWhenPaused = true;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);
        w.setLocation(400, 300);
        Thread.sleep(300);

        Method apply = MiniPlayerWindow.class.getDeclaredMethod("applyPausedVisibility");
        apply.setAccessible(true);
        Method hide = MiniPlayerWindow.class.getDeclaredMethod("hideMini");
        hide.setAccessible(true);

        Field everPlayed = MiniPlayerWindow.class.getDeclaredField("hasEverPlayed");
        everPlayed.setAccessible(true);
        Field hostState = MiniPlayerWindow.class.getDeclaredField("lastHostState");
        hostState.setAccessible(true);
        Field stateField = MiniPlayerWindow.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Field configField = MiniPlayerWindow.class.getDeclaredField("config");
        configField.setAccessible(true);

        // 0) 从没播过：即便 playing=false 也不该藏，否则一装好条子就消失
        everPlayed.setBoolean(w, false);
        hostState.set(w, State.Idle);
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(false));
        apply.invoke(w);
        check("没播放过不隐藏", w.isVisible());

        // 1) 播放中：显示
        everPlayed.setBoolean(w, true);
        hostState.set(w, State.Ready);
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(true));
        apply.invoke(w);
        check("播放中显示", w.isVisible());

        // 2) 暂停：隐藏（这是这条选项唯一要藏的状态）
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(false));
        stateField.set(w, ((PlayerState) stateField.get(w)).withState(State.Ready));
        hostState.set(w, State.Ready);
        apply.invoke(w);
        check("暂停时隐藏", !w.isVisible());

        // 3) 恢复播放：自动回来
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(true));
        apply.invoke(w);
        check("恢复播放自动显示", w.isVisible());

        // 4) 缓冲中：不隐藏（藏起来像插件崩了）
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(false));
        stateField.set(w, ((PlayerState) stateField.get(w)).withState(State.Buffering));
        hostState.set(w, State.Buffering);
        apply.invoke(w);
        check("缓冲中不隐藏", w.isVisible());

        // 5) 播完（Ended）：不隐藏，让人还能看见条子
        stateField.set(w, ((PlayerState) stateField.get(w)).withState(State.Ended));
        hostState.set(w, State.Ended);
        apply.invoke(w);
        check("播放结束不隐藏", w.isVisible());

        // 6) 用户手动隐藏的，恢复播放不该弹回来
        hostState.set(w, State.Ready);
        stateField.set(w, ((PlayerState) stateField.get(w)).withState(State.Ready));
        hide.invoke(w);
        check("手动隐藏后不可见", !w.isVisible());
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(true));
        apply.invoke(w);
        check("手动隐藏不被恢复播放弹回", !w.isVisible());

        // 7) 把开关关掉：因暂停而藏的那次要立刻显示
        show.invoke(w);
        stateField.set(w, ((PlayerState) stateField.get(w)).withPlaying(false));
        apply.invoke(w);
        check("先按暂停藏起来", !w.isVisible());
        PluginConfig live = (PluginConfig) configField.get(w);
        live.hideWhenPaused = false;
        apply.invoke(w);
        check("关掉开关立刻显示", w.isVisible());

        w.setVisible(false);
        w.dispose();
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }
}
