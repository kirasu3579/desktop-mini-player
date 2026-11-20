package com.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 离线渲染预览：不依赖 Salt Player 宿主，把悬浮条的各种状态画到 PNG，用于校对视觉稿。
 */
public final class Preview {

    private Preview() {
    }

    public static void main(String[] args) throws Exception {
        File outDir = new File("E:/AiTool/666/chajian2/spw-mini-player/preview");
        outDir.mkdirs();

        // 展开态（鼠标悬停）：封面 + 歌名歌手 + 播控 + 进度条
        render(outDir, "mini-light.png", false, Seed.GREEN, true, false, false);
        render(outDir, "mini-dark.png", true, Seed.GREEN, true, false, false);
        render(outDir, "mini-hover.png", false, Seed.GREEN, true, true, false);
        render(outDir, "mini-nocover.png", false, Seed.GREEN, false, false, false);
        render(outDir, "mini-cyan.png", false, Seed.CYAN, true, false, false);
        render(outDir, "mini-pink.png", false, Seed.PINK, true, false, false);

        // 歌词态（鼠标移开）：封面 + 当前歌词行 + 播控三键
        // （1.0.33 起歌词态固定保留播控，不再有"收起时也显示播控"的对照图）
        renderLyric(outDir, "mini-lyric.png", false, true, false);
        renderLyric(outDir, "mini-lyric-dark.png", true, true, false);
        // 卡拉 OK 逐字：宿主给了 lyricsCells 时按时间逐字点亮
        renderLyric(outDir, "mini-lyric-karaoke.png", false, true, true);
        renderLyric(outDir, "mini-lyric-karaoke-dark.png", true, true, true);
        // 超宽歌词：整行远超可视宽度，跟着演唱边界左滚（滚动中 / 滚到句末两个时刻）
        renderLyricOverflow(outDir, "mini-lyric-overflow.png", false, 74000L);
        // 78.9s：演唱边界（含 KARAOKE_LEAD_MS 200，即 79.1s）已进最后一格，
        // 位移被提前量顶到上限，出图应看到整行滚到底、行尾完整露出且最后一个字正在点亮
        renderLyricOverflow(outDir, "mini-lyric-overflow-end.png", false, 79050L);

        // 拼一张总览
        contactSheet(outDir);
        System.out.println("OK -> " + outDir.getAbsolutePath());
    }

    private enum Seed { GREEN, CYAN, PINK }

    /**
     * 歌词态：封面 + 当前歌词行，播控/歌名/进度条全部淡出。
     *
     * <p>{@code controlsAlpha} 由 30fps 定时器驱动，静态渲染时直接置 0 即可。</p>
     */
    private static void renderLyric(File dir, String fileName, boolean dark, boolean buttons,
                                    boolean karaoke) throws Exception {
        LyricsLine line = karaoke ? karaokeLine() : new LyricsLine(60000L, 64000L,
                java.util.Collections.emptyList(), "夜风轻轻吹过我的窗前", "");
        LyricsLine next = new LyricsLine(64000L, 68000L, java.util.Collections.emptyList(),
                "带走了我所有的思念", "");
        renderLyricState(dir, fileName, dark, buttons, karaoke ? 62200L : 62000L, line, next);
    }

    /** 歌词态出图的公共部分：造窗口、灌注封面与歌词行、按给定播放位置渲染。 */
    private static void renderLyricState(File dir, String fileName, boolean dark, boolean buttons,
                                         long positionMs, LyricsLine line, LyricsLine next)
            throws Exception {
        MiniPlayerWindow window = newWindow(dark);

        MediaItem item = new MediaItem(
                "晚风心里吹", "阿梨粤", "晚风心里吹", "阿梨粤",
                "D:/Music/wanfeng.flac", "track-001", 2023, "Pop", 1, 194000L, true, true);
        window.onMediaItemChanged(item);
        window.onIsPlayingChanged(true);
        window.onPositionUpdated(positionMs);

        BufferedImage coverArt = fakeCover(Seed.GREEN);
        setField(window, "cover", scaleTo(coverArt, coverPixels(window)));
        Color dominant = Theme.dominantColor(pixels(coverArt));
        setField(window, "coverSeed", dominant);
        setField(window, "baseColor", Theme.baseColor(dominant, dark));

        setField(window, "lyricsLines", java.util.List.of(line, next));
        setField(window, "currentLyric", line);
        setField(window, "controlsAlpha", 0f);
        // 播控单独一份透明度：1.0.33 起歌词态固定显示播控，这个开关只影响出图对照
        setField(window, "buttonsAlpha", buttons ? 1f : 0f);

        BufferedImage canvas = wallpaper(window, dark);
        draw(window, canvas);
        ImageIO.write(canvas, "png", new File(dir, fileName));
        System.out.println("wrote " + fileName);
        window.dispose();
    }

