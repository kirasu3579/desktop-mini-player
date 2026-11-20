package com.miniplayer;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine;

/**
 * 卡拉 OK 探针：验证「边界在 cell 内部也连续推进」这一条。
 *
 * <p>不截图取色（阈值太脆），直接对绘制用的几个纯函数下断言：
 * 拆分粒度与时长权重、时间轴可用性判定、边界坐标是否落在格子中间、以及逐帧推进是否单调。</p>
 */
public final class KaraokeProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: karaoke probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Method currentLayout = MiniPlayerWindow.class.getDeclaredMethod("currentLayout");
        currentLayout.setAccessible(true);
        Layout layout = (Layout) currentLayout.invoke(w);

        Field lyricFontField = MiniPlayerWindow.class.getDeclaredField("lyricFont");
        lyricFontField.setAccessible(true);
        Font font = (Font) lyricFontField.get(w);

        BufferedImage canvas = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        g.dispose();

        Method refine = MiniPlayerWindow.class.getDeclaredMethod("refineCells", List.class);
        refine.setAccessible(true);
        Method usable = MiniPlayerWindow.class.getDeclaredMethod("timelineUsable", List.class, String.class);
        usable.setAccessible(true);
        Method timelineEdge = MiniPlayerWindow.class.getDeclaredMethod(
                "timelineEdge", List.class, FontMetrics.class, int.class, int.class);
        timelineEdge.setAccessible(true);
        Field stateField = MiniPlayerWindow.class.getDeclaredField("state");
        stateField.setAccessible(true);

        // --- 1. 三字一组必须拆成三个单字，且时长均分
        List<LyricsLine.Cell> grouped = new ArrayList<>();
        grouped.add(new LyricsLine.Cell(0L, 1200L, "夜风轻"));
        List<LyricsLine.Cell> refined = asList(refine.invoke(null, grouped));
        check("三字 cell 拆成三格", refined.size() == 3);
        check("首格时长 400ms", refined.get(0).getEndTime() - refined.get(0).getStartTime() == 400L);
        check("拆出的字序正确", "夜".equals(refined.get(0).getText())
                && "风".equals(refined.get(1).getText())
                && "轻".equals(refined.get(2).getText()));

        // --- 2. 含中文标点也要拆，且标点只占 0.25 份时长
        List<LyricsLine.Cell> withPunct = new ArrayList<>();
        withPunct.add(new LyricsLine.Cell(0L, 1200L, "吹过，我"));
        List<LyricsLine.Cell> refinedPunct = asList(refine.invoke(null, withPunct));
        check("含标点的 cell 也拆开", refinedPunct.size() == 4);
        long hanDur = refinedPunct.get(0).getEndTime() - refinedPunct.get(0).getStartTime();
        long punctDur = refinedPunct.get(2).getEndTime() - refinedPunct.get(2).getStartTime();
        check("标点时长明显短于汉字", punctDur < hanDur);
        check("标点符号被单独拆出", "，".equals(refinedPunct.get(2).getText()));

        // --- 3. 拉丁文不拆
        List<LyricsLine.Cell> latin = new ArrayList<>();
        latin.add(new LyricsLine.Cell(0L, 1000L, "hello"));
        check("拉丁词不拆", asList(refine.invoke(null, latin)).size() == 1);

        // --- 4. 时间轴可用性
        List<LyricsLine.Cell> one = new ArrayList<>();
        one.add(new LyricsLine.Cell(0L, 400L, "夜"));
        one.add(new LyricsLine.Cell(400L, 800L, "风"));
        check("拼接一致则可用", (Boolean) usable.invoke(null, one, "夜风"));
        check("拼接不一致则不可用", !(Boolean) usable.invoke(null, one, "夜风轻"));
        check("空 cells 不可用", !(Boolean) usable.invoke(null, Collections.emptyList(), "夜风"));

        // --- 5. 边界在格子内部有中间值（不是只跳格子边界），并且整体带提前量
        long lead = karaokeLead(w);
        List<LyricsLine.Cell> three = new ArrayList<>();
        three.add(new LyricsLine.Cell(0L, 400L, "夜"));
        three.add(new LyricsLine.Cell(400L, 800L, "风"));
        three.add(new LyricsLine.Cell(800L, 1200L, "轻"));
        int left = 100;
        int threeW = fm.stringWidth("夜风轻");
        stateField.set(w, PlayerState.empty().withPosition(600L - lead));  // 加提前量后落在第二格正中间
        double edgeMid = (Double) timelineEdge.invoke(w, three, fm, left, threeW);
        stateField.set(w, PlayerState.empty().withPosition(400L - lead));  // 加提前量后正好第二格开头
        double edgeStart = (Double) timelineEdge.invoke(w, three, fm, left, threeW);
        stateField.set(w, PlayerState.empty().withPosition(800L - lead));  // 第二格结束
        double edgeEnd = (Double) timelineEdge.invoke(w, three, fm, left, threeW);
        double half = (edgeEnd - edgeStart) / 2d;
        check("格内中点落在格子中间", Math.abs(edgeMid - (edgeStart + half)) < 0.6);
        check("格内中点严格在两端之间", edgeMid > edgeStart && edgeMid < edgeEnd);

        // 提前量本身：真实位置还在第一格时，边界应该已经到了第二格起点
        stateField.set(w, PlayerState.empty().withPosition(400L - lead + 1L));
        double edgeAhead = (Double) timelineEdge.invoke(w, three, fm, left, threeW);
        check("边界整体提前 " + lead + "ms", edgeAhead > edgeStart && edgeAhead < edgeMid);

        // --- 6. 逐帧推进单调、单帧位移不超过 4px
        double prev = Double.NEGATIVE_INFINITY;
        double maxJump = 0d;
        boolean monotonic = true;
        for (long pos = 0; pos <= 1200; pos += 16) {
            stateField.set(w, PlayerState.empty().withPosition(pos));
            double edge = (Double) timelineEdge.invoke(w, three, fm, left, threeW);
            if (edge < prev - 0.001) {
                monotonic = false;
            }
            if (prev != Double.NEGATIVE_INFINITY) {
                maxJump = Math.max(maxJump, edge - prev);
            }
            prev = edge;
        }
        check("边界单调不减", monotonic);
        check("单帧位移 <= 4px", maxJump <= 4d);

        // --- 7. 逐格宽度之和与整串宽度不一致时，行末边界仍要覆盖整行
        //
        // 真机上宿主的 cell 文本常带首尾空格、或多拆出标点格，于是
        // Σ fm.stringWidth(piece) 与 fm.stringWidth(text) 不相等。
        // 边界若按逐格宽度累加，行末就会停在整行宽度的 ~95% 处，
        // 表现为句尾最后一个字只点亮一部分就换行。
        List<LyricsLine.Cell> padded = new ArrayList<>();
        padded.add(new LyricsLine.Cell(0L, 400L, " 你"));
        padded.add(new LyricsLine.Cell(400L, 800L, "是"));
        padded.add(new LyricsLine.Cell(800L, 1200L, "那"));
        stateField.set(w, PlayerState.empty().withPosition(1200L - lead));
        double fullW = fm.stringWidth("你是那");
        double edgeTail = (Double) timelineEdge.invoke(w, padded, fm, left, (int) fullW);
        check("行末边界覆盖整行宽度（余量 " + (edgeTail - left - fullW) + "px）",
                Math.abs(edgeTail - (left + fullW)) < 0.6);

        System.out.println(String.format(java.util.Locale.ROOT,
                "  边界: start=%.1f mid=%.1f end=%.1f maxJump=%.2f contentH=%d",
                edgeStart, edgeMid, edgeEnd, maxJump, layout.contentH));

        w.setVisible(false);
        w.dispose();
    }

    /**
     * 读 {@code KARAOKE_LEAD_MS}：边界计算会把播放位置整体提前这么多毫秒。
     *
     * <p>探针里所有"某位置应落在某格"的断言都要先把提前量扣掉，否则断言的是
     * 没有提前量时的旧行为。</p>
     */
    private static long karaokeLead(MiniPlayerWindow w) throws Exception {
        Field f = MiniPlayerWindow.class.getDeclaredField("KARAOKE_LEAD_MS");
        f.setAccessible(true);
        return f.getLong(null);
    }

    @SuppressWarnings("unchecked")
    private static List<LyricsLine.Cell> asList(Object o) {
        return (List<LyricsLine.Cell>) o;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
