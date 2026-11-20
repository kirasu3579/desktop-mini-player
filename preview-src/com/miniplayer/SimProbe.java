package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 进度平滑探针：模拟「宿主低频上报 + 60fps 渲染」下的进度曲线。
 *
 * <p>不依赖真实的 Swing 定时器（无法在探针里确定性地推进时间），而是每帧把
 * {@code lastTickNs} 手动往回拨 16ms 再调 {@code onTick()}，等价于跑了一帧。</p>
 *
 * <p>覆盖四种运行时形态：</p>
 * <ol>
 *   <li>正常播放（时长已知、playing=true）——进度单调、单帧增量受控；</li>
 *   <li>宿主不给时长（{@code getDuration()==0}）——必须照样每帧推进，
 *       否则位置只能等每秒一次的回调跳一格，画面就是一顿一顿；</li>
 *   <li>宿主漏发 {@code onIsPlayingChanged(true)}（playing 一直为 false）——
 *       只要位置回调确实在往前，也必须推进；</li>
 *   <li>真正暂停（playing=false 且位置不再前进）——必须原地不动。</li>
 * </ol>
 */
public final class SimProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: sim probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        simulate("正常播放（时长已知、playing=true）", 200_000L, true, true, false);
        simulate("时长缺失（getDuration()==0）", 0L, true, true, false);
        simulate("playing 漏发（位置回调在前进）", 200_000L, false, true, false);
        simulate("真正暂停（位置不前进）", 200_000L, false, false, true);
    }

    /**
     * @param playing     窗口认为自己在播
     * @param hostAdvancing 位置回调是否在前进
     * @param paused      是否模拟暂停（回调时间戳也不更新，锚点停留在很久以前）
     */
    private static void simulate(String name, long durationMs, boolean playing, boolean hostAdvancing,
                                 boolean paused) throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Field stateField = MiniPlayerWindow.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Field lastTickNsField = MiniPlayerWindow.class.getDeclaredField("lastTickNs");
        lastTickNsField.setAccessible(true);
        Field hostPosMsField = MiniPlayerWindow.class.getDeclaredField("hostPosMs");
        hostPosMsField.setAccessible(true);
        Field hostPosAtNsField = MiniPlayerWindow.class.getDeclaredField("hostPosAtNs");
        hostPosAtNsField.setAccessible(true);
        Field hostPosValidField = MiniPlayerWindow.class.getDeclaredField("hostPosValid");
        hostPosValidField.setAccessible(true);
        Field hostAdvancingField = MiniPlayerWindow.class.getDeclaredField("hostAdvancing");
        hostAdvancingField.setAccessible(true);
        Field nsCarryField = MiniPlayerWindow.class.getDeclaredField("nsCarry");
        nsCarryField.setAccessible(true);
        Method onTick = MiniPlayerWindow.class.getDeclaredMethod("onTick");
        onTick.setAccessible(true);

        long startMs = 10_000L;
        PlayerState st = new PlayerState("晚风心里吹", "阿梨粤", "", durationMs, startMs,
                playing, false, false, 80, false);
        stateField.set(w, st);
        hostPosMsField.set(w, startMs);
        hostPosAtNsField.set(w, System.nanoTime());
        hostPosValidField.set(w, Boolean.TRUE);
        hostAdvancingField.set(w, hostAdvancing);
        nsCarryField.set(w, 0L);

        long prev = startMs;
        boolean monotonic = true;
        long maxStep = 0L;
        int frames = 180;
        // 宿主每 30 帧（约 0.5s）上报一次真实位置；frame==60 那次故意多报 80ms，
        // 用来验证偏差被限速吸收而不是位置直接跳过去。
        long anchorPosMs = startMs;
        long anchorVirtualNs = 0L;
        long biasMs = 0L;

        for (int frame = 0; frame < frames; frame++) {
            long virtualNs = (long) frame * 16_000_000L;
            // 偏差是"宿主时钟持续偏快 80ms"，不是某一次孤立的多报：
            // 若只在一次回调里多报，后续回调拉回真实值就会造成 target 回退，
            // 那是探针模型自身的问题，不是被测代码的问题。
            if (frame == 60) {
                biasMs = 80L;
            }
            if (!paused && frame > 0 && frame % 30 == 0) {
                anchorVirtualNs = virtualNs;
                anchorPosMs = startMs + virtualNs / 1_000_000L + biasMs;
            }
            hostPosMsField.set(w, anchorPosMs);
            // 虚拟时钟：锚点时刻到"现在"的流逝时间用 virtualNs 表示，
            // 这样宿主上报间隔是可控的（paused 时锚点永远停在最初，视为回调已停）
            long elapsedNs = paused ? (long) frames * 16_000_000L : virtualNs - anchorVirtualNs;
            hostPosAtNsField.set(w, System.nanoTime() - elapsedNs);
            lastTickNsField.set(w, System.nanoTime() - 16_000_000L);
            onTick.invoke(w);
            long now = positionOf(stateField, w);
            if (now < prev) {
                monotonic = false;
                System.out.println("  回退 @frame=" + frame + " " + prev + " -> " + now);
            }
            maxStep = Math.max(maxStep, Math.abs(now - prev));
            prev = now;
        }

        long total = prev - startMs;
        System.out.println("[" + name + "] 180 帧后前进 " + total + "ms，单帧最大增量 = " + maxStep + "ms");
        check(name + "：进度单调不减", monotonic);
        if (paused) {
            check(name + "：暂停时不推进", total == 0L);
        } else {
            check(name + "：推进量接近真实时间（2.6–3.2s）", total >= 2_600L && total <= 3_200L);
            check(name + "：单帧增量不超过 32ms", maxStep <= 32L);
        }

        w.setVisible(false);
        w.dispose();
    }

    private static long positionOf(Field stateField, MiniPlayerWindow w) throws Exception {
        Object st = stateField.get(w);
        return ((PlayerState) st).positionMs;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
