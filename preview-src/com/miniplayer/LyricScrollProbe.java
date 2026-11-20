package com.miniplayer;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 超宽歌词探针：验证「整行超出可视宽度后，卡拉 OK 仍然生效，并且能滚动看到句末」。
 *
 * <p>被修掉的旧行为是：整行宽度超过 {@code maxW} 就整行画省略号并 return，
 * 于是超宽行既没有逐字动效，也永远看不到被裁掉的后半句。这个探针对两件事下断言：</p>
 *
 * <ol>
 *   <li>{@code lyricScrollFor} 纯函数：不越界、单调、演唱边界始终留在可视区内，
 *       并且<b>在边界走到行尾一个汉字之前，行尾就已经完整滚进窗口</b>
 *       （这才是「一句歌词最后一截滚不到」的修法）；</li>
 *   <li>像素级：超宽行前半亮后半暗（逐字染色确实跑起来了），且平移到上限后
 *       行尾的墨迹正好落到可视右边缘（句末真的被滚进窗口）。</li>
 * </ol>
 */
public final class LyricScrollProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: lyric scroll probe all green" : "FAIL: " + failures + " check(s) failed");
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

        Method scrollFor = MiniPlayerWindow.class.getDeclaredMethod(
                "lyricScrollFor", double.class, int.class, int.class, int.class);
        scrollFor.setAccessible(true);

        Method paintLine = MiniPlayerWindow.class.getDeclaredMethod(
                "paintKaraokeLine", Graphics2D.class, String.class, int.class, int.class,
                double.class, FontMetrics.class, float.class);
        paintLine.setAccessible(true);

        int left = layout.textX;
        int maxW = Math.max(40, (layout.contentW - 12) - left);
        String longText = "夜风轻轻吹过我的窗前带走所有思念".repeat(2);
        String shortText = "夜风轻轻吹过";

        FontMetrics fm = metrics(font);
        int longW = fm.stringWidth(longText);
        int shortW = fm.stringWidth(shortText);
        check("超长行确实超出可视宽度", longW > maxW);
        check("短行确实没超出可视宽度", shortW <= maxW);

        // --- 1. 纯函数 lyricScrollFor（edge 以行首为原点，位移 = edge + 提前量 - maxW）
        // 提前量取一个汉字宽，与实现里的 lyricLookahead(fm) 一致
        int lookahead = Math.max(1, fm.charWidth('字'));
        check("提前量是一个汉字宽", lookahead > 4);

        check("无边界(NaN)不滚动", scroll(scrollFor, Double.NaN, longW, maxW, lookahead) == 0);
        check("不超宽不滚动", scroll(scrollFor, longW, shortW, maxW, lookahead) == 0);
        check("边界在行首不滚动", scroll(scrollFor, 0d, longW, maxW, lookahead) == 0);
        // 镜头走在边界前面一格：边界距右缘还有 lookahead 时位移仍为 0
        check("边界距右缘还有一个字时不滚动",
                scroll(scrollFor, maxW - lookahead - 1d, longW, maxW, lookahead) == 0);
        check("边界刚好吃掉提前量时不滚动",
                scroll(scrollFor, maxW - lookahead, longW, maxW, lookahead) == 0);

        double trigger = maxW - lookahead;
        int midScroll = scroll(scrollFor, trigger + 40d, longW, maxW, lookahead);
        check("边界进入右缘后开始滚动", midScroll > 0);
        // 回归 1.0.36：真机日志里 textW=317/maxW=157/span=160 的长句整段 scroll 都是 0，
        // 因为只按「边界追到右缘」触发时，超宽行要大半天才越过阈值，尾部一直藏在窗外。
        // 现在触发点之后按比例兜底，句中就该滚完大半。
        int span0 = longW - maxW;
        int halfway = scroll(scrollFor, (maxW + longW) / 2d, longW, maxW, lookahead);
        check("边界到行中时已滚过半程", halfway > span0 / 2);
        check("滚动后边界仍在可视区内", (trigger + 40d) - midScroll <= maxW);
        check("滚动量不超过可滚范围", midScroll <= longW - maxW);

        int span = longW - maxW;
        // 核心断言：边界还差一个字到行尾时，行尾已经完整露出来并停住。
        // 这样最后一个字是"边亮边被看清"的，不会再出现"到下一句了还有一点点没滚到"。
        check("边界还差一个字到行尾时已滚到底",
                scroll(scrollFor, longW - lookahead, longW, maxW, lookahead) == span);
        check("滚到上限时句末贴住右边缘", scroll(scrollFor, longW, longW, maxW, lookahead) == span);
        check("边界超过句末也不会多滚",
                scroll(scrollFor, longW * 2d, longW, maxW, lookahead) == span);
        check("提前量为 0 时退化成贴着边界滚",
                scroll(scrollFor, longW, longW, maxW, 0) == span);

        boolean monotonic = true;
        int prev = -1;
        boolean bounded = true;
        boolean edgeVisible = true;
        boolean tailInTime = true;
        for (int e = 0; e <= longW + 40; e += 5) {
            int s = scroll(scrollFor, e, longW, maxW, lookahead);
            if (s < prev) {
                monotonic = false;
            }
            if (s > span) {
                bounded = false;
            }
            if (Math.min(e, longW) - s > maxW + 0.5d) {
                edgeVisible = false;
            }
            // 边界还剩一个字到行尾之后，必须一直顶在上限，不能回退
            if (e >= longW - lookahead && s != span) {
                tailInTime = false;
            }
            prev = s;
        }
        check("滚动量随边界单调不减", monotonic);
        check("滚动量始终不超过可滚范围", bounded);
        check("任何时刻演唱边界都留在可视区内", edgeVisible);
        check("边界进入最后一格后位移恒定在上限", tailInTime);

        // --- 2. 像素级：超宽行仍然逐字染色（边界在行中，未滚动）
        // 两段取同样宽度，墨量才有可比性
        int endScroll = span;
        double probeEdge = maxW * 0.4d;
        BufferedImage half = render(paintLine, w, font, longText, left, 0, probeEdge, false);
        long headInk = ink(half, left, left + (int) probeEdge);
        long restInk = ink(half, left + (int) probeEdge, left + 2 * (int) probeEdge);
        check("超宽行已唱部分明显比未唱部分实", headInk > restInk * 1.4d);
        check("超宽行两段都有墨（不是整行被降级成一句实色）", headInk > 0 && restInk > 0);

        // --- 3. 像素级：平移把行尾搬进可视窗口
        BufferedImage unscrolled = render(paintLine, w, font, longText, left, 0, longW, false);
        BufferedImage scrolled = render(paintLine, w, font, longText, left, endScroll, longW, false);
        int rightBefore = rightmostInk(unscrolled);
        int rightAfter = rightmostInk(scrolled);
        check("未滚动时行尾远在窗口之外", rightBefore >= left + maxW + 20);
        check("滚到上限后行尾贴住可视右边缘",
                rightAfter >= left + maxW - 8 && rightAfter <= left + maxW + 2);

        // --- 4. 像素级：真实绘制（带回 clipRect）时窗口右侧仍有句末墨迹
        BufferedImage clipped = render(paintLine, w, font, longText, left, endScroll, longW, true);
        long tailInk = ink(clipped, left + maxW - 8, left + maxW);
        check("滚动后可视窗口最右侧有句末文字", tailInk > 0);

        // --- 4b. 关键回归：边界还剩一个汉字到行尾时，行尾必须已经完整可见
        // 这是「一句歌词最后一截滚不到」的直接复现点：把位移取成实现给的量，
        // 用真实 clipRect 出图，断言最右侧一列有墨（行尾真的露出来了）。
        int scrollAtLastChar = scroll(scrollFor, longW - lookahead, longW, maxW, lookahead);
        BufferedImage lastChar = render(paintLine, w, font, longText, left, scrollAtLastChar, longW, true);
        int rightAtLastChar = rightmostInk(lastChar);
        check("边界还剩一个字时行尾已在可视区内",
                rightAtLastChar >= left + maxW - 8 && rightAtLastChar <= left + maxW + 2);

        // --- 5. 对照：短行整行实色，与「无边界」完全一致
        BufferedImage shortSolid = render(paintLine, w, font, shortText, left, 0, shortW, false);
        BufferedImage shortNan = render(paintLine, w, font, shortText, left, 0, Double.NaN, false);
        check("短行整行实色时与无动效等价", ink(shortSolid, 0, 200) == ink(shortNan, 0, 200));
        check("短行不滚动", scroll(scrollFor, shortW, shortW, maxW, lookahead) == 0);

        System.out.println(String.format(Locale.ROOT,
                "  度量: maxW=%d longW=%d shortW=%d midScroll=%d endScroll=%d "
                        + "headInk=%d restInk=%d rightBefore=%d rightAfter=%d tailInk=%d",
                maxW, longW, shortW, midScroll, endScroll,
                headInk, restInk, rightBefore, rightAfter, tailInk));

        w.setVisible(false);
        w.dispose();
    }

    /**
     * 按 paintLyric 的方式画一行：先（可选）clip 到可视窗口，再子画布平移 scroll 后调
     * {@code paintKaraokeLine}。
     *
     * @param relEdge 边界相对行首的坐标；{@link Double#NaN} 表示不做动效
     */
    private static BufferedImage render(Method paintLine, MiniPlayerWindow w, Font font, String text,
                                        int left, int scroll, double relEdge, boolean clip)
            throws Exception {
        BufferedImage img = new BufferedImage(520, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int maxW = Math.max(40, (304 - 12) - left);
        if (clip) {
            g.clipRect(left, 0, maxW, 80);
        }
        double edge = Double.isNaN(relEdge) ? Double.NaN : left + relEdge;
        Graphics2D lg = (Graphics2D) g.create();
        lg.translate(-scroll, 0);
        paintLine.invoke(w, lg, text, left, fm.getAscent(), edge, fm, 1f);
        lg.dispose();
        g.dispose();
        return img;
    }

    /** 统计一段横向范围内的 alpha 之和，作为"墨量"的度量。 */
    private static long ink(BufferedImage img, int x0, int x1) {
        int from = Math.max(0, x0);
        int to = Math.min(img.getWidth(), x1);
        long sum = 0;
        for (int x = from; x < to; x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                sum += (img.getRGB(x, y) >>> 24) & 0xFF;
            }
        }
        return sum;
    }

    /** 最右侧有墨的列号；整幅空白时返回 -1。 */
    private static int rightmostInk(BufferedImage img) {
        for (int x = img.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < img.getHeight(); y++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static int scroll(Method m, double edge, int textW, int maxW, int lookahead)
            throws Exception {
        return (Integer) m.invoke(null, edge, textW, maxW, lookahead);
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage canvas = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        g.dispose();
        return fm;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
