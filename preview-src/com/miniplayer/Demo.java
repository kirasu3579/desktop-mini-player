package com.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 真机 Demo：真的弹出一个 JWindow（透明/圆角/置顶），截图后退出。
 * 用于验证形状裁剪、投影与字体在真实桌面上的效果。
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        boolean dark = args.length > 0 && "dark".equals(args[0]);
        int holdMs = args.length > 1 ? Integer.parseInt(args[1]) : 1400;

        SwingUtilities.invokeAndWait(() -> {
            try {
                Constructor<MiniPlayerWindow> ctor = MiniPlayerWindow.class
                        .getDeclaredConstructor(java.awt.GraphicsConfiguration.class,
                                PluginConfig.class);
                ctor.setAccessible(true);
                PluginConfig cfg = new PluginConfig();
                cfg.themeMode = dark ? PluginConfig.MODE_DARK : PluginConfig.MODE_LIGHT;
                MiniPlayerWindow window = ctor.newInstance(null, cfg);

                MediaItem item = new MediaItem("晚风心里吹", "阿梨粤", "晚风心里吹", "阿梨粤",
                        "D:/Music/wanfeng.flac", "track-001", 2023, "Pop", 1, 194000L, true, true);
                window.onMediaItemChanged(item);
                window.onIsPlayingChanged(true);
                window.onPositionUpdated(62000L);

                BufferedImage coverArt = fakeCover();
                // 位图按设备缩放生成（1.5x 屏取 1.5 倍逻辑尺寸），与生产代码一致
                int coverPx = Math.round(window.getWidth() > 0
                        ? ((Layout) MiniPlayerWindow.class.getDeclaredMethod("currentLayout")
                        .invoke(window)).coverSize * 1.5f : 45f);
                setField(window, "cover", scaleTo(coverArt, Math.max(24, coverPx)));
                Color dominant = Theme.dominantColor(pixels(coverArt));
                setField(window, "coverSeed", dominant);
                setField(window, "baseColor", Theme.baseColor(dominant, dark));

                window.setLocation(120, 120);
                window.setVisible(true);
                window.toFront();
                window.repaint();

                Thread.sleep(holdMs);

                Rectangle bounds = window.getBounds();
                Robot robot = new Robot();
                BufferedImage shot = robot.createScreenCapture(bounds);
                File out = new File("E:/AiTool/666/chajian2/spw-mini-player/preview/"
                        + (dark ? "live-dark.png" : "live-light.png"));
                out.getParentFile().mkdirs();
                ImageIO.write(shot, "png", out);
                System.out.println("wrote " + out.getAbsolutePath()
                        + " translucencyCapable=" + window.getGraphicsConfiguration()
                        .isTranslucencyCapable());

                window.setVisible(false);
                window.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }

    private static BufferedImage fakeCover() {
        int s = 300;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(s, s),
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x7C, 0xA8, 0x5C), new Color(0x9C, 0xC2, 0x74),
                        new Color(0xD8, 0xE6, 0xB6)}));
        g.fillRect(0, 0, s, s);
        g.setColor(new Color(255, 255, 255, 60));
        g.fill(new Ellipse2D.Double(s * 0.18, s * 0.42, s * 0.62, s * 0.62));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 54));
        g.setColor(new Color(0x33, 0x44, 0x22, 200));
        g.drawString("晚风", 44, 132);
        g.dispose();
        return img;
    }

    private static BufferedImage scaleTo(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static int[] pixels(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        return img.getRGB(0, 0, w, h, null, 0, w);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
