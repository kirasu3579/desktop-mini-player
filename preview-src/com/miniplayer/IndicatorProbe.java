package com.miniplayer;

import javax.imageio.ImageIO;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 虚线框探针：把拖拽时的停放预览框真画到屏幕上并截屏，
 * 确认它是透明背景 + 半透明白虚线，而不是一块黑底或实心块。
 *
 * <p>两种场景：只显示虚线框；以及"迷你条正被拖到任务栏附近"——
 * 后者用来确认半透明的卡片不会把虚线框盖住。</p>
 */
public final class IndicatorProbe {

    public static void main(String[] args) throws Exception {
        Constructor<MiniPlayerWindow> ctor = MiniPlayerWindow.class
                .getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Method taskbarRect = MiniPlayerWindow.class.getDeclaredMethod("taskbarRect");
        taskbarRect.setAccessible(true);
        Method dockTargetRect = MiniPlayerWindow.class
                .getDeclaredMethod("dockTargetRect", Rectangle.class);
        dockTargetRect.setAccessible(true);
        Method showIndicator = MiniPlayerWindow.class
                .getDeclaredMethod("showIndicator", Rectangle.class);
        showIndicator.setAccessible(true);
        Method showWindow = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        showWindow.setAccessible(true);

        Rectangle bar = (Rectangle) taskbarRect.invoke(w);
        System.out.println("taskbarRect = " + bar);
        if (bar == null) {
            System.out.println("SKIP: 没有可见任务栏");
            System.exit(0);
        }

        Rectangle target = (Rectangle) dockTargetRect.invoke(w, bar);
        System.out.println("target      = " + target);

        // 先让迷你条显示出来，再叠虚线框——真实拖拽的顺序就是这样，
        // 反过来的话后显示的窗口会把虚线框压在下面
        showWindow.invoke(w);
        Thread.sleep(600);
        showIndicator.invoke(w, target);
        Thread.sleep(600);

        Robot robot = new Robot();
        Rectangle grab = target.getBounds();
        grab.grow(10, 10);
        grab = grab.intersection(new Rectangle(0, 0, 1707, 960));
        BufferedImage img = robot.createScreenCapture(grab);
        new File("preview").mkdirs();
        ImageIO.write(img, "png", new File("preview/live-indicator.png"));
        System.out.println("capture -> preview/live-indicator.png "
                + img.getWidth() + "x" + img.getHeight());
        int cx = img.getWidth() / 2;
        int cy = img.getHeight() / 2;
        System.out.println("center pixel = " + Integer.toHexString(img.getRGB(cx, cy))
                + "（透出的是任务栏本身，说明背景透明）");

        // 场景二：窗口压在任务栏上、处于"已锁定停放点"状态
        Field previewField = MiniPlayerWindow.class.getDeclaredField("dockPreviewActive");
        previewField.setAccessible(true);
        previewField.setBoolean(w, true);
        w.setLocation(Math.max(0, target.x - 50), Math.max(0, target.y - 30));
        w.repaint();
        showIndicator.invoke(w, target);
        Thread.sleep(900);

        Rectangle both = w.getBounds().union(target).getBounds();
        both.grow(12, 12);
        both = both.intersection(new Rectangle(0, 0, 1707, 960));
        BufferedImage img2 = robot.createScreenCapture(both);
        ImageIO.write(img2, "png", new File("preview/live-indicator-dragging.png"));
        System.out.println("capture -> preview/live-indicator-dragging.png "
                + img2.getWidth() + "x" + img2.getHeight());

        Method hideIndicator = MiniPlayerWindow.class.getDeclaredMethod("hideIndicator");
        hideIndicator.setAccessible(true);
        hideIndicator.invoke(w);
        previewField.setBoolean(w, false);
        w.setVisible(false);
        w.dispose();
        System.exit(0);
    }
}
