package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** 可用区域探针：确认 usableScreenBounds() 在拿不到 insets 的环境下也扣掉了任务栏。 */
public final class BoundsProbe {

    public static void main(String[] args) throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);
        try {
            Method m = MiniPlayerWindow.class.getDeclaredMethod("usableScreenBounds");
            m.setAccessible(true);
            Rectangle usable = (Rectangle) m.invoke(w);
            System.out.println("screen size     = " + w.getToolkit().getScreenSize());
            System.out.println("screen insets   = " + w.getToolkit().getScreenInsets(w.getGraphicsConfiguration()));
            System.out.println("usableScreenBounds = " + usable);
            Method ap = MiniPlayerWindow.class.getDeclaredMethod("applyInitialPosition");
            ap.setAccessible(true);
            ap.invoke(w);
            System.out.println("initial location = " + w.getLocation());
            Rectangle self = new Rectangle(w.getX(), w.getY(), w.getWidth(), w.getHeight());
            System.out.println("fits in usable   = " + usable.contains(self));
        } finally {
            w.dispose();
        }
        System.exit(0);
    }
}
