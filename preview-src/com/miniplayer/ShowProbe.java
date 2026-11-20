package com.miniplayer;

import javax.imageio.ImageIO;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 真机可见性探针：走和生产完全相同的路径（构造 → showWindow），
 * 然后截屏确认窗口真的画在屏幕上。
 *
 * <p>本机 Robot 无法控制指针，但 createScreenCapture 可用，所以只截图不点击。</p>
 */
public final class ShowProbe {

    public static void main(String[] args) throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        cfg.enabled = true;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);
        System.out.println("created  at=" + w.getLocation() + " size=" + w.getSize());

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);
        Thread.sleep(1200);

        System.out.println("showing=" + w.isShowing() + " visible=" + w.isVisible()
                + " at=" + w.getLocation() + " size=" + w.getSize());
        System.out.println("contentPane=" + w.getContentPane().getClass().getName()
                + " bounds=" + w.getContentPane().getBounds());

        Rectangle b = w.getBounds();
        Robot robot = new Robot();
        BufferedImage full = robot.createScreenCapture(b);
        new File("preview").mkdirs();
        ImageIO.write(full, "png", new File("preview/show-verify.png"));
        System.out.println("capture -> preview/show-verify.png " + full.getWidth() + "x" + full.getHeight());

        // 统计非透明/非背景像素，证明画了东西而不是一片桌面
        long painted = 0;
        for (int y = 0; y < full.getHeight(); y += 3) {
            for (int x = 0; x < full.getWidth(); x += 3) {
                int rgb = full.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int bl = rgb & 0xFF;
                // 卡片是偏绿的浅色底，桌面通常是深色壁纸
                if (g > r && g > bl && g > 90) {
                    painted++;
                }
            }
        }
        System.out.println("greenish samples = " + painted);

        // 停靠到任务栏后再截一张，验证紧凑态能真画出来
        Method taskbarRect = MiniPlayerWindow.class.getDeclaredMethod("taskbarRect");
        taskbarRect.setAccessible(true);
        Method dock = MiniPlayerWindow.class.getDeclaredMethod("dockToTaskbar", Rectangle.class);
        dock.setAccessible(true);
        Rectangle bar = (Rectangle) taskbarRect.invoke(w);
        System.out.println("taskbarRect=" + bar);
        if (bar != null) {
            dock.invoke(w, bar);
            Thread.sleep(900);
            System.out.println("docked  at=" + w.getLocation() + " size=" + w.getSize());
            Rectangle db = w.getBounds().getBounds();
            db.grow(6, 6);
            db = db.intersection(new Rectangle(0, 0, 1707, 960));
            BufferedImage cap = robot.createScreenCapture(db);
            ImageIO.write(cap, "png", new File("preview/live-docked.png"));
            System.out.println("capture -> preview/live-docked.png "
                    + cap.getWidth() + "x" + cap.getHeight());
        } else {
            System.out.println("skip docked capture: no taskbar detected");
        }

        w.setVisible(false);
        w.dispose();
        System.exit(0);
    }
}
