package com.miniplayer;

import java.awt.Color;

/**
 * 迷你播放条配色。
 *
 * <p>无背景版本：控件直接浮在桌面上，<b>不绘制任何卡片底、描边或投影</b>，
 * 所以前景色必须自己保证在壁纸上可读——浅色主题用近黑，深色主题用白。
 * 这对应 {@code PluginConfig#themeMode}（跟随系统 / 浅色 / 深色），
 * 用户按自己桌面明暗选一套即可。</p>
 */
public final class Theme {

    /** 缺省主色种子（无封面时用它推导强调色）。 */
    public static final Color SEED_GREEN = new Color(0x8F, 0xB8, 0x6A);
    /** 青色系封面时的取色回退底色。 */
    public static final Color SEED_CYAN = new Color(0x4F, 0xB3, 0xBF);
    /** 深色主题参考底色，仅用于说明与占位渐变。 */
    public static final Color DARK_BASE = new Color(0x1F, 0x1F, 0x23);

    /** 深色主题（深色壁纸）用的白系前景。 */
    public static final Color TEXT_PRIMARY = new Color(0xFF, 0xFF, 0xFF);
    public static final Color TEXT_SECONDARY = new Color(0xFF, 0xFF, 0xFF, 0x99);
    public static final Color ICON_NORMAL = new Color(0xFF, 0xFF, 0xFF, 0xE6);

    /** 浅色主题（浅色壁纸）用的近黑前景。 */
    public static final Color INK = new Color(0x1F, 0x1F, 0x24);

    public static final Color ACCENT_HEART = new Color(0xFF, 0x4D, 0x6D);

    /** 窗口圆角（仅用于窗口形状与拖放指示框，不再画卡片）。 */
    public static final int CORNER_RADIUS = 16;

    private Theme() {
    }

    /**
     * 由封面主色推导一个"可用的强调色"，用于进度条填充。
     *
     * <p>不再当作背景色：浅色主题把亮度压到 0.42 一档（白底上看得清），
     * 深色主题抬到 0.72 一档（深底上看得清），色相跟随封面。</p>
     *
     * @param coverColor 封面主色，可为 null
     * @param dark       是否深色主题
     */
    public static Color baseColor(Color coverColor, boolean dark) {
        Color seed = coverColor == null ? SEED_GREEN : coverColor;
        float[] hsb = Color.RGBtoHSB(seed.getRed(), seed.getGreen(), seed.getBlue(), null);
        // 饱和度适中即可，太低会变成灰条，太高在壁纸上刺眼
        float s = Math.max(0.34f, Math.min(0.62f, hsb[1] * 1.05f));
        float b = dark ? 0.74f : 0.44f;
        return Color.getHSBColor(hsb[0], s, b);
    }

    /** 主文字色：浅色主题近黑，深色主题纯白。 */
    public static Color titleColor(boolean dark) {
        return dark ? TEXT_PRIMARY : INK;
    }

    /** 次要文字色（歌手名 / 歌词兜底）：比主文字淡一档。 */
    public static Color subtitleColor(boolean dark) {
        return dark ? TEXT_SECONDARY : new Color(0x1F, 0x1F, 0x24, 0xA6);
    }

    /** 图标色。 */
    public static Color iconColor(boolean dark) {
        return dark ? ICON_NORMAL : new Color(0x2A, 0x2A, 0x30);
    }

    /** 进度条底轨：半透明前景色，浅色主题是淡黑、深色主题是淡白。 */
    public static Color trackColor(boolean dark) {
        return dark ? new Color(0xFF, 0xFF, 0xFF, 0x3D) : new Color(0x1F, 0x1F, 0x24, 0x24);
    }

    /** 播放/暂停键的圆底：低透明度，浅色主题用黑、深色主题用白。 */
    public static Color playButtonBackground(boolean dark) {
        return dark ? new Color(0xFF, 0xFF, 0xFF, 0x33) : new Color(0x1F, 0x1F, 0x24, 0x26);
    }

    /** 播放/暂停键的字形色，与圆底明暗相反。 */
    public static Color playButtonGlyph(boolean dark) {
        return dark ? TEXT_PRIMARY : INK;
    }

    /** 封面占位块的上/下渐变：中性深灰，不随封面色变化。 */
    public static Color coverPlaceholderTop() {
        return new Color(0x44, 0x44, 0x4C);
    }

    public static Color coverPlaceholderBottom() {
        return new Color(0x24, 0x24, 0x2A);
    }

    public static Color blend(Color a, Color b, float ratio) {
        float r = clamp01(ratio);
        int red = Math.round(a.getRed() * (1 - r) + b.getRed() * r);
        int green = Math.round(a.getGreen() * (1 - r) + b.getGreen() * r);
        int blue = Math.round(a.getBlue() * (1 - r) + b.getBlue() * r);
        int alpha = Math.round(a.getAlpha() * (1 - r) + b.getAlpha() * r);
        return new Color(clamp255(red), clamp255(green), clamp255(blue), clamp255(alpha));
    }

    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(alpha));
    }

    /**
     * 按比例收缩颜色自带的 alpha，而不是覆盖它。
     *
     * <p>控件淡入淡出时要用这个：颜色本身可能已经是半透明（如底轨的 0x24），
     * 直接 {@link #withAlpha} 会把它变成不透明，视觉上就是"淡出失败"。</p>
     *
     * @param factor 0..1 的额外不透明度
     */
    public static Color fade(Color c, float factor) {
        return withAlpha(c, Math.round(c.getAlpha() * clamp01(factor)));
    }

    /**
     * 取图片的平均主色（按面积加权，跳过近黑/近白像素）。
     *
     * @param pixels ARGB 像素数组
     * @return 主色，无法判断时返回 null
     */
    public static Color dominantColor(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return null;
        }
        long sumR = 0, sumG = 0, sumB = 0, count = 0;
        int step = Math.max(1, pixels.length / 4096);
        for (int i = 0; i < pixels.length; i += step) {
            int argb = pixels[i];
            int alpha = (argb >>> 24) & 0xFF;
            if (alpha < 200) {
                continue;
            }
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            // 跳过接近纯黑与纯白（封面上的文字/边框）
            if (max < 28 || min > 232) {
                continue;
            }
            // 跳过几乎无彩度的像素
            if (max - min < 12) {
                continue;
            }
            sumR += r;
            sumG += g;
            sumB += b;
            count++;
        }
        if (count < 16) {
            return null;
        }
        return new Color(clamp255((int) (sumR / count)), clamp255((int) (sumG / count)),
                clamp255((int) (sumB / count)));
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
