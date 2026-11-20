package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 派发探针：不依赖 Robot（本机指针不受 Robot 控制），直接把合成的 MouseEvent
 * 送进窗口的 Surface，验证「按下 → 抬起」链路能否走到 callPlayback / togglePlay。
 *
 * <p>宿主未启动时 WorkshopApi.instance 是 lateinit，调用会抛异常，正好用来区分
 * "根本没派发到" 与 "派发到了但宿主调用失败"：前者没有 requested 日志，后者有。</p>
 */
public final class DispatchProbe {

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Field f = MiniPlayerWindow.class.getDeclaredField("surface");
        f.setAccessible(true);
        java.awt.Component surface = (java.awt.Component) f.get(w);

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);
        Thread.sleep(300);
        System.out.println("window at " + w.getLocation() + " size " + w.getSize()
                + " surface " + surface.getSize() + " showing=" + w.isShowing());

        Method currentLayout = MiniPlayerWindow.class.getDeclaredMethod("currentLayout");
        currentLayout.setAccessible(true);
        Layout l = (Layout) currentLayout.invoke(w);

        String[] names = {"PREV", "PLAY", "NEXT"};
        int[][] centers = {
                {l.prevCx, l.ctrlY},
                {l.playCx, l.ctrlY},
                {l.nextCx, l.ctrlY},
        };

        for (int i = 0; i < centers.length; i++) {
            int lx = centers[i][0];
            int ly = centers[i][1];
            System.out.println("---- dispatch @" + names[i] + " local(" + lx + "," + ly + ")");
            click(surface, lx, ly);
            Thread.sleep(120);
        }
        w.setVisible(false);
        w.dispose();
    }

    private static void click(java.awt.Component target, int lx, int ly) {
        long when = System.currentTimeMillis();
        int mods = MouseEvent.BUTTON1_DOWN_MASK;
        MouseEvent press = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when, mods,
                lx, ly, 1, false, MouseEvent.BUTTON1);
        MouseEvent release = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, when + 60,
                MouseEvent.BUTTON1_DOWN_MASK, lx, ly, 1, false, MouseEvent.BUTTON1);
        target.dispatchEvent(press);
        target.dispatchEvent(release);
    }
}
