package com.miniplayer;

/**
 * 迷你条的几何布局参数。
 *
 * <p>以前所有尺寸都是 {@link MiniPlayerWindow} 里的类级常量。现在只有一种形态：
 * 高度恒等于任务栏高度（用户要求"拖出后也和任务栏里一样大"），宽度与原先的大条相同。
 * 尺寸在启动时按任务栏高度算一次，停靠与拖出不再改变它，只改变位置。</p>
 *
 * <p>几何参数抽成不可变值对象，由 {@link MiniPlayerWindow#applyLayout(Layout)} 应用；
 * 任务栏高度变化（用户改设置）时才会重算。</p>
 */
final class Layout {

    final int shadow;

    /**
     * 曾用于窗口圆角形状，现在<b>不参与</b>窗口形状：内容不画背景，圆角没有视觉意义，
     * 却会把四角从窗口形状里裁掉，而被裁掉的像素收不到鼠标事件。窗口形状已改为满矩形，
     * 这个值仅作布局语义保留（封面圆角另见 {@link #coverRadius}）。
     */
    final int cornerRadius;

    final int contentW;
    final int contentH;
    final int windowW;
    final int windowH;

    /**
     * 封面左缘。左侧不再有拖动手柄，封面直接贴边，只留 3px 给抗锯齿。
     *
     * <p>以前这里是 {@code gripX + gripW + 6 = 21}，为三条竖杠让出位置；用户明确要求
     * 删掉手柄且不要留白，所以整条变紧凑，文字区与进度条也随之左移。</p>
     */
    final int coverX;

    final int coverSize;
    final int coverRadius;
    final int coverY;

    final int textX;
    final int textRight;
    final int textMaxW;
    final int titleBaseline;
    final int artistBaseline;

    final int ctrlY;
    final int prevCx;
    final int playCx;
    final int nextCx;
    final int playRadius;
    final int sideBtnBox;

    final int barX;
    final int barW;
    final int barY;
    final int barH;
    final int barHHover;

    /** 字号：按任务栏高度算，矮条必须降一档才塞得进 40px。 */
    final float titleSize;
    final float artistSize;
    final float timeSize;

    private Layout(int shadow, int cornerRadius,
                   int contentW, int contentH,
                   int coverX, int coverSize, int coverRadius, int coverY,
                   int textX, int textRight, int titleBaseline, int artistBaseline,
                   int ctrlY, int prevCx, int playCx, int nextCx,
                   int playRadius, int sideBtnBox,
                   int barX, int barW, int barY, int barH, int barHHover,
                   float titleSize, float artistSize, float timeSize) {
        this.shadow = shadow;
        this.cornerRadius = cornerRadius;
        this.contentW = contentW;
        this.contentH = contentH;
        this.windowW = contentW + shadow * 2;
        this.windowH = contentH + shadow * 2;
        this.coverX = coverX;
        this.coverSize = coverSize;
        this.coverRadius = coverRadius;
        this.coverY = coverY;
        this.textX = textX;
        this.textRight = textRight;
        this.textMaxW = textRight - textX;
        this.titleBaseline = titleBaseline;
        this.artistBaseline = artistBaseline;
        this.ctrlY = ctrlY;
        this.prevCx = prevCx;
        this.playCx = playCx;
        this.nextCx = nextCx;
        this.playRadius = playRadius;
        this.sideBtnBox = sideBtnBox;
        this.barX = barX;
        this.barW = barW;
        this.barY = barY;
        this.barH = barH;
        this.barHHover = barHHover;
        this.titleSize = titleSize;
        this.artistSize = artistSize;
        this.timeSize = timeSize;
    }

    /**
     * 唯一的运行期布局：高度等于任务栏高度，宽度与原先的大条相同。
     *
     * <p>停靠与拖出使用同一个尺寸——用户要求"拖出后也和任务栏里一样大"，
     * 所以尺寸只在启动时按任务栏高度算一次，之后不再变化。</p>
     *
     * <p>高度算法：{@code contentH = h - 2*shadow}。shadow 先取 4，
     * 任务栏特别矮（如 30px 小任务栏）时把 shadow 压到 2 再算，保证总高不超出任务栏。</p>
     *
     * @param taskbarHeight 任务栏像素高度（逻辑像素）
     */
    static Layout compactFor(int taskbarHeight) {
        int h = Math.max(24, taskbarHeight);
        int shadow = h >= 44 ? 4 : 2;
        int contentH = h - shadow * 2;
        // 文字区（歌名 / 歌词）按需求收窄四分之一：原来的 185 → 139，
        // 整条也跟着变短，右侧不留空档
        int contentW = 304;

        // 竖向参数按 contentH 等比收敛，保证 40px 与更矮的任务栏都不裁切
        int coverSize = clamp((int) Math.round(contentH * 0.80), 20, 34);
        int coverY = Math.max(1, (contentH - coverSize) / 2);

        // 左侧不再留拖动手柄：封面贴边，只留 3px 抗锯齿余量
        int coverX = 3;

        int titleBaseline = Math.max(11, (int) Math.round(contentH * 0.45));
        int artistBaseline = Math.min(contentH - 6, titleBaseline + 14);
        int barH = contentH >= 36 ? 2 : 1;
        int barY = contentH - barH - 2;

        // 播控三键贴着右边缘排，副按钮半宽 8、右边距 8
        int nextCx = contentW - 16;
        int playCx = nextCx - 36;
        int prevCx = playCx - 36;
        // 文字区从封面右侧 8px 开始，一直用到副按钮左侧
        int textX = coverX + coverSize + 8;
        int textRight = prevCx - 16;

        return new Layout(
                shadow, 10,
                contentW, contentH,
                coverX, coverSize, Math.max(4, coverSize / 4), coverY,
                textX, textRight, titleBaseline, artistBaseline,
                contentH / 2,
                prevCx, playCx, nextCx,
                13, 16,
                // 进度条与封面左缘对齐，整条宽度从左贴边一直到右留 8px 余量
                coverX, contentW - coverX - 8,
                barY, barH, barH + 2,
                13f, 10f, 9f);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 内容区左上角（= 只给抗锯齿留的余量，不再有投影）。 */
    int cardX() {
        return shadow;
    }

    int cardY() {
        return shadow;
    }
}