    /**
     * 造一条带逐字时间轴的歌词。
     *
     * <p>故意按 3 个字一个 cell 来造——宿主的真实粒度通常就是词组而不是单字，
     * 这样出图才能验证「把 cell 细化到单字」是否生效。</p>
     */
    private static LyricsLine karaokeLine() {
        return karaokeLine("夜风轻轻吹过我的窗前", 60000L, 400L);
    }

    /** 按固定节拍给整行每个字造 cell：每 3 个字一个 cell，每字 {@code perCharMs}。 */
    private static LyricsLine karaokeLine(String s, long start, long perCharMs) {
        java.util.List<LyricsLine.Cell> cells = new java.util.ArrayList<>();
        long t = start;
        for (int i = 0; i < s.length(); i += 3) {
            int j = Math.min(s.length(), i + 3);
            long dur = perCharMs * (j - i);
            cells.add(new LyricsLine.Cell(t, t + dur, s.substring(i, j)));
            t += dur;
        }
        return new LyricsLine(start, t, cells, s, "");
    }

    /**
     * 超宽歌词行：整行宽度远超可视区，验证「不截断、跟着演唱边界滚动」。
     *
     * <p>1.0.34 起位移是「边界 + 一个汉字宽」，所以提前量让最后那一截在本行结束前
     * 就完整露出来。两张图取 74s（滚到中段）与 78.9s（边界已进最后一格，
     * 位移顶在上限）。</p>
     *
     * @param positionMs 播放位置，决定边界滚到哪一段
     */
    private static void renderLyricOverflow(File dir, String fileName, boolean dark, long positionMs)
            throws Exception {
        String text = "夜风轻轻吹过我的窗前带走所有思念".repeat(2);
        LyricsLine line = karaokeLine(text, 60000L, 600L);
        LyricsLine next = new LyricsLine(line.getEndTime(), line.getEndTime() + 4000L,
                java.util.Collections.emptyList(), "下一句歌词在等", "");
        renderLyricState(dir, fileName, dark, false, positionMs, line, next);
    }

    private static void render(File dir, String fileName, boolean dark, Seed seed,
                               boolean withCover, boolean hoverBar, boolean paused)
            throws Exception {
        MiniPlayerWindow window = newWindow(dark);

        MediaItem item = new MediaItem(
                "晚风心里吹", "阿梨粤", "晚风心里吹", "阿梨粤",
                "D:/Music/wanfeng.flac", "track-001", 2023, "Pop", 1, 194000L, true, true);
        window.onMediaItemChanged(item);
        window.onIsPlayingChanged(!paused);
        window.onPositionUpdated(62000L);

        Color dominant = null;
        if (withCover) {
            BufferedImage coverArt = fakeCover(seed);
            setField(window, "cover", scaleTo(coverArt, coverPixels(window)));
            dominant = Theme.dominantColor(pixels(coverArt));
            setField(window, "coverSeed", dominant);
        }
        setField(window, "hoverBar", hoverBar);
        setField(window, "baseColor", Theme.baseColor(dominant, dark));

        BufferedImage canvas = wallpaper(window, dark);
        draw(window, canvas);
        ImageIO.write(canvas, "png", new File(dir, fileName));
        window.dispose();
        System.out.println("wrote " + fileName);
    }

    /** 封面位图边长：按设备缩放生成，与生产代码一致，避免静态图发虚。 */
    private static int coverPixels(MiniPlayerWindow window) throws Exception {
        Layout l = (Layout) MiniPlayerWindow.class.getDeclaredMethod("currentLayout")
                .invoke(window);
        return Math.max(l.coverSize, Math.round(l.coverSize * 1.5f));
    }

