package com.miniplayer;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 像素 alpha 探针：验证「整窗每个像素都不是全透明」这条前提。
 *
 * <p>为什么这条要单独测：Windows 的 per-pixel 透明窗口（{@code WS_EX_LAYERED}）在做命中测试时
 * 只看像素 alpha，{@code alpha==0} 的像素会把鼠标事件**直接穿透给下面的窗口**，Java 侧根本
 * 收不到 {@code MouseEvent}。所以「命中区域」不只是 {@link MiniPlayerWindow#hitDragArea} 那几个
 * 判断条件——先得有像素接到事件，那些判断才有机会跑。</p>
 *
 * <p>历史表现：不垫底时整条只有封面、歌词字、播控图标这些"真的画了像素"的地方能点，空白处一律
 * 穿透，于是拖动、歌词态切换、上下曲都必须精确压在黑色图案上，观感就是"那块区域判定不对"。</p>
 *
 * <p>做法：把 {@code paintBody} 画到一张 ARGB 离屏图上，逐像素统计 alpha。</p>
 */
public final class AlphaProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: alpha probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        cfg.opacity = 1f;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Layout l = currentLayout(w);
        BufferedImage img = new BufferedImage(l.windowW, l.windowH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Method paintBody = MiniPlayerWindow.class.getDeclaredMethod("paintBody", Graphics2D.class);
            paintBody.setAccessible(true);
            paintBody.invoke(w, g);
        } finally {
            g.dispose();
        }

        int clear = 0;
        for (int y = 0; y < l.windowH; y++) {
            for (int x = 0; x < l.windowW; x++) {
                if ((img.getRGB(x, y) >>> 24) == 0) {
                    clear++;
                }
            }
        }
        System.out.println("window " + l.windowW + "x" + l.windowH
                + " contentH=" + l.contentH + " shadow=" + l.shadow
                + " fully transparent pixels = " + clear);

        // 1) 整窗无全透明像素：这是「任何位置都能接到鼠标事件」的充分条件
        check("整窗没有全透明像素（" + clear + " 个）", clear == 0);

        // 2) 抽查几处"什么都没画"的空白点：它们曾经是穿透区
        int[][] blanks = {
                {l.windowW - 6, l.windowH - 6},              // 右下角空白
                {l.textX + 40, l.shadow + 3},                // 文字区上方留白
                {l.coverX + 2, l.contentH - 4},              // 封面下方、进度条上方
                {l.prevCx - 30, l.ctrlY},                    // 播控三键之间的空隙
        };
        for (int[] p : blanks) {
            int alpha = img.getRGB(p[0], p[1]) >>> 24;
            check("空白点 (" + p[0] + "," + p[1] + ") alpha=" + alpha + " > 0", alpha > 0);
        }

        // 3) 垫底仍要"看不见"：alpha 必须是 1，而不是把整条画成黑块
        int blankAlpha = img.getRGB(l.textX + 40, l.shadow + 3) >>> 24;
        check("空白处 alpha 恰为 1（不可见但可命中），实际 " + blankAlpha, blankAlpha == 1);

        // 4) 窗口形状必须是整矩形：圆角会把四角从形状里裁掉，被裁掉的像素同样收不到事件
        java.awt.Shape shape = w.getShape();
        boolean fullRect = shape == null;
        String shapeText = "null（不设形状）";
        if (shape instanceof java.awt.geom.RoundRectangle2D rr) {
            shapeText = "RoundRectangle arc=" + rr.getArcWidth() + "x" + rr.getArcHeight()
                    + " " + rr.getWidth() + "x" + rr.getHeight();
            fullRect = rr.getArcWidth() == 0 && rr.getArcHeight() == 0
                    && rr.getWidth() >= l.windowW && rr.getHeight() >= l.windowH;
        } else if (shape != null) {
            java.awt.geom.Rectangle2D b = shape.getBounds2D();
            shapeText = shape.getClass().getSimpleName() + " " + b.getWidth() + "x" + b.getHeight();
            fullRect = b.getWidth() >= l.windowW && b.getHeight() >= l.windowH;
        }
        check("窗口形状是满矩形（" + shapeText + "）", fullRect);

        w.setVisible(false);
        w.dispose();
    }

    private static Layout currentLayout(MiniPlayerWindow w) throws Exception {
        Method m = MiniPlayerWindow.class.getDeclaredMethod("currentLayout");
        m.setAccessible(true);
        return (Layout) m.invoke(w);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
