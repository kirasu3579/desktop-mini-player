package com.miniplayer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * 全部图标均为 Java2D 矢量绘制，不依赖外部资源文件。
 *
 * <p>约定：每个方法都在给定矩形 {@code (x, y, size, size)} 内居中绘制，
 * 调用方负责设置颜色以外的状态。
 */
public final class Icons {

    private Icons() {
    }

    private static void setup(Graphics2D g, Color color, float strokeWidth) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    /** 上一首：竖线 + 指向左的三角形。 */
    public static void previous(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, 0f);
        double pad = size * 0.16;
        double left = x + pad;
        double top = y + pad;
        double h = size - pad * 2;
        // 竖线
        double barW = Math.max(1.5, size * 0.09);
        g.fill(new Rectangle2D.Double(left, top, barW, h));
        // 三角
        Path2D.Double tri = new Path2D.Double();
        double triLeft = left + barW + size * 0.08;
        double triRight = x + size - pad;
        tri.moveTo(triRight, top);
        tri.lineTo(triLeft, y + size / 2.0);
        tri.lineTo(triRight, top + h);
        tri.closePath();
        g.fill(tri);
    }

    /** 下一首：指向右的三角形 + 竖线。 */
    public static void next(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, 0f);
        double pad = size * 0.16;
        double barW = Math.max(1.5, size * 0.09);
        double right = x + size - pad;
        double top = y + pad;
        double h = size - pad * 2;
        Path2D.Double tri = new Path2D.Double();
        double triLeftEdge = x + pad;
        double triRight = right - barW - size * 0.08;
        tri.moveTo(triLeftEdge, top);
        tri.lineTo(triRight, y + size / 2.0);
        tri.lineTo(triLeftEdge, top + h);
        tri.closePath();
        g.fill(tri);
        g.fill(new Rectangle2D.Double(right - barW, top, barW, h));
    }

    /** 播放：实心三角形（略右偏以视觉居中）。 */
    public static void play(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, 0f);
        double pad = size * 0.26;
        double shift = size * 0.04;
        Path2D.Double tri = new Path2D.Double();
        tri.moveTo(x + pad + shift, y + pad * 0.72);
        tri.lineTo(x + size - pad + shift, y + size / 2.0);
        tri.lineTo(x + pad + shift, y + size - pad * 0.72);
        tri.closePath();
        g.fill(tri);
    }

    /** 暂停：两条圆角竖线。 */
    public static void pause(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, 0f);
        double padX = size * 0.28;
        double padY = size * 0.26;
        double barW = Math.max(2.0, size * 0.15);
        double h = size - padY * 2;
        g.fill(new java.awt.geom.RoundRectangle2D.Double(
                x + padX, y + padY, barW, h, barW, barW));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(
                x + size - padX - barW, y + padY, barW, h, barW, barW));
    }

    /** 爱心（描边），filled=true 时填充。 */
    public static void heart(Graphics2D g, double x, double y, double size,
                             Color color, boolean filled) {
        Path2D.Double path = heartPath(x, y, size);
        if (filled) {
            setup(g, color, 0f);
            g.fill(path);
        } else {
            setup(g, color, (float) Math.max(1.2, size * 0.09));
            g.draw(path);
        }
    }

    private static Path2D.Double heartPath(double x, double y, double size) {
        double cx = x + size / 2.0;
        double top = y + size * 0.22;
        double bottom = y + size * 0.86;
        double left = x + size * 0.09;
        double right = x + size * 0.91;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx, bottom);
        p.curveTo(cx - size * 0.10, bottom - size * 0.16,
                left, y + size * 0.52,
                left, y + size * 0.40);
        p.curveTo(left, top, x + size * 0.30, top,
                cx, y + size * 0.36);
        p.curveTo(x + size * 0.70, top, right, top,
                right, y + size * 0.40);
        p.curveTo(right, y + size * 0.52,
                cx + size * 0.10, bottom - size * 0.16,
                cx, bottom);
        p.closePath();
        return p;
    }

    /** 音量：喇叭 + 弧线，level 0..3 控制弧线数量。 */
    public static void volume(Graphics2D g, double x, double y, double size,
                              Color color, int level, boolean muted) {
        float sw = (float) Math.max(1.2, size * 0.085);
        setup(g, color, sw);
        // 喇叭主体
        Path2D.Double horn = new Path2D.Double();
        double hornLeft = x + size * 0.10;
        double hornRight = x + size * 0.36;
        double midY = y + size / 2.0;
        horn.moveTo(hornLeft, midY - size * 0.11);
        horn.lineTo(hornRight, midY - size * 0.11);
        horn.lineTo(x + size * 0.52, y + size * 0.20);
        horn.lineTo(x + size * 0.52, y + size * 0.80);
        horn.lineTo(hornRight, midY + size * 0.11);
        horn.lineTo(hornLeft, midY + size * 0.11);
        horn.closePath();
        g.fill(horn);

        if (muted || level <= 0) {
            // 静音叉号
            double cx = x + size * 0.70;
            double r = size * 0.14;
            g.draw(new java.awt.geom.Line2D.Double(cx - r, midY - r, cx + r, midY + r));
            g.draw(new java.awt.geom.Line2D.Double(cx - r, midY + r, cx + r, midY - r));
            return;
        }
        for (int i = 1; i <= level; i++) {
            double rr = size * (0.12 + 0.115 * i);
            double cx = x + size * 0.50;
            g.draw(new Arc2D.Double(cx - rr, midY - rr, rr * 2, rr * 2,
                    -52, 104, Arc2D.OPEN));
        }
    }

    /** 播放列表：三条横线。 */
    public static void playlist(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.3, size * 0.095));
        double left = x + size * 0.14;
        double right = x + size * 0.86;
        for (int i = 0; i < 3; i++) {
            double yy = y + size * (0.28 + i * 0.22);
            g.draw(new java.awt.geom.Line2D.Double(left, yy, right, yy));
        }
    }

    /** 歌词：「词」字用路径绘制成本高，这里用两条文本线 + 音符替代。 */
    public static void lyrics(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.2, size * 0.085));
        double left = x + size * 0.12;
        g.draw(new java.awt.geom.Line2D.Double(left, y + size * 0.32,
                x + size * 0.70, y + size * 0.32));
        g.draw(new java.awt.geom.Line2D.Double(left, y + size * 0.58,
                x + size * 0.70, y + size * 0.58));
        // 右下角小圆点（歌词高亮）
        double d = size * 0.14;
        g.fill(new Ellipse2D.Double(x + size * 0.76, y + size * 0.48, d, d));
    }

    /** 循环：闭环箭头。 */
    public static void repeat(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.2, size * 0.085));
        double pad = size * 0.16;
        g.draw(new Arc2D.Double(x + pad, y + pad, size - pad * 2, size - pad * 2,
                50, 280, Arc2D.OPEN));
        // 箭头
        double ax = x + size * 0.685;
        double ay = y + size * 0.16;
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(ax - size * 0.11, ay - size * 0.02);
        arrow.lineTo(ax + size * 0.06, ay + size * 0.03);
        arrow.lineTo(ax - size * 0.06, ay + size * 0.15);
        arrow.closePath();
        g.fill(arrow);
    }

    /** 关闭/收起：一条向下折线。 */
    public static void collapse(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.3, size * 0.10));
        double cx = x + size / 2.0;
        g.draw(new java.awt.geom.Line2D.Double(cx - size * 0.24, y + size * 0.34,
                cx, y + size * 0.64));
        g.draw(new java.awt.geom.Line2D.Double(cx, y + size * 0.64,
                cx + size * 0.24, y + size * 0.34));
    }

    /** 关闭窗口用的叉号。 */
    public static void close(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.3, size * 0.10));
        double pad = size * 0.28;
        g.draw(new java.awt.geom.Line2D.Double(x + pad, y + pad, x + size - pad, y + size - pad));
        g.draw(new java.awt.geom.Line2D.Double(x + size - pad, y + pad, x + pad, y + size - pad));
    }

    /** 音质标签的圆角胶囊背景 + 文字（文字由调用方绘制）。 */
    public static void pill(Graphics2D g, double x, double y, double w, double h,
                            Color fill, double radius) {
        setup(g, fill, 0f);
        g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, radius, radius));
    }

    /** 独占模式：头戴耳机（半圆头梁 + 两个耳罩）。 */
    public static void headphone(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.3, size * 0.10));
        double cx = x + size / 2.0;
        double cy = y + size * 0.50;
        double r = size * 0.31;
        // 头梁：上半圆
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 18, 144, Arc2D.OPEN));
        // 两个耳罩
        double earW = size * 0.21;
        double earH = size * 0.36;
        double earY = cy - size * 0.09;
        g.fill(new java.awt.geom.RoundRectangle2D.Double(
                cx - r - earW * 0.70, earY, earW, earH, earW, earW));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(
                cx + r - earW * 0.30, earY, earW, earH, earW, earW));
    }

    /** 置顶：向上箭头 + 底部横线（⤒ 样式，小尺寸下最清晰）。 */    public static void pin(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, (float) Math.max(1.4, size * 0.11));
        double cx = x + size / 2.0;
        // 箭头
        Path2D.Double head = new Path2D.Double();
        head.moveTo(cx, y + size * 0.16);
        head.lineTo(x + size * 0.80, y + size * 0.50);
        head.lineTo(x + size * 0.64, y + size * 0.50);
        head.lineTo(x + size * 0.64, y + size * 0.68);
        head.lineTo(x + size * 0.36, y + size * 0.68);
        head.lineTo(x + size * 0.36, y + size * 0.50);
        head.lineTo(x + size * 0.20, y + size * 0.50);
        head.closePath();
        g.fill(head);
        // 底线
        g.draw(new java.awt.geom.Line2D.Double(
                x + size * 0.20, y + size * 0.84, x + size * 0.80, y + size * 0.84));
    }

    /** 音符占位（无封面时使用）。用路径绘制，避免字体缺字形。 */
    public static void note(Graphics2D g, double x, double y, double size, Color color) {
        setup(g, color, 0f);
        // 符头：略倾斜的椭圆
        double headW = size * 0.42;
        double headH = size * 0.32;
        g.fill(new java.awt.geom.Ellipse2D.Double(
                x + size * 0.10, y + size * 0.56, headW, headH));
        // 符干
        double stemW = Math.max(1.6, size * 0.10);
        g.fill(new java.awt.geom.Rectangle2D.Double(
                x + size * 0.10 + headW - stemW, y + size * 0.16,
                stemW, size * 0.52));
        // 符尾钩
        Path2D.Double flag = new Path2D.Double();
        flag.moveTo(x + size * 0.10 + headW - stemW, y + size * 0.16);
        flag.curveTo(x + size * 0.70, y + size * 0.22,
                x + size * 0.74, y + size * 0.40,
                x + size * 0.54, y + size * 0.50);
        flag.curveTo(x + size * 0.68, y + size * 0.38,
                x + size * 0.58, y + size * 0.28,
                x + size * 0.10 + headW - stemW, y + size * 0.30);
        flag.closePath();
        g.fill(flag);
    }
}