    private static MiniPlayerWindow newWindow(boolean dark) throws Exception {
        Constructor<MiniPlayerWindow> ctor = MiniPlayerWindow.class
                .getDeclaredConstructor(java.awt.GraphicsConfiguration.class,
                        PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        // 预览固定显式配色，避免受开发机系统深色设置影响
        cfg.themeMode = dark ? PluginConfig.MODE_DARK : PluginConfig.MODE_LIGHT;
        return ctor.newInstance(null, cfg);
    }

    private static BufferedImage wallpaper(MiniPlayerWindow window, boolean dark) {
        int w = window.getWidth();
        int h = window.getHeight();
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        if (dark) {
            g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0),
                    new Point2D.Float(w, h), new float[]{0f, 1f},
                    new Color[]{new Color(0x2B, 0x2F, 0x38), new Color(0x10, 0x12, 0x16)}));
        } else {
            g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0),
                    new Point2D.Float(w, h), new float[]{0f, 1f},
                    new Color[]{new Color(0xEC, 0xF1, 0xE6), new Color(0xCB, 0xD8, 0xBA)}));
        }
        g.fillRect(0, 0, w, h);
        g.dispose();
        return canvas;
    }

    /**
     * 把窗口画到离屏图上。
     *
     * <p>绘制逻辑在 contentPane 的 {@code paintComponent} 里，窗口没显示时
     * {@code JWindow.paint} 不会替子组件做 layout，因而画出来是空白。这里直接
     * 对 {@code surface} 调 paint，绕过可见性与布局依赖。</p>
     */
    private static void draw(MiniPlayerWindow window, BufferedImage canvas) throws Exception {
        Object surface = getField(window, "surface");
        java.awt.Component comp = (java.awt.Component) surface;
        comp.setSize(window.getWidth(), window.getHeight());
        comp.doLayout();
        Graphics2D pg = canvas.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        comp.paint(pg);
        pg.dispose();
    }

    /** 把各种状态竖向拼成一张对比图。 */
    private static void contactSheet(File dir) throws Exception {
        String[] names = {"mini-light.png", "mini-hover.png", "mini-dark.png",
                "mini-lyric.png", "mini-lyric-dark.png",
                "mini-lyric-karaoke.png", "mini-lyric-karaoke-dark.png",
                "mini-lyric-overflow.png", "mini-lyric-overflow-end.png",
                "mini-pink.png", "mini-nocover.png"};
        String[] labels = {"浅色 · 悬停（展开播控）", "浅色 · 进度条悬停", "深色 · 悬停（展开播控）",
                "浅色 · 歌词态（播控常显）", "深色 · 歌词态（播控常显）",
                "浅色 · 卡拉 OK 逐字（唱到第 6 字一半）", "深色 · 卡拉 OK 逐字",
                "浅色 · 超宽行（滚到第 22 字）", "浅色 · 超宽行（滚到句末）",
                "粉色封面", "无封面（占位）"};
        int pad = 12;
        int labelH = 22;
        BufferedImage first = ImageIO.read(new File(dir, names[0]));
        int w = first.getWidth() + pad * 2;
        int cellH = first.getHeight() + labelH + pad;
        int h = cellH * names.length + pad;

        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xF4, 0xF6, 0xF2));
        g.fillRect(0, 0, w, h);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));

        int y = pad;
        for (int i = 0; i < names.length; i++) {
            g.setColor(new Color(0x5A, 0x66, 0x55));
            g.drawString(labels[i], pad, y + 14);
            BufferedImage img = ImageIO.read(new File(dir, names[i]));
            g.drawImage(img, pad, y + labelH, null);
            y += cellH;
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(dir, "sheet.png"));
        System.out.println("wrote sheet.png " + w + "x" + h);
    }

    private static BufferedImage fakeCover(Seed seed) {
        int s = 300;
        Color[] trio;
        switch (seed) {
            case CYAN -> trio = new Color[]{new Color(0x2E, 0x8F, 0xA8), new Color(0x4F, 0xB3, 0xBF),
                    new Color(0xC2, 0xEC, 0xF0)};
            case PINK -> trio = new Color[]{new Color(0xB4, 0x4E, 0x74), new Color(0xD9, 0x7A, 0x9B),
                    new Color(0xF6, 0xD9, 0xE2)};
            default -> trio = new Color[]{new Color(0x7C, 0xA8, 0x5C), new Color(0x9C, 0xC2, 0x74),
                    new Color(0xD8, 0xE6, 0xB6)};
        }
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(s, s),
                new float[]{0f, 0.55f, 1f}, trio));
        g.fillRect(0, 0, s, s);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(s * 0.75f, s * 0.22f),
                s * 0.55f, new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 190), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, s, s);
        g.setColor(new Color(255, 255, 255, 60));
        g.fill(new Ellipse2D.Double(s * 0.18, s * 0.42, s * 0.62, s * 0.62));
        g.setColor(new Color(0x33, 0x44, 0x22, 200));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 54));
        g.drawString("晚风", 44, 132);
        g.dispose();
        return img;
    }    private static BufferedImage scaleTo(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static int[] pixels(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        return img.getRGB(0, 0, w, h, null, 0, w);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
