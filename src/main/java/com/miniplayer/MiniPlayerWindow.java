package com.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State;
import com.xuncorp.spw.workshop.api.WorkshopApi;

import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面迷你播放条：无边框、可拖动、可置顶的悬浮控件。
 *
 * <p>布局为上下两层：上层左侧圆角封面，右侧两行文字（歌名 / 歌手），
 * 右侧播控三键；下层通栏细进度条，可拖动跳转。鼠标移开后交叉淡入"封面 + 当前歌词"。
 * 没有卡片背景，窗口除前景元素外完全透明。</p>
 *
 * <p>尺寸只有一种：高度恒等于任务栏高度（见 {@link Layout#compactFor(int)}），
 * 停靠与拖出都不改变它。</p>
 */
public final class MiniPlayerWindow extends JWindow {

    // 几何参数（留白、封面、文字基线、播控圆心、进度条）全部在 {@link Layout} 里，
    // 运行期尺寸只有一种：高度恒等于任务栏高度。这里不再保留类级尺寸常量，
    // 避免出现"常量写着 76 而实际画在 40 高"这类不一致。

    private static final int HIT_NONE = 0;
    private static final int HIT_PREV = 1;
    private static final int HIT_PLAY = 2;
    private static final int HIT_NEXT = 3;

    /** 贴边吸附阈值。 */
    private static final int SNAP_DISTANCE = 8;
    /**
     * 指针移动超过这个距离才算"拖动"，否则当成一次点击。
     *
     * <p>整条都能按住拖动之后必须区分这两者：单击文字区不该触发吸附/停靠，
     * 也不该把封面双击（唤起宿主）吞掉。</p>
     */
    private static final int DRAG_THRESHOLD_PX = 4;
    /** 拖动时距任务栏多近就点亮虚线框（停靠预览）。 */
    private static final int DOCK_ATTRACT_PX = 24;
    /**
     * 拿不到任务栏高度时的兜底高度（逻辑像素）。
     *
     * <p>窗口尺寸始终等于任务栏高度，任务栏自动隐藏或全屏时探测不到，
     * 用 Windows 默认任务栏高度，保证控件不会突然变成另一种尺寸。</p>
     */
    static final int DEFAULT_TASKBAR_HEIGHT = 48;
    /** 鼠标离开后多少毫秒隐藏控制按钮。 */
    private static final int AUTO_HIDE_DELAY_MS = 450;
    /** 渲染帧间隔：有歌词/在播放时用 60fps，其余降到 30fps 省电。 */
    private static final int TICK_FAST_MS = 16;
    private static final int TICK_IDLE_MS = 33;
    /**
     * 卡拉 OK 边界的提前量（毫秒）。
     *
     * <p>宿主给的时间轴通常贴着音频后半段，播放位置走到某个字的时间戳时，
     * 那个字听起来已经唱掉一半了，观感就是"整句慢一两个字"。这里把用于求边界
     * 的位置整体往前挪一点，让黑边比音频早到一点点——宁可早到一点，也不要追着跑。
     * 只影响句内边界，<b>不影响换行时机</b>（换行由行 startTime 决定）。</p>
     */
    private static final long KARAOKE_LEAD_MS = 200L;
    /**
     * 超宽歌词行滚动时，演唱边界离可视右边缘留出的余量（像素）。
     *
     * <p>边界一旦贴到最右边，下一个字就看不见了；留一点余量，让"正在唱的那个字"
     * 完整可见。1.0.33 起归零——真正的提前量交给 {@link #LYRIC_SCROLL_LOOKAHEAD_PX}
     * 那种"向前看"的位移，而不是"提前刹车"的余量。留余量只会让行尾那截更滚不到。</p>
     */
    private static final double LYRIC_SCROLL_MARGIN = 0d;
    /**
     * 滚动提前量：按演唱边界算位移时，额外往右多看一个字宽。
     *
     * <p>这才是"一句歌词最后一截滚不到"的真正修法。根因不在平滑滞后，而在**时间分配**：
     * 最后一个 cell 的结束时刻就等于本行结束时刻，边界要等到那一刻才推进到行尾，
     * 而画面在同一刻已经换行——最后那一截在"本行"这段时间里压根没有出现过。
     * 之前用 {@code LYRIC_SCROLL_FINISH_MS} 在末尾 400ms 内把滚动量硬顶到上限，
     * 结果整行 100 多像素一次性窜过去，看起来像"提前进了下一句"（用户反馈）。</p>
     *
     * <p>改成让位移永远比边界快一个汉字宽：边界距行尾还差一个字时，行尾就已经完整
     * 露出来并停住，最后那个字边亮边被看清，然后才换行。滚动全程速度不变，
     * 不会有突然的冲刺。实际宽度取 {@code FontMetrics.charWidth('字')}，
     * 由 {@link #paintLyric} 传进来；这里只是语义常量与兜底值。</p>
     */
    private static final int LYRIC_SCROLL_LOOKAHEAD_PX = 12;
    /**
     * 无时间轴可用时，超宽行匀速循环滚动的速度（像素/毫秒）。
     *
     * <p>沿用歌名跑马灯的 0.028，两者在同一屏上运动速度才一致。</p>
     */
    private static final float LYRIC_MARQUEE_SPEED = 0.028f;
    /** 匀速循环回绕前额外走过的空白（像素），让句末停顿一下再从头开始。 */
    private static final float LYRIC_MARQUEE_GAP = 40f;
    /** 主动轮询当前曲目的间隔。 */
    private static final int POLL_MS = 1000;
    /**
     * 层级校准间隔。
     *
     * <p>以前这里是 250ms 一次 {@code toFront()} 的"置顶保活"，那正是用户报的
     * "窗口一闪一闪"的来源：{@code toFront()} 在 Windows 上就是一次
     * {@code SetWindowPos(HWND_TOP)}，每 250ms 重排一次 z 序，任务栏这种同属
     * TOPMOST 的系统窗口会跟着抖。</p>
     *
     * <p>现在改成 1 秒一次的<b>只读检查</b>：查任务栏有没有压在我们上面、查前台
     * 是不是全屏应用，只有结论真的变了才动一次 {@code SetWindowPos}。TOPMOST
     * 窗口挂上之后本来就不会自己掉下来，不需要保活（探针连续采样 5 秒验证过）。</p>
     */
    private static final int LAYER_CHECK_MS = 1000;
    /**
     * 层内提权的熔断次数。
     *
     * <p>{@link WindowLayer#raiseWithinTopmost} 是"被判到被任务栏压住"时的补救动作。
     * 如果判据本身出了错（历史上错过两次），这个动作会退化成每秒提权一次，同样表现为
     * 闪烁。所以连续失败到第 {@code 5} 次就彻底停手，只喊一次日志——宁可层级不对，
     * 也不要抖。</p>
     */
    private static final int LAYER_RAISE_LIMIT = 5;

    private static MiniPlayerWindow instance;

    private final PluginConfig config;

    private PlayerState state = PlayerState.empty();
    /** 轮询失败只记一次日志，避免每秒刷屏。 */
    private boolean pollFailureLogged;
    private BufferedImage cover;
    private String coverTrackId;
    private Color baseColor = Theme.SEED_GREEN;
    private Color coverSeed;

    private boolean hoverBar;
    private boolean hoverWindow;
    private boolean dragging;
    private boolean pressingOnBar;
    private Point dragOffset;
    private Point pressPoint;
    /** 按下时的窗口左上角与指针屏幕坐标：拖动按「起点 + 总位移」定位，避免逐帧累加导致越拖越快。 */
    private Point dragStartLocation;
    private int pressScreenX;
    private int pressScreenY;
    /** 本次按住期间指针是否真的移动过（超过 {@link #DRAG_THRESHOLD_PX}）。 */
    private boolean dragMoved;
    private boolean menuOpen;
    /** 当前展开的右键菜单：{@code popupMenuWillBecomeInvisible} 偶发不触发时用它判断是否真的还开着。 */
    private JPopupMenu activeMenu;

    /**
     * 当前几何布局。
     *
     * <p>只有一种形态：高度对齐任务栏的紧凑矮条。停靠与拖出<b>不再改变尺寸</b>，
     * 只改变位置——用户要求"拖出后和任务栏里保持一样大"。</p>
     */
    private Layout layout;
    /** 是否已停靠进任务栏（只影响位置与标志，不影响尺寸）。 */
    private boolean docked;
    /** 拖动中是否已经锁定停靠目标（虚线框点亮）。 */
    private boolean dockPreviewActive;
    /** 停靠预览虚线框，仅在拖动靠近任务栏时显示。 */
    private DropIndicator indicator;

    /** 控制按钮显隐：1 完全可见，0 完全隐藏。 */
    private float controlsAlpha = 1f;
    /** 播控三键的透明度：歌词态也常显（{@link #keepButtonsWithLyric()} 恒为真），因此通常独立于 {@link #controlsAlpha}。 */
    private float buttonsAlpha = 1f;
    private long lastInteractionAt = System.currentTimeMillis();
    /** 上一帧的单调时钟（纳秒）。用 nanoTime 而不是 currentTimeMillis：后者会被系统对时拉动。 */
    private long lastTickNs;
    /** 帧间隔换算成整毫秒后的余量（纳秒），攒够 1ms 再补进去，避免 16.67ms 的帧把时间吃掉。 */
    private long nsCarry;
    private long lastPollAt;
    /**
     * 宿主最近一次上报的播放位置与收到它的时刻。
     *
     * <p>宿主回调频率不可控（可能 100ms 一次，也可能 1s 一次）。把这个值当作
     * "锚点"，每帧按本地流逝时间外推成目标位置，再让 {@code positionMs} 限速追上去，
     * 进度就会一直单调前进——校准只改速度，不改位置，肉眼看不到回退。</p>
     */
    private long hostPosMs;
    private long hostPosAtNs;
    private boolean hostPosValid;
    /**
     * 宿主上报的位置是不是确实在往前走。
     *
     * <p>只靠 {@code state.playing} 判断"该不该推进"不够：有的宿主先发
     * {@code State.Ready}、晚一步才发 {@code onIsPlayingChanged(true)}，这一段窗口
     * 会被误判成暂停；只靠"最近收到过位置"也不够，暂停后宿主仍会零星补发。
     * 这里把"位置真的变大了"当作独立证据，与 {@code state.playing} 取或。</p>
     */
    private boolean hostAdvancing;
    /**
     * 宿主最近一次的播放状态枚举（{@code Idle / Buffering / Ready / Ended}）。
     *
     * <p>{@link PlayerState} 只保留 {@code playing}/{@code buffering} 两个布尔，
     * 分不出"暂停"和"播完了"——两者都是 {@code playing == false}。要按用户要求
     * 做到「只有暂停时隐藏、播完不隐藏」，必须自己记住原始枚举。</p>
     */
    private State lastHostState = State.Idle;
    /** 本窗口生命周期内是否播放过；没播过就谈不上"暂停"，避免一装好条子就自己消失。 */
    private boolean hasEverPlayed;
    /**
     * 当前这次隐藏是不是「因为暂停」造成的。
     *
     * <p>只有这种隐藏才由恢复播放自动撤销；用户从右键菜单手动隐藏的，不该被
     * 下一首歌的播放事件弹回来。</p>
     */
    private boolean hidePausedHandled;
    /** 上次检查窗口层级的时间（{@link #LAYER_CHECK_MS} 节流）。 */
    private long lastLayerCheckAt;
    /**
     * 上一次校准时的"期望层级"：{@code true} 表示应该挂 TOPMOST。
     *
     * <p>用它做差分——只有期望值真的翻转（进出全屏、切换勾选）才换层。这正是从
     * "每 250ms 无条件重排"改成"只在必要时重排"的关键。</p>
     */
    private boolean layerWantedTopmost = true;
    /** 迷你条自己的 HWND，0 表示还没取到（FFM 不可用或窗口还没显示）。 */
    private long selfHwnd;
    /** 层级校准累计执行次数，日志里用来确认机制真的在跑。 */
    private int layerSyncs;
    /**
     * 连续层内提权尝试次数，用于 {@link #LAYER_RAISE_LIMIT} 熔断。
     *
     * <p>提到成功、换层成功、或者根本没被压住时都归零。到了上限就不再尝试，
     * 避免判据出错时退化成每秒提权。</p>
     */
    private int layerRaiseAttempts;
    /** 同一时刻只允许一个宿主激活线程（连点封面时后续点击直接忽略）。 */
    private final AtomicBoolean hostActivating = new AtomicBoolean();

    /** 整首歌的歌词行；宿主一次性推来，用于按播放位置兜底匹配。 */
    private List<LyricsLine> lyricsLines = Collections.emptyList();
    /** 当前演唱到的歌词行。 */
    private LyricsLine currentLyric;
    /** 歌词主行字体（当前正在唱的那行），按布局内容高动态计算。 */
    private Font lyricFont;
    /** 歌词副行字体（下一句预告），比主行小一档、颜色更淡。 */
    private Font lyricNextFont;

    /** 卡拉 OK 边界的上一个实际绘制位置：用来做指数平滑，抹掉 1–2px 的残余抖动。 */
    private double edgeShown = -1d;
    /** {@link #edgeShown} 属于哪一行；换行时边界要直接吸附，不能从上一行末尾滑过来。 */
    private String edgeForLine;
    /** 本帧走的是时间轴路径还是整行匀速路径，只给诊断日志用。 */
    private boolean karaokeByCells;
    /** 诊断：帧数、边界单帧最大位移、位置回退次数。 */
    private int diagFrames;
    private double diagEdgeJumpMax;
    private int diagPosBackward;
    private long diagSince;
    /** 歌词滚动诊断：上一行文本与上次打印时刻（换行必打，之后每 2 秒一次）。 */
    private String diagScrollForLine;
    private long diagScrollAt;

    /** 跑马灯状态。 */
    private float marqueeOffset;
    private boolean marqueeNeeded;

    /**
     * 歌词主行当前的水平滚动量（像素，≥0）。
     *
     * <p>不是累加量，而是每帧由演唱边界推导出来：{@code scroll} 只会跟着 {@code edge}
     * 走，seek 回退时自然跟着回落，不会漂移。</p>
     */
    private int lyricScroll;
    /** {@link #lyricScroll} 属于哪一行文本：换行时滚动要归零，不能把上一行的偏移带过来。 */
    private String lyricScrollForLine;
    /** 无时间轴时匀速循环滚动的起点（纳秒），换行时重置。 */
    private long lyricMarqueeStartNs;
    /** 本帧主行是否发生了滚动：{@link #onTick} 用它维持 60fps 与逐帧重绘。 */
    private boolean lyricScrolling;

    private Font titleFont;
    private Font artistFont;
    private Font timeFont;
    private final Font miniFont;

    private final Timer tickTimer;

    /** 自绘 + 接收鼠标事件的唯一组件，保证事件坐标系与绘制坐标系一致。 */
    private final Surface surface;

    // ---------------------------------------------------------------- 构造与单例

    private MiniPlayerWindow(GraphicsConfiguration gc, PluginConfig config) {
        super(gc);
        this.config = config;

        // 尺寸只有一种：高度对齐任务栏。必须早于 applyFonts()，字号由布局决定。
        layout = initialLayout();

        miniFont = pickFont(Font.BOLD, 17f);
        applyFonts();

        setBackground(new Color(0, 0, 0, 0));
        setSize(layout.windowW, layout.windowH);
        // 常态就应该挂在 TOPMOST 层（这是"压住任务栏"的前提）。先让 Java 侧把
        // 初始层级摆对，窗口一出现就不会先闪一下普通层；之后就交给
        // WindowLayer 用 SetWindowPos 精细控制（那里还能区分"任务栏"与"全屏应用"，
        // 而 setAlwaysOnTop 只有"全盖"和"全不盖"两态）。
        setAlwaysOnTop(true);
        try {
            // 非激活窗口：不抢宿主焦点
            setFocusableWindowState(false);
        } catch (Throwable ignored) {
            // 极老 JVM 无此 API
        }

        surface = new Surface();
        surface.setSize(layout.windowW, layout.windowH);
        surface.setPreferredSize(new java.awt.Dimension(layout.windowW, layout.windowH));
        setContentPane(surface);
        installMouseHandlers();
        applyShape();

        tickTimer = new Timer(TICK_IDLE_MS, e -> onTick());
        tickTimer.setCoalesce(true);
        baseColor = Theme.baseColor(null, isDark());
        applyInitialPosition();
    }

    /** 按当前布局重算字号（布局变了字号档位也要跟着变）。 */
    private void applyFonts() {
        titleFont = pickFont(Font.BOLD, layout.titleSize);
        artistFont = pickFont(Font.PLAIN, layout.artistSize);
        timeFont = pickFont(Font.PLAIN, layout.timeSize);
        // 歌词要放两行，字号比单行时再压一档：主行 11、副行 10（40px 内容区）
        lyricFont = pickFont(Font.BOLD, Math.max(11f, layout.coverSize * 0.34f));
        lyricNextFont = pickFont(Font.PLAIN, Math.max(10f, layout.coverSize * 0.30f));
        updateMarqueeNeeded();
    }

    /**
     * 重建窗口形状。
     *
     * <p>形状是矩形（半径为 0）：内容本身不画背景，圆角没有视觉意义，却会把四角那点子区域
     * 从窗口形状里裁掉——被裁掉的像素同样收不到鼠标事件，与 per-pixel alpha 穿透是同一类问题。
     * 仍然调 {@code setShape} 而不是删掉调用，是为了让窗口继续走形状化路径（per-pixel 合成），
     * 不至于在部分平台上退化成不透明黑块。</p>
     */
    private void applyShape() {
        try {
            setShape(new RoundRectangle2D.Double(0, 0, layout.windowW, layout.windowH, 0, 0));
        } catch (Throwable ignored) {
            // 部分环境不支持窗口形状，退化为矩形窗口
        }
    }

    /**
     * 切换布局。这是<b>唯一</b>允许改变窗口尺寸的入口：尺寸在窗口、自绘面板、
     * 窗口形状三处冗余保存，分开改必然漏掉一处。
     */
    void applyLayout(Layout next) {
        if (next == null) {
            return;
        }
        boolean sameSize = layout != null
                && next.windowW == layout.windowW && next.windowH == layout.windowH;
        layout = next;
        if (sameSize) {
            // 尺寸没变（同一个任务栏高度）就只更新参数，避免 setSize 引起闪烁
            applyFonts();
            repaint();
            return;
        }
        setSize(layout.windowW, layout.windowH);
        if (surface != null) {
            surface.setSize(layout.windowW, layout.windowH);
            surface.setPreferredSize(new java.awt.Dimension(layout.windowW, layout.windowH));
            surface.revalidate();
        }
        applyShape();
        applyFonts();
        repaint();
    }

    /**
     * 唯一的初始布局：高度等于任务栏高度。
     *
     * <p>拿不到任务栏（自动隐藏、全屏）时按 {@link #DEFAULT_TASKBAR_HEIGHT} 算，
     * 保证任何时候窗口都是"任务栏那样大"，拖出与停靠尺寸一致。</p>
     */
    private Layout initialLayout() {
        Rectangle bar = taskbarRect();
        int h = bar != null ? bar.height : DEFAULT_TASKBAR_HEIGHT;
        return Layout.compactFor(h);
    }

    /** 当前布局。名字不能叫 {@code layout()}——会与 {@code Container.layout()} 冲突。 */
    Layout currentLayout() {
        return layout;
    }

    /**
     * 建窗并返回实例。
     *
     * <p>插件 {@code start()} 跑在 PF4J 自己的线程上，不是 EDT，而 Swing 组件必须在 EDT 上创建。
     * 这里用 {@code invokeAndWait} <b>同步</b>等待：早先用 {@code invokeLater} 会立刻返回，
     * 调用方紧接着读 {@code instance()} 拿到 null，窗口就永远建不出来。</p>
     */
    static MiniPlayerWindow ensureCreated(PluginConfig config) {
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                SwingUtilities.invokeAndWait(() -> ensureCreated(config));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                MiniPlayerPlugin.log("ensureCreated interrupted");
            } catch (java.lang.reflect.InvocationTargetException e) {
                MiniPlayerPlugin.log("ensureCreated failed: "
                        + MiniPlayerPlugin.describe(e.getTargetException()));
            }
            return instance;
        }
        if (instance != null) {
            return instance;
        }
        try {
            instance = new MiniPlayerWindow(defaultConfiguration(), config);
            MiniPlayerPlugin.log("window created at " + instance.getLocation()
                    + " size " + instance.getSize());
        } catch (Throwable t) {
            MiniPlayerPlugin.log("window create failed: " + MiniPlayerPlugin.describe(t));
            instance = null;
        }
        return instance;
    }

    static MiniPlayerWindow instance() {
        return instance;
    }

    /** 供扩展点回调使用的无锁读取。 */
    static MiniPlayerWindow peek() {
        return instance;
    }

    /**
     * 清空单例。
     *
     * <p>同样必须同步：{@code stop()} 之后宿主可能立刻再调 {@code start()}，
     * 若清理排队到 EDT 后面才执行，新一轮会捡到一个已经 dispose 的窗口。</p>
     */
    static void reset() {
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                SwingUtilities.invokeAndWait(MiniPlayerWindow::reset);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                MiniPlayerPlugin.log("reset failed: "
                        + MiniPlayerPlugin.describe(e.getTargetException()));
            }
            return;
        }
        MiniPlayerWindow w = instance;
        instance = null;
        if (w != null) {
            w.stopTimers();
        }
    }

    /** 当前是否深色配色，统一从配置读取（含跟随系统）。 */
    private boolean isDark() {
        return config != null && config.isDark();
    }

    private static GraphicsConfiguration defaultConfiguration() {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice device = env.getDefaultScreenDevice();
        GraphicsConfiguration gc = device.getDefaultConfiguration();
        if (gc.isTranslucencyCapable()) {
            return gc;
        }
        for (GraphicsConfiguration candidate : device.getConfigurations()) {
            if (candidate.isTranslucencyCapable()) {
                return candidate;
            }
        }
        return gc;
    }

    private static Font pickFont(int style, float size) {
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC"};
        for (String name : candidates) {
            Font f = new Font(name, style, Math.round(size));
            if (f.getFamily().equalsIgnoreCase(name)) {
                return f.deriveFont(style, size);
            }
        }
        return new Font(Font.DIALOG, style, Math.round(size));
    }

    // ---------------------------------------------------------------- 窗口生命周期

    /**
     * 优先恢复上次位置（含停靠态），否则放到主屏右下角并避开任务栏。
     *
     * <p>尺寸在构造时已按任务栏高度定好，这里只决定<b>位置</b>，
     * 所以停靠与否不会导致窗口变大变小。</p>
     */
    void applyInitialPosition() {
        Rectangle bar = taskbarRect();
        if (config.docked && bar != null) {
            docked = true;
            // 用上次记住的坐标，而不是 getX()：构造函数里还没 setLocation，
            // getX()/getY() 恒为 0，取它会让停靠目标被夹到任务栏最左边。
            // 没有任何记录时（首次就以停靠态启动）退到任务栏中心，也别贴最左。
            int preferX = config.windowX >= 0 ? config.windowX : bar.x + bar.width / 2;
            int preferY = config.windowY >= 0 ? config.windowY : bar.y + bar.height / 2;
            Rectangle target = dockTargetRect(bar, preferX, preferY);
            setLocation(target.x, target.y);
            rememberPosition();
            return;
        }
        if (config.docked) {
            // 上次是停靠态但现在拿不到任务栏（换了屏 / 任务栏自动隐藏）：只清标志，尺寸不变
            config.docked = false;
        }
        docked = false;
        if (config.windowX >= 0 && config.windowY >= 0
                && visibleAreaIntersects(config.windowX, config.windowY)) {
            setLocation(config.windowX, config.windowY);
            return;
        }
        // 首次出现：右下角、任务栏上方
        Rectangle bounds = usableScreenBounds();
        int x = bounds.x + bounds.width - layout.windowW - 24;
        int y = bounds.y + bounds.height - layout.windowH - 24;
        setLocation(Math.max(bounds.x, x), Math.max(bounds.y, y));
        rememberPosition();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            startTimers();
            toFront();
        } else {
            stopTimers();
        }
    }

    /** 显示窗口；同步执行，保证 {@code start()} 返回时窗口已经在屏幕上。 */
    void showWindow() {
        runOnEdtSync(() -> {
            if (!isVisible()) {
                setVisible(true);
            }
            toFront();
            // 窗口重新显示后 HWND 可能变了（销毁重建），层级判定也要从头再来
            selfHwnd = 0L;
            lastLayerCheckAt = 0L;
            repaint();
            pollNow();
        });
    }

    /** 销毁窗口；同步执行，避免宿主紧接着的 start() 捡到半销毁的实例。 */
    void disposeWindow() {
        runOnEdtSync(() -> {
            MiniPlayerPlugin.log("window disposed");
            stopTimers();
            hideIndicator();
            if (indicator != null) {
                indicator.dispose();
                indicator = null;
            }
            rememberPosition();
            config.docked = isDocked();
            config.save();
            setVisible(false);
            dispose();
        });
    }

    boolean isShowingMini() {
        return isVisible();
    }

    private void startTimers() {
        lastTickNs = System.nanoTime();
        nsCarry = 0L;
        if (!tickTimer.isRunning()) {
            tickTimer.start();
        }
    }

    private void stopTimers() {
        tickTimer.stop();
    }

    // ---------------------------------------------------------------- 扩展点回调

    void onMediaItemChanged(MediaItem item) {
        if (item == null) {
            return;
        }
        runOnEdt(() -> {
            // 换歌先清掉上一首的歌词，否则宿主推新歌词前会短暂显示旧歌词。
            lyricsLines = Collections.emptyList();
            currentLyric = null;
            // 上一首的位置锚点作废：留着它会让新歌第一帧就把进度条顶到末尾
            hostPosValid = false;
            hostPosMs = 0L;
            nsCarry = 0L;
            state = state.with(item);
            baseColor = Theme.baseColor(coverSeed, isDark());
            updateMarqueeNeeded();
            repaint();
            loadCover(item.getId());
        });
    }

    void onIsPlayingChanged(boolean playing) {
        runOnEdt(() -> {
            state = state.withPlaying(playing);
            hostAdvancing = playing;
            if (playing) {
                hasEverPlayed = true;
            }
            lastTickNs = System.nanoTime();
            nsCarry = 0L;
            // 暂停/恢复的瞬间重新锚定：恢复播放时用当前时刻当基准，避免把暂停时长算进去
            hostPosMs = state.positionMs;
            hostPosAtNs = System.nanoTime();
            hostPosValid = true;
            applyPausedVisibility();
            repaint();
        });
    }

    void onStateChanged(State s) {
        runOnEdt(() -> {
            lastHostState = s;
            state = state.withState(s);
            hostAdvancing = state.playing;
            if (state.playing) {
                hasEverPlayed = true;
            }
            lastTickNs = System.nanoTime();
            nsCarry = 0L;
            hostPosMs = state.positionMs;
            hostPosAtNs = System.nanoTime();
            applyPausedVisibility();
            repaint();
        });
    }

    /**
     * 宿主上报播放位置。
     *
     * <p>这里**不直接改** {@code positionMs}，只把它当锚点存下来。真正的推进在
     * {@link #onTick()} 里按本地流逝时间外推、再限速追随——宿主回调频率再低、
     * 再来回抖动，进度也不会突然倒退或前跳。</p>
     *
     * <p>只有偏差超过 300ms（换歌、seek、刚启动）才直接对齐，那种情况用户本来就
     * 预期画面跳一下。</p>
     */
    void onPositionUpdated(long position) {
        runOnEdt(() -> {
            if (dragging || pressingOnBar) {
                return;
            }
            long nowNs = System.nanoTime();
            // 位置变大 → 宿主确实在播；变小 → seek/回绕，先当作"不确定"处理
            if (position > hostPosMs + 5L) {
                hostAdvancing = true;
            } else if (position + 5L < hostPosMs) {
                hostAdvancing = false;
            }
            hostPosMs = position;
            hostPosAtNs = nowNs;
            hostPosValid = true;
            long diff = position - state.positionMs;
            if (diff < -300L || diff > 300L) {
                state = state.withPosition(position);
                lastTickNs = System.nanoTime();
                nsCarry = 0L;
            } else if (diff < -20L) {
                // 只统计"往回跳"：连续倒退就是宿主时钟抖动的证据
                diagPosBackward++;
            }
            repaint();
        });
    }

    // ---------------------------------------------------------------- 定时任务

    private void onTick() {
        long nowNs = System.nanoTime();
        long now = System.currentTimeMillis();
        long spanNs = Math.max(0L, Math.min(500_000_000L, nowNs - lastTickNs)) + nsCarry;
        lastTickNs = nowNs;
        // 16ms 的帧换成毫秒会丢掉 0.67ms，攒起来再补齐，否则 60fps 下进度会越跑越慢
        long delta = spanNs / 1_000_000L;
        nsCarry = spanNs - delta * 1_000_000L;

        boolean dirty = false;

        // 1) 播放中推进进度：以宿主上报的位置为锚点，按本地流逝时间外推，再限速追随。
        //    这样校准只改速度不改位置，既不会每秒倒一下，也不会卡顿后一直落后。
        //
        //    能不能推进不能只看 state.playing：
        //    - 有的宿主先发 State.Ready、晚一拍才发 onIsPlayingChanged(true)，中间会被误判成暂停；
        //    - 有的宿主根本不给时长（getDuration()==0），旧逻辑里 durationMs>0 这个前置条件
        //      会让整段推进永远不执行，位置只能靠每秒一次的 onPositionUpdated 跳一下，
        //      画面上就是边界一顿一顿地闪——这正是要修的那个 bug。
        //    所以改为"宿主说在播"或"位置确实在前进且最近两秒还有回调"二者取或；
        //    时长只在拿得到时才用来钳制上界。
        boolean hostFresh = hostPosValid && nowNs - hostPosAtNs < 2_000_000_000L;
        boolean advancing = state.playing || (hostAdvancing && hostFresh);
        if (advancing && !pressingOnBar && !dragging) {
            long next;
            if (hostPosValid) {
                double elapsedMs = (nowNs - hostPosAtNs) / 1_000_000.0;
                double target = hostPosMs + elapsedMs;
                double cur = state.positionMs;
                double diff = target - cur;
                // 每帧最多追 1.6 倍真实时间 + 2ms：足够快，也快到看不出跳
                double maxStep = delta * 1.6 + 2.0;
                next = Math.round(Math.abs(diff) <= maxStep ? target : cur + Math.signum(diff) * maxStep);
            } else {
                next = state.positionMs + delta;
            }
            // 宿主没给时长时不做上界钳制，否则位置会被 0 钉死
            if (state.durationMs > 0L) {
                next = Math.min(state.durationMs, next);
            }
            next = Math.max(0L, next);
            if (next != state.positionMs) {
                state = state.withPosition(next);
                dirty = true;
            }
        }

        // 2) 文字区在「歌名+歌手」与「歌词」之间交叉淡入；没有歌词可显示时保持完整布局，
        //    否则整条会只剩一个封面，窗口看起来是空的
        float target = (shouldShowControls() || !hasLyric()) ? 1f : 0f;
        if (Math.abs(controlsAlpha - target) > 0.01f) {
            float step = (float) delta / 160f;
            controlsAlpha = controlsAlpha < target
                    ? Math.min(target, controlsAlpha + step)
                    : Math.max(target, controlsAlpha - step);
            dirty = true;
        } else if (controlsAlpha != target) {
            controlsAlpha = target;
            dirty = true;
        }

        // 2b) 播控三键单独一份透明度：歌词态也常显（keepButtonsWithLyric 恒为真）
        float buttonsTarget = (shouldShowControls() || keepButtonsWithLyric()) ? 1f : 0f;
        if (Math.abs(buttonsAlpha - buttonsTarget) > 0.01f) {
            float step = (float) delta / 160f;
            buttonsAlpha = buttonsAlpha < buttonsTarget
                    ? Math.min(buttonsTarget, buttonsAlpha + step)
                    : Math.max(buttonsTarget, buttonsAlpha - step);
            dirty = true;
        } else if (buttonsAlpha != buttonsTarget) {
            buttonsAlpha = buttonsTarget;
            dirty = true;
        }

        // 3) 跑马灯
        if (marqueeNeeded && shouldShowControls()) {
            marqueeOffset += delta * 0.028f;
            FontMetrics fm = getFontMetrics(titleFont);
            float span = fm.stringWidth(state.title) + 24f;
            if (marqueeOffset > span) {
                marqueeOffset = -layout.textMaxW;
            }
            dirty = true;
        } else if (marqueeOffset != 0f) {
            marqueeOffset = 0f;
            dirty = true;
        }

        // 4) 兜底轮询：宿主偶尔漏发曲目变更事件
        if (now - lastPollAt >= POLL_MS) {
            lastPollAt = now;
            pollNow();
            // 停靠态下顺带校验任务栏是否还托着我们：换屏、改任务栏位置、
            // 任务栏自动隐藏都会让矮条悬在半空，这时自动抽出来
            verifyDockedStillValid();
        }

        // 5) 层级校准：常态压住任务栏，前台是全屏应用时让位。
        //    内部按 LAYER_CHECK_MS（1 秒）节流，且只在结论变化时才动一次 z 序。
        syncLayer();

        // 5b) 离屏自愈：拔显示器、改分辨率、任务栏换边都可能把矮条丢到屏幕外，
        //     这时用户完全看不见它，看起来就是"悬浮窗消失了"（recenter 内部会
        //     顺带刷新记忆坐标并落盘）
        if (!visibleAreaIntersects(getX(), getY())) {
            MiniPlayerPlugin.log("window offscreen at " + getX() + "," + getY()
                    + ", recentering");
            recenter();
        }

        // 6) 坐标变更合并落盘
        if (config.isDirty()) {
            config.save();
        }

        // 7) 变帧率：歌词可见且在推进时跑 60fps，其余降到 30fps
        //    "在推进"要用 advancing 而不是 state.playing——宿主漏发 playing 时
        //    仍要按 60fps 出帧，否则 33ms 的节拍本身就会让边界一步一格。
        //    歌词在匀速循环滚动时与位置无关（暂停也在滚），同样要 60fps。
        int wantDelay = (hasLyric() && (advancing || lyricScrolling || controlsAlpha < 0.98f))
                ? TICK_FAST_MS : TICK_IDLE_MS;
        if (tickTimer.getDelay() != wantDelay) {
            tickTimer.setDelay(wantDelay);
        }

        // 8) 诊断：每 5 秒一行，用来判断到底是"画得慢"还是"数据在跳"
        diagTick(nowNs);

        // 平移量每帧都在变，位置不变也必须重画
        if (lyricScrolling) {
            dirty = true;
        }

        if (dirty) {
            repaint();
        }
    }

    /** 采集卡拉 OK 的帧率、边界单帧最大位移、位置回退次数，5 秒汇总一行日志。 */
    private void diagTick(long nowNs) {
        if (!hasLyric()) {
            diagSince = nowNs;
            diagFrames = 0;
            diagEdgeJumpMax = 0d;
            diagPosBackward = 0;
            return;
        }
        diagFrames++;
        long span = nowNs - diagSince;
        if (span < 5_000_000_000L) {
            return;
        }
        double fps = diagFrames * 1_000_000_000.0 / span;
        MiniPlayerPlugin.log(String.format(
                java.util.Locale.ROOT,
                "karaoke fps=%.1f edgeJumpMax=%.1f posBackward=%d path=%s",
                fps, diagEdgeJumpMax, diagPosBackward,
                karaokeByCells ? "timeline" : "sweep"));
        diagSince = nowNs;
        diagFrames = 0;
        diagEdgeJumpMax = 0d;
        diagPosBackward = 0;
    }

    /**
     * 打印超宽歌词行的滚动诊断：换行时打一行，之后每 2 秒打一行。
     *
     * <p>专门用来定位「滚到某个位置就不动了」：把边界坐标、位移、可视宽度、整行宽度、
     * 以及当前 cell 时间轴的末端一起打出来，就能分清是边界不再推进（时间轴卡住），
     * 还是边界在推进但位移被 {@code span} 钳住（提前量已经吃满，属于正常到底）。</p>
     */
    private void diagLyricScroll(String text, int textW, int maxW, double edge, int scroll,
                                 LyricsLine line) {
        long now = System.currentTimeMillis();
        boolean newLine = !text.equals(diagScrollForLine);
        boolean stale = now - diagScrollAt >= 2_000L;
        if (!newLine && !stale) {
            return;
        }
        diagScrollForLine = text;
        diagScrollAt = now;
        java.util.List<LyricsLine.Cell> cells = refineCells(line == null ? null : line.getLyricsCells());
        long firstCell = -1L;
        long lastCell = -1L;
        if (!cells.isEmpty()) {
            firstCell = cells.get(0).getStartTime();
            lastCell = cells.get(cells.size() - 1).getEndTime();
        }
        String head = text.length() > 10 ? text.substring(0, 10) : text;
        MiniPlayerPlugin.log(String.format(
                java.util.Locale.ROOT,
                "lyric scroll pos=%d edge=%.1f scroll=%d textW=%d maxW=%d span=%d "
                        + "line=[%d,%d] cellEnd=%d cells=%d text=%s",
                karaokePosMs(), edge, scroll, textW, maxW, Math.max(0, textW - maxW),
                line == null ? -1L : line.getStartTime(),
                line == null ? -1L : line.getEndTime(),
                lastCell, cells.size(), head));
    }

    /** 停靠态的有效性校验：任务栏必须仍然覆盖矮条所在的横向范围。 */
    private void verifyDockedStillValid() {
        if (!isDocked()) {
            return;
        }
        Rectangle bar = taskbarRect();
        if (bar == null || !bar.intersects(getBounds())) {
            undockIfDocked();
            rememberPosition();
            config.save();
        }
    }

    private boolean shouldShowControls() {
        // 停靠态不再强制常显：紧凑矮条同样支持"平时只看封面+歌词、
        // 移入鼠标才展开播控"，这正是它的默认交互。
        return hoverWindow || dragging || pressingOnBar || menuOpen
                || !config.autoHideControls
                || System.currentTimeMillis() - lastInteractionAt < AUTO_HIDE_DELAY_MS;
    }

    /**
     * 收起为歌词态时是否仍要保留播控三键。
     *
     * <p>1.0.33 起固定保留：用户要求"歌词态也需要显示上下曲、暂停控件"，
     * 原来的「收起时也显示播控」开关已删除，行为写死为常显。</p>
     */
    private boolean keepButtonsWithLyric() {
        return hasLyric();
    }

    /** 宿主一次性推来整首歌的歌词行。 */
    void onLyricsLinesUpdated(List<LyricsLine> lines) {
        runOnEdt(() -> {
            lyricsLines = lines == null ? Collections.emptyList() : lines;
            currentLyric = null;
            repaint();
        });
    }

    /** 宿主推来"当前演唱到的行"。 */
    void onLyricLineUpdated(LyricsLine line) {
        runOnEdt(() -> {
            currentLyric = line;
            repaint();
        });
    }

    private void pollNow() {
        try {
            MediaItem item = WorkshopApi.playback().getCurrentMediaItem();
            if (item == null) {
                return;
            }
            if (item.getId() != null && !item.getId().equals(coverTrackId)) {
                onMediaItemChanged(item);
            }
        } catch (Throwable t) {
            // 未授予 library-read 或宿主暂不可用时静默跳过，
            // 但只记一次，避免每秒刷屏把日志撑爆
            if (!pollFailureLogged) {
                pollFailureLogged = true;
                MiniPlayerPlugin.log("poll failed (will stay silent): " + t);
            }
        }
    }

    // ---------------------------------------------------------------- 封面

    private void loadCover(String trackId) {
        if (trackId == null || trackId.isBlank() || trackId.equals(coverTrackId)) {
            return;
        }
        coverTrackId = trackId;
        cover = null;
        CompletionStage<byte[]> stage;
        try {
            stage = WorkshopApi.library().getCoverById(trackId);
        } catch (Throwable t) {
            // 未授予 library-read 权限时抛异常，降级为占位封面
            return;
        }
        if (stage == null) {
            return;
        }
        stage.whenComplete((bytes, error) -> {
            if (error != null || bytes == null || bytes.length == 0) {
                return;
            }
            BufferedImage decoded;
            try {
                decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                return;
            }
            if (decoded == null) {
                return;
            }
            // 按设备缩放生成位图：逻辑 44px 在 1.5× 屏上要占 66 物理像素，
            // 若只按 44 像素生成、再由 drawImage 放大，显示会明显发虚。
            int px = Math.max(layout.coverSize, Math.round(layout.coverSize * (float) hudScale()));
            BufferedImage scaled = scaleCover(decoded, px);
            Color seed = Theme.dominantColor(toPixels(decoded));
            runOnEdt(() -> {
                // 快速切歌时丢弃过期结果，避免封面串图
                if (!trackId.equals(coverTrackId)) {
                    return;
                }
                cover = scaled;
                coverSeed = seed;
                baseColor = Theme.baseColor(coverSeed, isDark());
                repaint();
            });
        });
    }

    private static BufferedImage scaleCover(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int side = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - side) / 2;
        int sy = (src.getHeight() - side) / 2;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        g.dispose();
        return out;
    }

    private static int[] toPixels(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        return img.getRGB(0, 0, w, h, null, 0, w);
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * 自绘面板：所有绘制与鼠标监听都挂在它身上。
     *
     * <p>此前把监听器直接装在 {@link JWindow} 上，而 JWindow 自带 contentPane，
     * 导致事件坐标与绘制坐标不在同一坐标系——表现就是拖拽（只用坐标差）正常，
     * 而所有按钮（用绝对坐标）全部失配。统一到同一个组件后坐标系唯一。
     */
    private final class Surface extends javax.swing.JComponent {

        Surface() {
            setOpaque(false);
            setFocusable(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                        RenderingHints.VALUE_STROKE_PURE);
                paintBody(g);
            } finally {
                g.dispose();
            }
        }
    }

    private void paintBody(Graphics2D g) {
        // 先垫一层 alpha=1 的黑底，再谈"透明"。
        //
        // Windows 对 per-pixel 透明窗口（WS_EX_LAYERED）做命中测试时只看像素 alpha：
        // alpha==0 的像素直接把点击穿透给桌面窗口，Java 侧根本收不到事件。不垫底
        // 的话整条只有封面、标题、播控图标这些"真的画了像素"的地方能命中，空白处
        // 点不动——表现就是拖动、歌词态切换、上下曲都必须精确压在黑色图案上。
        // alpha=1 肉眼分辨不出，但每个像素都不再是全透明，整条从此都在命中范围内。
        g.setColor(new Color(0, 0, 0, 1));
        g.fillRect(0, 0, layout.windowW, layout.windowH);

        // 无背景：不画卡片底、柔光、顶部高光和描边，窗口视觉上完全透明，
        // 只有封面、文字、播控和进度条浮在桌面上，不画卡片底。
        float cardAlpha = config == null ? 1f
                : Math.max(0.35f, Math.min(1f, config.opacity));
        // 已锁定任务栏停放点时压低不透明度，否则拖动中的整条会把
        // 任务栏上的虚线框整个盖住，用户看不到"松手会停在哪"
        if (dockPreviewActive) {
            cardAlpha *= 0.55f;
        }
        if (cardAlpha < 0.999f) {
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, cardAlpha));
        }

        g.translate(layout.shadow, layout.shadow);

        // 内容；内部会临时改 composite（封面圆角裁剪），每步自己恢复
        paintCover(g);
        // 未悬停时显示当前歌词，悬停后交叉淡入完整布局
        paintLyric(g);
        paintText(g);
        paintControls(g);
        paintProgressBar(g);

        g.translate(-layout.shadow, -layout.shadow);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
    }

    private void paintCover(Graphics2D g) {
        Shape clip = new RoundRectangle2D.Double(layout.coverX, layout.coverY, layout.coverSize, layout.coverSize,
                layout.coverRadius, layout.coverRadius);
        Shape oldClip = g.getClip();
        g.clip(clip);
        if (cover != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cover, layout.coverX, layout.coverY, layout.coverSize, layout.coverSize, null);
        } else {
            // 无封面占位：中性深灰渐变，不跟随封面色，避免在壁纸上糊成一片
            g.setPaint(new LinearGradientPaint(
                    new Point2D.Float(layout.coverX, layout.coverY),
                    new Point2D.Float(layout.coverX + layout.coverSize, layout.coverY + layout.coverSize),
                    new float[]{0f, 1f},
                    new Color[]{Theme.coverPlaceholderTop(), Theme.coverPlaceholderBottom()}));
            g.fillRect(layout.coverX, layout.coverY, layout.coverSize, layout.coverSize);
            // 音符用矢量绘制，避免系统字体缺字形时显示成方块
            double noteSize = layout.coverSize * 0.46;
            Icons.note(g, layout.coverX + (layout.coverSize - noteSize) / 2.0,
                    layout.coverY + (layout.coverSize - noteSize) / 2.0,
                    noteSize, new Color(255, 255, 255, 220));
        }
        g.setClip(oldClip);
        // 无背景后封面会和壁纸直接相邻，只留一道极淡的描边做分离
        g.setColor(isDark() ? new Color(255, 255, 255, 38) : new Color(0, 0, 0, 26));
        g.setStroke(new BasicStroke(1f));
        g.draw(clip);
    }

    /**
     * 未悬停时的"歌词态"：封面仍在，右侧换成歌词。
     *
     * <p>双行显示：上行是当前演唱的那一句（加粗、前景色），下行是下一句预告
     * （常规字重、更淡）。最后一句后面没有下一句了，就退回单行居中。</p>
     *
     * <p>与完整布局交叉淡入淡出：{@link #controlsAlpha} 为 0 时只有歌词，
     * 为 1 时只有歌名/歌手/播控，中间是两态叠加，因此不会出现"突然跳变"。
     * 歌词本身也跟随 {@code controlsAlpha} 反向淡出。</p>
     */
    /** 当前应该高亮的那一行：优先宿主推送的，其次按播放位置二分兜底。 */
    private LyricsLine currentOrMatchedLyric() {
        LyricsLine matched = matchLyric(state.positionMs);
        if (currentLyric == null) {
            return matched;
        }
        if (matched == null) {
            return currentLyric;
        }
        // 宿主推送的时机并不可靠：可能滞后，也可能提前。分两种情况处理。
        if (state.positionMs < currentLyric.getStartTime()) {
            // 播放位置还没到推送的这一行——宿主提前推来了。这时必须继续画上一行，
            // 让最后几个字唱完、整行亮满停留一小会儿，等位置真正到 startTime 再换行。
            // 以前这里取「更晚的一行」，效果就是最后一个字刚亮完就跳行。
            return matched;
        }
        // 位置已经进了推送行或更后面：取时间上更靠后的那一行。滞后时用匹配行追上进度，
        // 否则滞后那几帧会把上一行进度算成 100%，整句突然全亮再跳回行首重扫。
        return matched.getStartTime() >= currentLyric.getStartTime() ? matched : currentLyric;
    }

    private void paintLyric(Graphics2D g) {
        float lyricAlpha = 1f - controlsAlpha;
        if (lyricAlpha <= 0.02f) {
            // 歌词整个不可见时不能留着滚动标记，否则 tick 会一直判"还在滚"而空转 60fps
            lyricScrolling = false;
            return;
        }
        LyricsLine line = currentOrMatchedLyric();
        String text = textOf(line);
        if (text == null || text.isEmpty()) {
            text = lyricText();
        }
        if (text == null || text.isEmpty()) {
            lyricScrolling = false;
            return;
        }

        float lyricFade = lyricAlpha * (isDark() ? 0.94f : 0.90f);
        String next = nextLyricText();

        // 歌词区：右侧留给播控三键（歌词态也常显），不能压到按钮上
        int left = layout.textX;
        int right = keepButtonsWithLyric()
                ? layout.prevCx - layout.sideBtnBox / 2 - 8
                : layout.contentW - 12;
        int maxW = Math.max(40, right - left);

        Shape oldClip = g.getClip();
        g.clipRect(left, 0, maxW, layout.contentH);

        g.setFont(lyricFont);
        FontMetrics fmMain = g.getFontMetrics();

        // 换行就把滚动量归零：上一行滚到了句末，下一行若沿用就会从中间开始
        if (!text.equals(lyricScrollForLine)) {
            lyricScrollForLine = text;
            lyricMarqueeStartNs = System.nanoTime();
            lyricScroll = 0;
        }
        int textW = fmMain.stringWidth(text);
        double edge = lyricEdgeFor(line, text, fmMain, left);
        boolean wide = textW > maxW;
        if (wide) {
            // 有边界就跟着边界滚，没有时间轴（NaN）就匀速循环，保证句末一定滚得进窗口
            double relEdge = edge - left;
            lyricScroll = Double.isNaN(relEdge)
                    ? lyricMarqueeScroll(System.nanoTime(), textW, maxW)
                    : lyricScrollFor(relEdge, textW, maxW, lyricLookahead(fmMain));
        } else {
            lyricScroll = 0;
        }
        lyricScrolling = lyricScroll > 0;
        diagLyricScroll(text, textW, maxW, edge, lyricScroll, line);

        if (next == null || next.isEmpty()) {
            // 最后一句：没有预告行，单行垂直居中
            int baseline = (layout.contentH + fmMain.getAscent() - fmMain.getDescent()) / 2;
            paintScrolledLine(g, text, left, baseline, edge, fmMain, lyricFade);
            g.setClip(oldClip);
            return;
        }

        g.setFont(lyricNextFont);
        FontMetrics fmNext = g.getFontMetrics();
        int hMain = fmMain.getHeight();
        int hNext = fmNext.getHeight();
        int top = (layout.contentH - (hMain + hNext)) / 2;
        int baselineMain = top + fmMain.getAscent();
        int baselineNext = top + hMain + fmNext.getAscent();

        g.setFont(lyricFont);
        paintScrolledLine(g, text, left, baselineMain, edge, fmMain, lyricFade);

        g.setFont(lyricNextFont);
        // 副行更淡：无背景时和下句一样亮会跟主行抢注意力
        g.setColor(Theme.fade(Theme.subtitleColor(isDark()), lyricFade * 0.88f));
        g.drawString(ellipsize(next, fmNext, maxW), left, baselineNext);

        g.setClip(oldClip);
    }

    /**
     * 算超宽歌词行的水平滚动量（像素）。
     *
     * <p>滚动只为一件事：让"正在唱的那个字"始终落在可视区里，并且行尾要在本行结束前
     * 就露出来。所以位移量 = "演唱边界相对行首的位置" + 提前量 {@code lookahead}
     * - 可视区宽度，再钳到 {@code [0, textW - maxW]}——不超宽不滚、还没越过右缘不滚、
     * 滚到头也不多滚。</p>
     *
     * <p>为什么是"加提前量"而不是"留余量"：两者方向相反。留余量（原先的
     * {@code LYRIC_SCROLL_MARGIN = 24 → 8 → 0}）等于让边界提前刹车，只会让行尾更晚
     * 露出来；加提前量则是让整个镜头走在边界前面一格，边界还剩一个字的距离时，
     * 行尾已经完整可见并停住。</p>
     *
     * @param edge      演唱边界相对行首的坐标；{@link Double#NaN} 表示这行没有可用时间轴，
     *                  此时由调用方改用匀速循环，本方法返回 0
     * @param textW     整行文字的像素宽度
     * @param maxW      可视区宽度
     * @param lookahead 提前量（像素），通常取一个汉字宽
     * @return 应向左平移的像素数，恒满足 {@code 0 <= scroll <= max(0, textW - maxW)}
     */
    static int lyricScrollFor(double edge, int textW, int maxW, int lookahead) {
        int span = textW - maxW;
        if (span <= 0 || Double.isNaN(edge)) {
            return 0;
        }
        int ahead = Math.max(0, lookahead);
        // 触发点：边界推进到距右缘一个汉字宽时才开始滚，之前文字本来放得下
        double trigger = maxW - ahead;
        double want;
        if (edge <= trigger) {
            want = 0d;
        } else {
            // 两条下限取大者：
            // 1) byEdge   —— 让边界留在可视区右缘，唱到哪就跟到哪；
            // 2) byRatio  —— 从触发点起按「已走 / 剩余」线性分摊 span，保证句末一定
            //                滚到 span（尾部那截才会露出来），且速度均匀、不做末尾冲刺。
            // 只用 byEdge 时，边界要到快唱完才追上右缘，超宽行大半段时间尾部都被裁掉，
            // 用户看到的就是「差半个字永远滚不到」（见真机日志 textW=317/span=160 却 scroll=0）。
            double room = textW - trigger;
            double travelled = edge - trigger;
            double byEdge = edge + ahead - maxW;
            double byRatio = room > 0d ? span * (travelled / room) : span;
            want = Math.max(byEdge, byRatio);
        }
        if (want <= 0d) {
            return 0;
        }
        // 取 floor 而不是四舍五入：宁可少滚半像素，也别把边界挤出右边缘
        return (int) Math.min(span, Math.floor(want));
    }

    /**
     * 提前量：一个汉字宽，用于 {@link #lyricScrollFor}。
     *
     * <p>取 {@code charWidth('字')} 而不是写死像素：字号会随任务栏高度变化
     * （{@link #applyFonts}），写死就会在大任务栏上显得不够、小任务栏上显得过头。</p>
     */
    private static int lyricLookahead(FontMetrics fm) {
        int w = fm.charWidth('字');
        return w > 0 ? w : LYRIC_SCROLL_LOOKAHEAD_PX;
    }

    /**
     * 求主歌词行的演唱边界（绝对屏幕坐标，{@link Double#NaN} 表示这行不做动效）。
     *
     * <p>逐字时间轴可用就走时间轴，否则退回按整行时长匀速扫；两条路都经过
     * {@link #smoothEdge} 平滑，避免宿主时间戳跳变时边界一顿一顿。</p>
     */
    private double lyricEdgeFor(LyricsLine line, String text, FontMetrics fm, int left) {
        java.util.List<LyricsLine.Cell> cells = refineCells(line == null ? null : line.getLyricsCells());
        boolean byCells = timelineUsable(cells, text);
        if (byCells != karaokeByCells) {
            // 只在路径切换时记一行：忽而丝滑忽而匀速，通常就是这两条路在来回切
            karaokeByCells = byCells;
            String head = text.length() > 12 ? text.substring(0, 12) : text;
            MiniPlayerPlugin.log("karaoke path -> " + (byCells ? "timeline" : "sweep")
                    + " cells=" + cells.size() + " textLen=" + text.length()
                    + " head=" + head);
        }
        double target = byCells
                ? timelineEdge(cells, fm, left, fm.stringWidth(text))
                : sweepEdge(line, text, fm, left);
        return smoothEdge(text, target);
    }

    /**
     * 画主歌词行，能拿到逐字时间轴时走卡拉 OK 效果。
     *
     * <p>宿主在 {@code LyricsLine.lyricsCells} 里给了每个字（或每个词）的起止时间，
     * 这里按边界 {@code edge} 把已唱的字画成实色、未唱的压到 42% 透明度，正在唱的那个字
     * 用裁剪只填一半——和 KTV 字幕一个观感。</p>
     *
     * <p>整行超宽时<b>不再</b>截断加省略号：由 {@link #paintScrolledLine} 按
     * {@link #lyricScrollFor} 的位移把绘制坐标整体左移，超出的部分自然滚进窗口；
     * 边界坐标与文字一起平移，所以逐字动效不会和字错位。
     * 只有完全没有时间轴（{@code edge} 为 NaN）时才整行单色。</p>
     */
    private void paintKaraokeLine(Graphics2D g, String text, int left, int baseline,
                                  double edge, FontMetrics fm, float alpha) {
        Color sung = Theme.fade(Theme.titleColor(isDark()), alpha);
        // 未唱的字要一直在那儿，只是比已唱的暗一档。对比度太低，看到的就不是
        // "一条边界扫过去"，而是"整句一起变亮"。
        Color pending = Theme.fade(Theme.titleColor(isDark()), alpha * 0.42f);
        paintEdge(g, text, left, baseline, edge, fm, sung, pending);
    }

    /**
     * 按当前滚动量 {@link #lyricScroll} 画一行主歌词。
     *
     * <p>不用子画布做 {@code translate}：Java2D 的裁剪区是<b>跟着变换走</b>的，
     * 平移子画布会把 {@link #paintLyric} 设好的可视窗口一起挪走，结果是左边漏一块空白、
     * 右边也裁错位置。改成把绘制坐标整体左移，裁剪窗口留在原地——
     * 超出部分自然被父画布的 {@code clipRect} 切掉，效果与"窗口不动、文字滚过"一致。</p>
     */
    private void paintScrolledLine(Graphics2D g, String text, int left, int baseline,
                                   double edge, FontMetrics fm, float alpha) {
        int dx = -lyricScroll;
        double shifted = Double.isNaN(edge) ? Double.NaN : edge + dx;
        paintKaraokeLine(g, text, left + dx, baseline, shifted, fm, alpha);
    }

    /**
     * 没有时间轴时的匀速循环位移：走满 {@code textW - maxW} 后停
     * {@link #LYRIC_MARQUEE_GAP} 像素的时间再回到行首，复用歌名跑马灯的速度。
     */
    private int lyricMarqueeScroll(long nowNs, int textW, int maxW) {
        int span = textW - maxW;
        if (span <= 0) {
            return 0;
        }
        double travelled = (nowNs - lyricMarqueeStartNs) / 1_000_000.0 * LYRIC_MARQUEE_SPEED;
        double cycle = span + LYRIC_MARQUEE_GAP;
        double phase = travelled % cycle;
        return (int) Math.floor(Math.min(phase, span));
    }

    /**
     * 求边界时用的播放位置：真实位置 + {@link #KARAOKE_LEAD_MS}。
     *
     * <p>整句统一提前这么多，等于把边界整体左移半个字左右，句末就会先扫完、
     * 留一小段"全亮"的停顿，等下一行到点再换——而不是最后一个字刚亮完就立马跳行。</p>
     */
    private long karaokePosMs() {
        return state.positionMs + KARAOKE_LEAD_MS;
    }

    /**
     * 按 cell 时间轴求边界坐标。
     *
     * <p>关键是<b>在格内插值</b>：先找到当前位置落在第几个 cell，再按该 cell 内部的时间
     * 比例在这几个字的宽度里插值。所以宿主给的是 3 个字一个 cell，边界也会在这 3 个字的
     * 宽度内连续扫过去，而不是等整格时间到了才一次跳一格。</p>
     *
     * <p><b>宽度必须按整行宽度归一。</b>逐格累加的 {@code Σ stringWidth(piece)} 并不总等于
     * 整串 {@code stringWidth(text)}：宿主给出的 cell 文本经常带首尾空格、或者把标点单独拆格，
     * 于是 Σ 比整串小几个像素。此时若直接用 Σ 当分母，行末边界只会走到整行宽度的
     * 95%~97%，最后一个字就只点亮一部分便换行——这正是「句尾只露一点」的成因。
     * 所以这里把 {@code Σ} 累加出来的坐标按 {@code textW / Σ} 整体拉伸到行尾，
     * 只缩放不改变格与格之间的节奏，标点和长音的停顿感得以保留。</p>
     */
    private double timelineEdge(java.util.List<LyricsLine.Cell> cells, FontMetrics fm, int left,
                                int textW) {
        long pos = karaokePosMs();
        double total = 0d;
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            if (piece != null && !piece.isEmpty()) {
                total += fm.stringWidth(piece);
            }
        }
        double x = left;
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            double w = fm.stringWidth(piece);
            long start = cell.getStartTime();
            long end = cell.getEndTime();
            if (pos >= end) {
                x += w;
                continue;
            }
            if (pos <= start || end <= start) {
                break;
            }
            x += w * ((pos - start) / (double) (end - start));
            break;
        }
        // 把按 Σ 算出来的偏移等比缩放到整行宽度：Σ < textW 时边界会被推得更远，
        // 句尾因此能覆盖到最后一个字；Σ > textW 时同样收敛回行尾，不会越过。
        if (total > 0d && textW > 0) {
            return left + (x - left) * (textW / total);
        }
        return x;
    }

    /**
     * 没有可用时间轴时，退回「整行按行时间匀速推进」。
     *
     * @return 边界 x；{@link Double#NaN} 表示完全没有行时长，应当整行实色不做动效
     */
    private double sweepEdge(LyricsLine line, String text, FontMetrics fm, int left) {
        long ts = line == null ? 0L : line.getStartTime();
        long te = line == null ? 0L : line.getEndTime();
        if (te <= ts) {
            return Double.NaN;
        }
        double progress = (karaokePosMs() - ts) / (double) (te - ts);
        progress = Math.max(0d, Math.min(1d, progress));
        return left + fm.stringWidth(text) * progress;
    }

    /**
     * 把边界位置平滑一下。
     *
     * <p>换行、seek 回退、位置非法时直接吸附——这些场景用户本来就不期待连续。
     * 正常前进时用指数平滑吃掉 1–2px 的残余抖动，代价是边界比真实进度慢约 30ms，
     * 肉眼看不出来。</p>
     */
    private double smoothEdge(String text, double target) {
        if (Double.isNaN(target)) {
            edgeShown = -1d;
            edgeForLine = text;
            return target;
        }
        if (edgeForLine == null || !edgeForLine.equals(text) || edgeShown < 0d || target < edgeShown) {
            edgeShown = target;
            edgeForLine = text;
            return edgeShown;
        }
        double before = edgeShown;
        // 0.85 而不是 0.55：位置本身已经是每帧连续推进的，再叠一层重平滑只会让边界
        // 稳定落后一截（低帧率下尤其明显，看起来像整句慢了一两个字）。
        edgeShown += (target - edgeShown) * 0.85;
        if (Math.abs(target - edgeShown) < 0.4) {
            edgeShown = target;
        }
        double jump = Math.abs(edgeShown - before);
        if (jump > diagEdgeJumpMax) {
            diagEdgeJumpMax = jump;
        }
        return edgeShown;
    }

    /** 先整行铺淡色，再把边界左侧用实色重画一遍。 */
    private void paintEdge(Graphics2D g, String text, int left, int baseline, double edge,
                           FontMetrics fm, Color sung, Color pending) {
        if (Double.isNaN(edge)) {
            g.setColor(sung);
            g.drawString(text, left, baseline);
            return;
        }
        g.setColor(pending);
        g.drawString(text, left, baseline);
        if (edge <= left) {
            return;
        }
        Shape oldClip = g.getClip();
        g.clip(new java.awt.geom.Rectangle2D.Double(left, 0, edge - left, layout.contentH));
        g.setColor(sung);
        g.drawString(text, left, baseline);
        g.setClip(oldClip);
    }

    /**
     * 时间轴能不能用：cells 拼起来必须正好是这一行的文字，且时间单调。
     *
     * <p>这里<b>不再</b>要求"一字一格"或"各格时长铺满整行"——粒度粗到词组也能用，
     * 因为插值发生在格内部。只挡住拼接结果对不上的情况（例如宿主把译文也塞进 cells），
     * 那种硬按 cells 画会串行。</p>
     */
    private static boolean timelineUsable(java.util.List<LyricsLine.Cell> cells, String text) {
        if (cells == null || cells.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            if (piece == null) {
                return false;
            }
            sb.append(piece);
            long start = cell.getStartTime();
            long end = cell.getEndTime();
            if (end < start) {
                return false;
            }
            if (start < first) {
                first = start;
            }
            if (end > last) {
                last = end;
            }
        }
        if (last <= first) {
            return false;
        }
        return stripSpace(sb.toString()).equals(stripSpace(text));
    }

    private static String stripSpace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u3000') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 把逐字单元细化到「单字」。
     *
     * <p>宿主给的 cell 粒度不一定是一字一个：常见的是 2–4 个字的词组，甚至整句一个 cell。
     * 细化之后每个字的停留时长更接近真实演唱，扫过去的节奏才像跟唱。</p>
     *
     * <p>时长按<b>权重</b>分而不是等分：汉字算 1，标点/空格只算 0.25——中文逗号占掉
     * 一整个字的时长会让边界在标点处明显停顿一下。含拉丁字母的 cell 原样保留，
     * 把 {@code hello} 拆成五个字母会变成打字机。</p>
     */
    private static java.util.List<LyricsLine.Cell> refineCells(java.util.List<LyricsLine.Cell> cells) {
        if (cells == null || cells.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<LyricsLine.Cell> out = new java.util.ArrayList<>(cells.size() * 2);
        for (LyricsLine.Cell cell : cells) {
            String piece = cell.getText();
            long start = cell.getStartTime();
            long end = cell.getEndTime();
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            int n = piece.codePointCount(0, piece.length());
            if (n <= 1 || end <= start || !noLatin(piece)) {
                out.add(cell);
                continue;
            }
            double[] weights = new double[n];
            double total = 0d;
            for (int i = 0; i < n; i++) {
                int cp = piece.codePointAt(piece.offsetByCodePoints(0, i));
                weights[i] = isPunctuationOrSpace(cp) ? 0.25d : 1d;
                total += weights[i];
            }
            long span = end - start;
            double acc = 0d;
            for (int i = 0; i < n; i++) {
                int from = piece.offsetByCodePoints(0, i);
                int to = piece.offsetByCodePoints(0, i + 1);
                long s = start + (long) (span * acc / total);
                acc += weights[i];
                long e = start + (long) (span * acc / total);
                if (e <= s) {
                    e = s + 1L;
                }
                out.add(new LyricsLine.Cell(s, e, piece.substring(from, to)));
            }
        }
        return out;
    }

    private static boolean isPunctuationOrSpace(int cp) {
        if (Character.isWhitespace(cp) || cp == '\u3000') {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.OTHER_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION;
    }

    /** 不含拉丁字母才允许按字拆分（中文、日文、韩文、标点都可以拆）。 */
    private static boolean noLatin(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * 决定当前该显示哪一行歌词。
     *
     * <p>优先用宿主推送的 {@link #currentLyric}；拿不到时按播放位置在
     * {@link #lyricsLines} 里二分兜底——宿主漏发 {@code onLyricsLineUpdated}
     * 时歌词不会一直停在旧行。</p>
     */
    private String lyricText() {
        LyricsLine line = currentLyric;
        if (line == null && !lyricsLines.isEmpty()) {
            line = matchLyric(state.positionMs);
        }
        if (line == null) {
            return null;
        }
        return textOf(line);
    }

    /**
     * 下一句歌词，用作副行预告。
     *
     * <p>先在 {@link #lyricsLines} 里按对象身份定位当前行，再往下找第一条有文字的；
     * 宿主单独推送的 {@code currentLyric} 不在列表里时，退回按起始时间定位。
     * 已经是最后一句则返回 {@code null}。</p>
     */
    private String nextLyricText() {
        if (lyricsLines.isEmpty()) {
            return null;
        }
        int index = indexOfLyric(currentOrMatchedLyric());
        if (index < 0) {
            return null;
        }
        for (int i = index + 1; i < lyricsLines.size(); i++) {
            String text = textOf(lyricsLines.get(i));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /** 在 {@link #lyricsLines} 里找某一行的下标：先比对象，再比起始时间。 */
    private int indexOfLyric(LyricsLine line) {
        if (line == null) {
            return -1;
        }
        for (int i = 0; i < lyricsLines.size(); i++) {
            if (lyricsLines.get(i) == line) {
                return i;
            }
        }
        long start = line.getStartTime();
        for (int i = 0; i < lyricsLines.size(); i++) {
            if (lyricsLines.get(i).getStartTime() == start) {
                return i;
            }
        }
        return -1;
    }

    /** 取一行的正文，空行（纯间奏/空占位）返回 {@code null}。 */
    private static String textOf(LyricsLine line) {
        if (line == null) {
            return null;
        }
        String main = line.getPureMainText();
        if (main == null || main.trim().isEmpty()) {
            return null;
        }
        return main.trim();
    }

    /** 当前是否有可显示的歌词行。 */
    private boolean hasLyric() {
        String text = lyricText();
        return text != null && !text.isEmpty();
    }

    /** 按播放位置二分查找当前行。 */
    private LyricsLine matchLyric(long positionMs) {
        int lo = 0;
        int hi = lyricsLines.size() - 1;
        LyricsLine found = null;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LyricsLine l = lyricsLines.get(mid);
            if (l.getStartTime() <= positionMs) {
                found = l;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private void paintText(Graphics2D g) {
        boolean hasTrack = state.hasTrack();
        String title = hasTrack ? state.title : "桌面迷你播放条";
        String artist = hasTrack ? state.artist : "等待播放";
        if (state.buffering) {
            artist = artist + " \u00b7 缓冲中";
        }

        int textAlpha = (int) (255 * controlsAlpha);
        if (textAlpha <= 4) {
            return;
        }

        Shape oldClip = g.getClip();
        g.clipRect(layout.textX, layout.coverY, layout.textMaxW, layout.coverSize);

        g.setFont(titleFont);
        g.setColor(Theme.fade(Theme.titleColor(isDark()), controlsAlpha));
        FontMetrics tfm = g.getFontMetrics();
        if (marqueeNeeded && marqueeOffset != 0f) {
            g.drawString(title, layout.textX - (int) marqueeOffset, layout.titleBaseline);
        } else {
            g.drawString(ellipsize(title, tfm, layout.textMaxW), layout.textX, layout.titleBaseline);
        }

        g.setFont(artistFont);
        g.setColor(Theme.fade(Theme.subtitleColor(isDark()), controlsAlpha));
        FontMetrics afm = g.getFontMetrics();
        g.drawString(ellipsize(artist, afm, layout.textMaxW), layout.textX, layout.artistBaseline);

        g.setClip(oldClip);
    }

    private void paintControls(Graphics2D g) {
        int alpha = (int) (255 * buttonsAlpha);
        if (alpha <= 4) {
            return;
        }

        Color sideColor = Theme.fade(Theme.iconColor(isDark()), buttonsAlpha);
        Icons.previous(g, layout.prevCx - layout.sideBtnBox / 2.0, layout.ctrlY - layout.sideBtnBox / 2.0,
                layout.sideBtnBox, sideColor);
        Icons.next(g, layout.nextCx - layout.sideBtnBox / 2.0, layout.ctrlY - layout.sideBtnBox / 2.0,
                layout.sideBtnBox, sideColor);

        // 播放/暂停：半透明圆底 + 反向明暗的字形（无背景版本不再用实心白圆）
        double r = layout.playRadius;
        // 圆底自带低 alpha，这里要乘上 buttonsAlpha 而不是覆盖它
        Color base = Theme.playButtonBackground(isDark());
        Color fill = Theme.withAlpha(base, Math.round(base.getAlpha() * buttonsAlpha));
        g.setColor(fill);
        g.fill(new Ellipse2D.Double(layout.playCx - r, layout.ctrlY - r, r * 2, r * 2));

        Color glyph = Theme.fade(Theme.playButtonGlyph(isDark()), buttonsAlpha);
        double iconSize = r * 1.05;
        if (state.playing) {
            Icons.pause(g, layout.playCx - iconSize / 2.0, layout.ctrlY - iconSize / 2.0, iconSize, glyph);
        } else {
            Icons.play(g, layout.playCx - iconSize / 2.0, layout.ctrlY - iconSize / 2.0, iconSize, glyph);
        }

        // 右侧原本还有「独占 / 置顶 / 关闭」三个功能图标，已按需求移除，
        // 这三个操作保留在右键菜单里，不再占用窗口宽度。
    }

    private void paintProgressBar(Graphics2D g) {
        int barAlpha = (int) (255 * controlsAlpha);
        if (barAlpha <= 4) {
            return;
        }
        int h = hoverBar || pressingOnBar ? layout.barHHover : layout.barH;
        double y = layout.barY + (layout.barH - h) / 2.0;
        double arc = h;

        g.setColor(Theme.fade(Theme.trackColor(isDark()), controlsAlpha));
        g.fill(new RoundRectangle2D.Double(layout.barX, y, layout.barW, h, arc, arc));

        double progress = state.progress();
        if (progress > 0) {
            double fillW = Math.max(h, layout.barW * progress);
            // 已播放段用封面主色：无背景后靠这一抹颜色保留辨识度
            g.setColor(Theme.fade(baseColor, controlsAlpha));
            g.fill(new RoundRectangle2D.Double(layout.barX, y, fillW, h, arc, arc));
        }

        // 悬停时在进度点画圆点，并在上方显示时间
        if ((hoverBar || pressingOnBar) && state.durationMs > 0) {
            double cx = layout.barX + layout.barW * progress;
            double d = 9;
            // 无背景后白点在浅色桌面上看不见，跟随主题取前景色
            g.setColor(Theme.fade(Theme.iconColor(isDark()), controlsAlpha));
            g.fill(new Ellipse2D.Double(cx - d / 2.0, layout.barY + layout.barH / 2.0 - d / 2.0, d, d));

            // 时间气泡只在够高的时候画：矮条里它会把歌名/歌手那两行整个盖住
            if (layout.contentH < 56) {
                return;
            }
            String time = state.timeText();
            g.setFont(timeFont);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(time);
            int tx = (int) Math.round(Math.max(layout.barX, Math.min(layout.barX + layout.barW - tw, cx - tw / 2.0)));
            int ty = layout.barY - 4;
            // 时间气泡保持深底白字：两种主题下都有足够对比度
            g.setColor(new Color(0, 0, 0, 150));
            g.fill(new RoundRectangle2D.Double(tx - 5, ty - fm.getAscent() - 1,
                    tw + 10, fm.getHeight() - 1, 6, 6));
            g.setColor(Color.WHITE);
            g.drawString(time, tx, ty);
        }
    }

    private static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        int ew = fm.stringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < text.length(); i++) {
            int cw = fm.charWidth(text.charAt(i));
            if (acc + cw + ew > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
            acc += cw;
        }
        return sb.append(ellipsis).toString();
    }

    private void updateMarqueeNeeded() {
        marqueeNeeded = state.title != null
                && getFontMetrics(titleFont).stringWidth(state.title) > layout.textMaxW;
        if (!marqueeNeeded) {
            marqueeOffset = 0f;
        }
    }

    // ---------------------------------------------------------------- 交互

    private void installMouseHandlers() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastInteractionAt = System.currentTimeMillis();
                if (e.isPopupTrigger()) {
                    // 锁定后连右键菜单都不弹（用户要求：锁定＝关掉右键菜单与拖动）。
                    // 播控三键与进度条不受锁定影响，所以这里只拦菜单，不拦整条。
                    if (config.locked) {
                        return;
                    }
                    showMenu(e);
                    return;
                }
                pressPoint = eventPoint(e);
                // 按钮优先于进度条：播控带与进度条热区在 HiDPI 下可能重叠，
                // 先判进度条会把按钮点击当成拖动进度条
                if (hitAnyButton(eventPoint(e))) {
                    return;
                }
                if (hitProgressBar(eventPoint(e))) {
                    pressingOnBar = true;
                    seekToPoint(eventPoint(e));
                    return;
                }
                // 除按钮与进度条外整条都能拖：原先只有左侧 10px 手柄可拖，
                // 按住封面或文字区时窗口纹丝不动，被判成"拖动判定不对"
                if (!hitDragArea(eventPoint(e))) {
                    return;
                }
                if (config.locked) {
                    return;
                }
                dragging = true;
                dragMoved = false;
                dragOffset = eventPoint(e);
                pressScreenX = e.getXOnScreen();
                pressScreenY = e.getYOnScreen();
                dragStartLocation = getLocation();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                lastInteractionAt = System.currentTimeMillis();
                if (pressingOnBar) {
                    seekToPoint(eventPoint(e));
                    return;
                }
                if (!dragging || dragOffset == null || dragStartLocation == null) {
                    return;
                }
                // 用「按下时的窗口位置 + 指针位移」定位，不能累加 getX()：
                // 窗口一移动，事件里的窗口内坐标就跟着变，逐帧累加会越拖越快
                int dx = e.getXOnScreen() - pressScreenX;
                int dy = e.getYOnScreen() - pressScreenY;
                if (dx == 0 && dy == 0) {
                    // 合成事件（探针）拿不到屏幕坐标时退回窗口内坐标
                    Point p = eventPoint(e);
                    dx = p.x - dragOffset.x;
                    dy = p.y - dragOffset.y;
                }
                if (Math.abs(dx) + Math.abs(dy) >= DRAG_THRESHOLD_PX) {
                    dragMoved = true;
                }
                setLocation(dragStartLocation.x + dx, dragStartLocation.y + dy);
                updateDockPreview(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastInteractionAt = System.currentTimeMillis();
                // 菜单既可能在按下时触发（Windows 传统），也可能在松开时触发（部分场景），
                // 两处都要被锁定拦下，否则锁定后右键仍能弹出菜单
                if (e.isPopupTrigger()) {
                    if (config.locked) {
                        return;
                    }
                    showMenu(e);
                    return;
                }
                boolean wasBar = pressingOnBar;
                pressingOnBar = false;
                boolean wasDragging = dragging;
                // 只有指针真的移动过才算拖动。整个条都能按住之后，
                // 单击文字区也会进 dragging 状态，不能再把"按下过"当成"拖动过"，
                // 否则松手会去停靠/吸附，封面双击唤起宿主也被吞掉。
                boolean moved = dragMoved;
                dragging = false;
                dragMoved = false;
                dragStartLocation = null;
                setCursor(Cursor.getDefaultCursor());

                if (wasDragging && moved) {
                    // 停靠固定开启（1.0.32 起面板里的开关已删除）
                    Rectangle bar = taskbarRect();
                    if (bar != null && dockPreviewActive) {
                        dockToTaskbar(bar);
                    } else {
                        undockIfDocked();
                        snapToEdges();
                    }
                    hideIndicator();
                    dockPreviewActive = false;
                    clampOnScreen();
                    rememberPosition();
                    config.docked = isDocked();
                    config.save();
                }
                if (wasBar || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Point p = eventPoint(e);
                boolean sameSpot = pressPoint == null
                        || (Math.abs(p.x - pressPoint.x) < 4 && Math.abs(p.y - pressPoint.y) < 4);
                int hit = hitButton(p);
                // 点击诊断：仍然点不动时，用户把这行贴出来就能定位坐标系问题
                MiniPlayerPlugin.log("click raw=" + e.getPoint()
                        + " screen=" + e.getXOnScreen() + "," + e.getYOnScreen()
                        + " win=" + getX() + "," + getY()
                        + " scale=" + hudScale()
                        + " size=" + (surface == null ? "-" : surface.getWidth() + "x" + surface.getHeight())
                        + " hit=" + hit + " sameSpot=" + sameSpot + " drag=" + wasDragging);
                if (hit == HIT_NONE) {
                    // 空白处不再承担任何播放动作：播放/暂停与上下曲只由播控三键触发。
                    // 唯一的例外是封面——唤起 Salt Player 主窗口。
                    //
                    // 这里刻意不要求 clickCount >= 2：原先只有双击才响应，用户单击封面
                    // 毫无反馈，观感就是"点封面没反应"。单击即唤起不会有误触代价——
                    // 封面上没有别的动作可做，按住不动才算单击（sameSpot），拖动仍归拖动。
                    if (sameSpot && hitCoverAt(p)) {
                        openHostWindow();
                    }
                    return;
                }
                if (!sameSpot) {
                    return;
                }
                switch (hit) {
                    case HIT_PLAY -> togglePlay();
                    case HIT_PREV -> callPlayback("previous");
                    case HIT_NEXT -> callPlayback("next");
                    default -> {
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                lastInteractionAt = System.currentTimeMillis();
                hoverWindow = true;
                boolean onBar = hitProgressBar(eventPoint(e));
                if (onBar != hoverBar) {
                    hoverBar = onBar;
                    repaint();
                }
                boolean onButton = hitButton(eventPoint(e)) != HIT_NONE;
                // 封面上有独立的动作（单击唤起宿主），所以光标要跟别处区分开：
                // 封面给 HAND（"这里可以点"），其余可拖区域给 MOVE（"这里可以抓"）。
                // 以前封面也算"可拖"，整条光标都是 MOVE，用户点封面得不到任何提示，
                // 才觉得"有些位置是点击、有些是拖动"。封面仍然可以拖（位移阈值大于点按）。
                boolean onCover = !onBar && !onButton && hitCoverAt(eventPoint(e));
                // 整个条都能拖，光标要如实反映"这里能按住拖"。锁定时一律不给移动光标，
                // 免得看起来能拖、按下去却纹丝不动。
                boolean draggable = !onBar && !onButton && !config.locked;
                if (onBar || onButton || onCover) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else if (draggable) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hoverWindow = true;
                lastInteractionAt = System.currentTimeMillis();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverWindow = false;
                lastInteractionAt = System.currentTimeMillis();
                if (hoverBar) {
                    hoverBar = false;
                }
                repaint();
            }
        };
        surface.addMouseListener(adapter);
        surface.addMouseMotionListener(adapter);
    }

    /**
     * 事件坐标转窗口内逻辑坐标。
     * <p>首选由「屏幕坐标 - 窗口屏幕原点」反推：AWT 的屏幕坐标与窗口坐标同为用户空间，
     * 两者相减就是事件在窗口内的真实位置，不受 DPI 缩放影响。</p>
     * <p>只有反推结果明显不合理时，才退回 {@code event.getPoint()}。</p>
     */
    private Point eventPoint(MouseEvent e) {
        Point p = e.getPoint();
        try {
            int sx = e.getXOnScreen() - getX();
            int sy = e.getYOnScreen() - getY();
            if (Math.abs(sx - p.x) <= 2 && Math.abs(sy - p.y) <= 2) {
                return p;
            }
            if (sx >= -4 && sy >= -4 && sx <= layout.windowW + 4 && sy <= layout.windowH + 4) {
                return new Point(sx, sy);
            }
        } catch (Throwable ignored) {
            // 屏幕坐标拿不到时用事件自身坐标
        }
        return p;
    }

    /**
     * 候选坐标系：事件坐标可能已被等比例缩放。先试原值，再试按 DPI 比例缩小后的值。
     *
     * <p><b>不要再加 {@code raw * scale} 那个候选。</b>它是"事件坐标偏小"的兜底，
     * 但会把落在文字区的点放大到播控三键上：本机 scale=1.5，点在歌词区
     * {@code x=166} 被放大成 249，正好贴到 {@code playCx=252}，一次点击就
     * 变成了"暂停"。放大候选只会让热区向外污染，没有任何场景需要它——
     * 真要偏小，缩小候选那一步就够了。</p>
     */
    private java.util.List<Point> candidates(Point raw) {
        java.util.List<Point> out = new java.util.ArrayList<>(2);
        out.add(raw);
        double scale = hudScale();
        if (scale > 1.001 || scale < 0.999) {
            out.add(new Point((int) Math.round(raw.x / scale), (int) Math.round(raw.y / scale)));
        }
        return out;
    }

    private double hudScale() {
        try {
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc != null) {
                return gc.getDefaultTransform().getScaleX();
            }
        } catch (Throwable ignored) {
            // 拿不到就按 1 处理
        }
        return 1.0;
    }

    private int hitButton(Point raw) {
        for (Point c : candidates(raw)) {
            int hit = hitButtonAt(c);
            if (hit != HIT_NONE) {
                return hit;
            }
        }
        return HIT_NONE;
    }

    private int hitButtonAt(Point pp) {
        double x = pp.x - layout.shadow;
        double y = pp.y - layout.shadow;
        // 圆形热区：半径 sideBtnBox/2 + 5 = 13px，比 16px 图标大一圈。
        //
        // 不能再放宽到 +8：那正好是 16px，而文字区右端就落在 prevCx - 16
        // （textRight = prevCx - 16），两者相切——点在歌词最右端会被判成"上一首"。
        // 用户报的"歌词区域点击会切歌/暂停"就是这么来的。
        if (dist(x, y, layout.prevCx, layout.ctrlY) <= layout.sideBtnBox / 2.0 + 5) {
            return HIT_PREV;
        }
        if (dist(x, y, layout.nextCx, layout.ctrlY) <= layout.sideBtnBox / 2.0 + 5) {
            return HIT_NEXT;
        }
        if (dist(x, y, layout.playCx, layout.ctrlY) <= layout.playRadius + 4) {
            return HIT_PLAY;
        }
        // 兜底：落在播控带高度内、且在某一按钮的水平归属区间，就归到最近的那个。
        // 圆形热区之间的空隙不该吞掉点击——那是"按钮点了没反应"的主要来源。
        //
        // 但范围必须收紧到按钮附近：原先 y 允许 ±(playRadius+6)=±19、x 允许 ±(half+6)，
        // 在 40px 高的条上等于"整个高度、从 prevCx-14 一直到右边缘"全是按钮，
        // 文字区一贴到右边就会误触发上/下曲。现在按按钮自身半径收窄，
        // 只补圆形热区之间的缝，不外扩。
        if (y >= layout.ctrlY - layout.playRadius - 2 && y <= layout.ctrlY + layout.playRadius + 2) {
            double best = Double.MAX_VALUE;
            int bestHit = HIT_NONE;
            double[] cxs = {layout.prevCx, layout.playCx, layout.nextCx};
            int[] hits = {HIT_PREV, HIT_PLAY, HIT_NEXT};
            double[] halfs = {layout.sideBtnBox / 2.0, layout.playRadius, layout.sideBtnBox / 2.0};
            for (int i = 0; i < cxs.length; i++) {
                double dx = Math.abs(x - cxs[i]);
                // 只接受落在该按钮水平半宽内的点，且左边距优先，避免两个按钮争抢
                if (dx <= halfs[i] + 2 && dx < best) {
                    best = dx;
                    bestHit = hits[i];
                }
            }
            return bestHit;
        }
        return HIT_NONE;
    }

    private boolean hitAnyButton(Point p) {
        return hitButton(p) != HIT_NONE;
    }

    private boolean hitProgressBar(Point raw) {
        for (Point c : candidates(raw)) {
            if (hitProgressBarAt(c)) {
                return true;
            }
        }
        return false;
    }

    private boolean hitProgressBarAt(Point p) {
        double x = p.x - layout.shadow;
        double y = p.y - layout.shadow;
        return x >= layout.barX - 4 && x <= layout.barX + layout.barW + 4
                && y >= layout.barY - 6 && y <= layout.barY + layout.barH + 8;
    }

    /**
     * 是否点在封面上。
     *
     * <p>封面单击用来唤起 Salt Player 主窗口。热区比封面本身每边宽 2px，让圆角
     * 附近也能点中；它只影响"双击封面"这个动作，能不能拖仍由 {@link #hitDragArea}
     * 决定——封面本身是可拖区域。</p>
     */
    private boolean hitCoverAt(Point raw) {
        for (Point c : candidates(raw)) {
            double x = c.x - layout.shadow;
            double y = c.y - layout.shadow;
            if (x >= layout.coverX - 2 && x <= layout.coverX + layout.coverSize + 2
                    && y >= layout.coverY - 2 && y <= layout.coverY + layout.coverSize + 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否落在可拖动区域。
     *
     * <p>除播控三键与进度条之外，整条都能按住拖动。之前只有左侧 10px 宽的手柄可拖，
     * 用户按住封面或文字区时窗口纹丝不动，观感就是"拖动判定不对"。</p>
     *
     * <p>放宽热区不会把点击吞掉：点按与拖动由位移阈值区分，播控三键与进度条仍然优先。</p>
     *
     * <p>这里刻意只看 {@code raw} 本身，不像其他命中测试那样遍历 {@link #candidates}：
     * 候选点是给"HiDPI 下坐标可能差一个缩放"兜底的，用在"排除按钮/进度条"上会产生
     * 自相矛盾的结论——原始点落在播放键上被排除，缩放候选点却落回空白处被判成可拖。</p>
     */
    private boolean hitDragArea(Point raw) {
        double x = raw.x - layout.shadow;
        double y = raw.y - layout.shadow;
        if (x < -2 || y < -2 || x > layout.contentW + 2 || y > layout.contentH + 2) {
            return false;
        }
        // 按钮与进度条优先：它们的热区范围内不接管拖动
        return hitButtonAt(raw) == HIT_NONE && !hitProgressBarAt(raw);
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void seekToPoint(Point raw) {
        if (state.durationMs <= 0) {
            return;
        }
        Point p = raw;
        if (!hitProgressBarAt(p)) {
            for (Point c : candidates(raw)) {
                if (hitProgressBarAt(c)) {
                    p = c;
                    break;
                }
            }
        }
        double x = p.x - layout.shadow;
        double ratio = (x - layout.barX) / (double) layout.barW;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        long target = (long) (state.durationMs * ratio);
        state = state.withPosition(target);
        repaint();
        seekTo(target);
    }

    private void togglePlay() {
        callPlayback(state.playing ? "pause" : "play");
    }

    /**
     * 校准窗口层级：常态压住任务栏，全屏应用出现时让位。
     *
     * <p>用户要的是一个 Windows 没有提供原生档位的东西：比普通窗口高（要能压住任务栏），
     * 但比全屏应用低（看视频、打游戏时不该糊在画面上）。Java 的
     * {@code setAlwaysOnTop} 只有"全盖"和"全不盖"两态，所以这里自己算：</p>
     *
     * <ul>
     *   <li>前台不是全屏应用 → 挂 TOPMOST。任务栏本身也在 TOPMOST 层，同层窗口的
     *       前后由最后一次 {@code SetWindowPos} 决定，所以我们能压住它。</li>
     *   <li>前台是全屏应用 → 降到普通层，让全屏窗口盖住我们。</li>
     * </ul>
     *
     * <p>关键在于<b>不是每拍都动</b>，而是分三种情况：</p>
     * <ol>
     *   <li>期望值翻转（进出全屏）→ {@code setTopmost} 换层。这是真正的
     *       层级变化，一次到位。</li>
     *   <li>没翻转、但确实被任务栏挤到下面（{@code trayCovers} 为真）→
     *       <b>{@code raiseWithinTopmost}</b>：在 TOPMOST 层内部把窗口提回最前。
     *       <b>这里不能再用 {@code setTopmost(true)}</b>——对已经挂在 TOPMOST 层的窗口，
     *       {@code SetWindowPos(HWND_TOPMOST)} 是层内空操作，调多少次位置都不动，
     *       于是「判到被压住 → 重新挂 TOPMOST → 仍被压住」可以无限循环，用户看到的
     *       就是迷你条一直待在任务栏下面（消失）。必须用 {@code HWND_TOP}。</li>
     *   <li>两样都不是 → 什么都不做。{@code StableProbe} 实测 TOPMOST 一旦挂上就永久
     *       自持：别的置顶窗口反复前置、静置 5 秒、关掉邻居，层级纹丝不动
     *       （一直上溯 13 层到顶）。所以<b>根本不需要周期保活</b>。</li>
     * </ol>
     *
     * <p>以前每 250ms 无条件 {@code toFront()} 才是真正的病根：那是主动把任务栏
     * 和我们自己的 z 序反复重排，肉眼看就是"一闪一闪"。</p>
     *
     * <p>拖动中与菜单展开期间跳过：那两种情况下一律不应打断用户正在做的事。</p>
     */
    private void syncLayer() {
        if (!isVisible() || dragging) {
            return;
        }
        // 菜单展开期间不动：弹层自己就在 TOPMOST 顶部，这时重排等于把它拽到下面去
        if (activeMenu != null && !activeMenu.isVisible()) {
            menuOpen = false;
            activeMenu = null;
        }
        if (menuOpen) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLayerCheckAt < LAYER_CHECK_MS) {
            return;
        }
        lastLayerCheckAt = now;
        try {
            if (selfHwnd == 0L) {
                selfHwnd = WindowLayer.hwndOf(this);
            }
            if (selfHwnd == 0L) {
                // FFM 不可用（或窗口还没被系统创建）：退化为 Java 侧行为。
                // 挂 false 是刻意的——宁可让全屏窗口盖住我们，也不要糊在人家画面上；
                // 常态压住任务栏的能力在 FFM 可用时由 WindowLayer 保证。
                setAlwaysOnTop(false);
                return;
            }
            boolean fs = WindowLayer.fullscreenForeground(selfHwnd);
            // 全屏一律让位（1.0.32 起"总在最前"开关已删除，语义固定）；非全屏永远挂 TOPMOST，
            // 这才压得住任务栏
            boolean wantTop = !fs;
            boolean flip = wantTop != layerWantedTopmost;
            // 真被任务栏挤到下面时才需要动，正常"压住任务栏"状态返回 false
            boolean trayAbove = wantTop && WindowLayer.trayCovers(selfHwnd);
            if (flip) {
                boolean ok = WindowLayer.setTopmost(selfHwnd, wantTop);
                if (ok) {
                    layerWantedTopmost = wantTop;
                    layerRaiseAttempts = 0;
                    layerSyncs++;
                    MiniPlayerPlugin.log("layer topmost=" + wantTop + " fs=" + fs
                            + " count=" + layerSyncs);
                }
            } else if (trayAbove) {
                // 层内提权：换层救不了这个情况（见方法注释第 2 条）
                if (layerRaiseAttempts >= LAYER_RAISE_LIMIT) {
                    if (layerRaiseAttempts == LAYER_RAISE_LIMIT) {
                        layerRaiseAttempts++;
                        // 只喊一次：判据如果仍然错，别把日志刷爆、也别每秒提权一次
                        MiniPlayerPlugin.log("layer raise gave up after "
                                + LAYER_RAISE_LIMIT + " tries");
                    }
                } else {
                    layerRaiseAttempts++;
                    boolean raised = WindowLayer.raiseWithinTopmost(selfHwnd);
                    MiniPlayerPlugin.log("layer raise ok=" + raised
                            + " attempt=" + layerRaiseAttempts);
                    if (raised) {
                        layerRaiseAttempts = 0;
                        layerSyncs++;
                    }
                }
            } else {
                // 没被压住（正常状态）：清空失败计数，下次真出事时重新获得完整的重试额度
                layerRaiseAttempts = 0;
            }
            // 预览虚线框跟随主窗层级：它是独立窗口，不跟就会在全屏时单独糊在画面上
            if (indicator != null) {
                indicator.followLayer(wantTop);
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("layer sync failed: " + t);
        }
    }

    /**
     * 单击封面时唤起宿主主窗（还原最小化并尝试提到前台）。
     *
     * <p><b>必须离开 EDT</b>。点击处理本身跑在 AWT EDT 上，而这条链路要调一串 Win32
     * 窗口 API（{@code EnumWindows}、{@code SetWindowPos}、{@code SetForegroundWindow}…）。
     * 只要其中任何一个对别人的窗口同步等待一下，整条 UI 线程就卡住——日志里的
     * {@code TERMINATED_HANG / outcome=UNRESPONSIVE} 就是这么来的。FFM 调用与线程无关，
     * 挪到后台线程既能彻底挡住这类风险，也不会拖慢界面。</p>
     *
     * <p>{@link AtomicBoolean} 保证同一时刻只有一个激活线程：用户连点封面时后续点击
     * 直接忽略，不会堆积线程。</p>
     */
    private void openHostWindow() {
        MiniPlayerPlugin.log("cover click: open host window");
        if (!hostActivating.compareAndSet(false, true)) {
            return;
        }
        long self = selfHwnd != 0L ? selfHwnd : WindowLayer.hwndOf(this);
        Thread worker = new Thread(() -> {
            try {
                if (!HostWindow.activate(self)) {
                    MiniPlayerPlugin.log("cover click: host activate returned false");
                }
            } catch (Throwable t) {
                MiniPlayerPlugin.log("cover click failed: " + t);
            } finally {
                hostActivating.set(false);
            }
        }, "miniplayer-host-activate");
        worker.setDaemon(true);
        worker.start();
    }

    private void toggleLock() {
        config.locked = !config.locked;
        config.save();
        // 锁定时把光标复位：否则会保留拖动时留下的 MOVE 光标，看着像还能拖
        setCursor(Cursor.getDefaultCursor());
        repaint();
    }

    private void hideMini() {
        MiniPlayerPlugin.log("hide mini");
        hideIndicator();
        dockPreviewActive = false;
        setVisible(false);
    }

    /**
     * 按「暂停时隐藏」开关重算显隐。
     *
     * <p>用户要的是"播放时显示、未播放时隐藏"，边界确认为<b>只有暂停时隐藏</b>：
     * 缓冲中不隐藏（藏起来像插件崩了）、播完不隐藏（{@code Ended}，让人还能看到条子）、
     * 从没播过也不隐藏（刚装好就消失最让人困惑）。</p>
     *
     * <p>只撤销"因为暂停而藏"的那一次：用户从右键菜单手动隐藏的，不该被下一首歌
     * 的播放事件弹回来。</p>
     */
    private void applyPausedVisibility() {
        if (!isVisible() && !hidePausedHandled) {
            // 用户自己藏起来的，别多管闲事
            return;
        }
        if (!config.hideWhenPaused) {
            if (hidePausedHandled) {
                hidePausedHandled = false;
                if (!isVisible() && config.enabled) {
                    MiniPlayerPlugin.log("paused-hide: switched off, show");
                    showWindow();
                }
            }
            return;
        }
        boolean paused = hasEverPlayed && !state.playing && !state.buffering
                && lastHostState != State.Ended;
        if (paused) {
            if (isVisible()) {
                hidePausedHandled = true;
                MiniPlayerPlugin.log("paused-hide: paused, hide");
                hideMini();
            }
        } else if (hidePausedHandled) {
            hidePausedHandled = false;
            if (!isVisible() && config.enabled) {
                MiniPlayerPlugin.log("paused-hide: resumed, show");
                showWindow();
            }
        }
    }

    /** 宿主 API 要求在主线程（AWT EDT）调用。 */
    private void callPlayback(String action) {
        MiniPlayerPlugin.log("playback." + action + " requested");
        runOnEdt(() -> {
            try {
                switch (action) {
                    case "play" -> WorkshopApi.playback().play();
                    case "pause" -> WorkshopApi.playback().pause();
                    case "previous" -> WorkshopApi.playback().previous();
                    case "next" -> WorkshopApi.playback().next();
                    default -> {
                    }
                }
                MiniPlayerPlugin.log("playback." + action + " ok");
            } catch (Throwable t) {
                MiniPlayerPlugin.log("playback." + action + " failed: " + t);
            }
        });
    }

    private void seekTo(long ms) {
        runOnEdt(() -> {
            try {
                WorkshopApi.playback().seekTo(ms);
                MiniPlayerPlugin.log("seekTo " + ms + " ok");
            } catch (Throwable t) {
                MiniPlayerPlugin.log("seekTo failed: " + t);
            }
        });
    }

    // ---------------------------------------------------------------- 位置

    private void rememberPosition() {
        config.windowX = getX();
        config.windowY = getY();
        config.markDirty();
    }

    /**
     * 拖到屏幕边缘附近时贴边吸附（吸附到任务栏上方的可用区域，不遮任务栏）。
     *
     * <p>1.0.32 起吸附固定开启：设置面板里的「贴边吸附」开关已删除。</p>
     */
    private void snapToEdges() {
        Rectangle area = usableScreenBounds();
        int x = getX();
        int y = getY();
        if (Math.abs(x - area.x) <= SNAP_DISTANCE) {
            x = area.x;
        } else if (Math.abs((x + layout.windowW) - (area.x + area.width)) <= SNAP_DISTANCE) {
            x = area.x + area.width - layout.windowW;
        }
        if (Math.abs(y - area.y) <= SNAP_DISTANCE) {
            y = area.y;
        } else if (Math.abs((y + layout.windowH) - (area.y + area.height)) <= SNAP_DISTANCE) {
            // 底边吸附到"任务栏上沿"，这样控件浮在任务栏上方而不是被任务栏盖住
            y = area.y + area.height - layout.windowH;
        }
        if (x != getX() || y != getY()) {
            setLocation(x, y);
        }
    }

    /**
     * 窗口所在屏幕的<b>可用</b>区域（已扣除任务栏与屏幕预留边距）。
     *
     * <p>优先用「设备边界 - 屏幕内边距」以支持多显示器；但实测部分 Windows
     * 环境 {@code getScreenInsets()} 返回全 0（拿不到任务栏位置），此时主屏退回
     * {@code getMaximumWindowBounds()}，避免控件压在任务栏上。</p>
     */
    private Rectangle usableScreenBounds() {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle max = env.getMaximumWindowBounds();
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            return max;
        }
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle usable = new Rectangle(bounds.x + insets.left,
                bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom));

        boolean noInsets = insets.left == 0 && insets.right == 0
                && insets.top == 0 && insets.bottom == 0;
        boolean onPrimary = bounds.equals(
                env.getDefaultScreenDevice().getDefaultConfiguration().getBounds());
        if (noInsets && onPrimary && max.height < bounds.height) {
            return max;
        }
        return usable;
    }

    /**
     * 当前屏幕任务栏所占的矩形；拿不到时返回 {@code null}。
     *
     * <p>任务栏 = 屏幕物理边界 − 可用工作区，两者之差就是任务栏占据的那一条。
     * 用户可能把任务栏放在上/下/左/右四边，这里按差值非零的那一边计算；
     * 任务栏设置为自动隐藏时四边差值都为 0，此时返回 {@code null}，
     * 停靠功能整体禁用（预览框不出现、松手走普通贴边吸附）。</p>
     */
    Rectangle taskbarRect() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle bounds = gc != null ? gc.getBounds()
                : env.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        Rectangle usable = usableScreenBounds();

        int bottom = (bounds.y + bounds.height) - (usable.y + usable.height);
        int top = usable.y - bounds.y;
        int left = usable.x - bounds.x;
        int right = (bounds.x + bounds.width) - (usable.x + usable.width);

        // 取最大的一条边，其余视为 0：某些环境 inset 会有 1~2px 的取整误差
        int best = Math.max(Math.max(bottom, top), Math.max(left, right));
        if (best <= 2) {
            return null;
        }
        if (best == bottom) {
            return new Rectangle(bounds.x, usable.y + usable.height, bounds.width, bottom);
        }
        if (best == top) {
            return new Rectangle(bounds.x, bounds.y, bounds.width, top);
        }
        if (best == left) {
            return new Rectangle(bounds.x, usable.y, left, usable.height);
        }
        return new Rectangle(usable.x + usable.width, usable.y, right, usable.height);
    }

    /**
     * 停靠到任务栏时的目标外框：垂直居中塞进任务栏，水平跟随当前窗口。
     *
     * <p>宽度取当前布局（停靠与拖出同尺寸），所以水平方向只是把窗口的 x 夹进
     * 任务栏范围，垂直方向对齐到任务栏中线。</p>
     */
    Rectangle dockTargetRect(Rectangle bar) {
        return dockTargetRect(bar, getX(), getY());
    }

    /**
     * 同上，但位置由调用方给出。
     *
     * <p>窗口还没 {@code setLocation} 过时 {@link #getX()} / {@link #getY()} 恒为 0，
     * 启动恢复不能用它们，否则会被夹到任务栏最左边——这正是"每次重开都跑到最左边"的原因。</p>
     */
    Rectangle dockTargetRect(Rectangle bar, int preferX, int preferY) {
        boolean horizontal = bar.width >= bar.height;
        int w = layout.windowW;
        int h = layout.windowH;
        int x;
        int y;
        if (horizontal) {
            x = Math.max(bar.x, Math.min(bar.x + bar.width - w, preferX));
            y = bar.y + (bar.height - h) / 2;
        } else {
            x = bar.x + (bar.width - w) / 2;
            y = Math.max(bar.y, Math.min(bar.y + bar.height - h, preferY));
        }
        return new Rectangle(x, y, w, h);
    }

    /** 拖动中刷新停靠预览：靠近任务栏就点亮虚线框，离开则收起并取消停靠态。 */
    private void updateDockPreview(MouseEvent e) {
        Rectangle bar = taskbarRect();
        if (bar == null) {
            hideIndicator();
            dockPreviewActive = false;
            return;
        }
        boolean near = bar.intersects(getBounds()) || distanceTo(bar, getBounds()) <= DOCK_ATTRACT_PX;
        boolean wasActive = dockPreviewActive;
        if (near) {
            // 已经停靠时窗口就在任务栏里，不需要再显示预览框
            if (isDocked()) {
                hideIndicator();
                dockPreviewActive = true;
            } else {
                dockPreviewActive = true;
                showIndicator(dockTargetRect(bar));
            }
        } else {
            dockPreviewActive = false;
            hideIndicator();
            // 拖离任务栏立刻抽出来，手感上像把矮条从任务栏里拔出来
            undockIfDocked();
        }
        if (wasActive != dockPreviewActive) {
            // 拖动到任务栏上方时整条要跟着变半透明，否则前景元素会盖住虚线框
            repaint();
        }
    }

    /** 两个矩形之间的边缘间距（相交时为 0）。 */
    private static int distanceTo(Rectangle a, Rectangle b) {
        int dx = Math.max(0, Math.max(a.x - (b.x + b.width), b.x - (a.x + a.width)));
        int dy = Math.max(0, Math.max(a.y - (b.y + b.height), b.y - (a.y + a.height)));
        return Math.max(dx, dy);
    }

    /** 是否停靠在任务栏里。尺寸不再区分停靠与否，只反映位置状态。 */
    boolean isDocked() {
        return docked;
    }

    /**
     * 停靠：把窗口对齐到任务栏内的目标位置。
     *
     * <p>尺寸不变——停靠与拖出用的是同一个尺寸，用户要求两者一样大。</p>
     */
    private void dockToTaskbar(Rectangle bar) {
        if (bar == null) {
            return;
        }
        // 任务栏高度变了（用户改过设置）就同步尺寸，否则保持不动
        Layout want = Layout.compactFor(bar.height);
        if (layout == null || want.windowH != layout.windowH) {
            applyLayout(want);
        }
        Rectangle target = dockTargetRect(bar);
        setLocation(target.x, target.y);
        docked = true;
        config.docked = true;
        config.markDirty();
    }

    /**
     * 取消停靠标志；本来就没停靠时什么都不做（幂等）。
     *
     * <p>这里刻意不动位置：拖动途中一旦改坐标就会和 {@code mouseDragged} 里的
     * {@code setLocation} 打架，表现为窗口发抖。越界由松手后的
     * {@link #clampOnScreen()} 兜底。</p>
     */
    private void undockIfDocked() {
        if (!docked) {
            return;
        }
        docked = false;
        config.docked = false;
        config.markDirty();
    }

    private void showIndicator(Rectangle target) {
        if (indicator == null) {
            indicator = new DropIndicator();
        }
        indicator.showAt(target);
    }

    private void hideIndicator() {
        if (indicator != null) {
            indicator.hideNow();
        }
    }

    /**
     * 停靠预览虚线框：一个独立的透明置顶窗口，只画一圈虚线。
     *
     * <p>不画在迷你条自己身上——虚线框要覆盖整个任务栏区域，比迷你条大得多。
     * 用 {@code JWindow} 而不是在任务栏上盖一层，是为了不干扰任务栏自身的命中测试。</p>
     */
    private final class DropIndicator extends JWindow {

        private final Panel panel;
        /** 预览框自己的 HWND，用于跟随主窗层级。 */
        private long indicatorHwnd;
        /** 上次设定给预览框的层级，避免每拍重复 SetWindowPos。 */
        private boolean topmost;

        DropIndicator() {
            super(MiniPlayerWindow.this.getGraphicsConfiguration());
            setBackground(new Color(0, 0, 0, 0));
            setFocusableWindowState(false);
            try {
                setAlwaysOnTop(true);
            } catch (Throwable ignored) {
                // 置顶失败不影响预览
            }
            topmost = true;
            panel = new Panel();
            setContentPane(panel);
        }

        void showAt(Rectangle r) {
            panel.setActive(true);
            setLocation(r.x, r.y);
            int w = Math.max(1, r.width);
            int h = Math.max(1, r.height);
            setSize(w, h);
            panel.setSize(w, h);
            panel.setPreferredSize(new java.awt.Dimension(w, h));
            panel.revalidate();
            panel.repaint();
            if (!isVisible()) {
                setVisible(true);
                indicatorHwnd = 0L;
                // 只在首次显示时抬一次。摆在每帧：拖动过程中每次 mouseDragged 都会
                // 走到这里，toFront() 等价 SetWindowPos(HWND_TOP)，就是主窗"一闪一闪"
                // 的同一类毛病——预览框自己抖起来更难解释。
                toFront();
            }
            followLayer(MiniPlayerWindow.this.layerWantedTopmost);
        }

        /**
         * 跟随主窗层级。
         *
         * <p>不跟随的话，勾了"盖住全屏"之外的情况它仍会挂着自已的
         * {@code setAlwaysOnTop(true)}，全屏窗口一出现就有一条虚线框单独糊在画面上。
         * 只在层级真的需要变时才动 {@code SetWindowPos}，避免拖动过程中抖。</p>
         */
        void followLayer(boolean wantTopmost) {
            if (!isVisible()) {
                return;
            }
            if (wantTopmost == topmost) {
                return;
            }
            try {
                if (indicatorHwnd == 0L) {
                    indicatorHwnd = WindowLayer.hwndOf(this);
                }
                if (indicatorHwnd != 0L && WindowLayer.setTopmost(indicatorHwnd, wantTopmost)) {
                    topmost = wantTopmost;
                }
            } catch (Throwable ignored) {
                // 跟随失败不影响预览
            }
        }

        void hideNow() {
            if (isVisible()) {
                setVisible(false);
            }
        }

        private final class Panel extends javax.swing.JComponent {

            private boolean active;

            Panel() {
                setOpaque(false);
            }

            void setActive(boolean active) {
                this.active = active;
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                            RenderingHints.VALUE_STROKE_PURE);
                    float inset = 1.5f;
                    double w = getWidth() - inset * 2;
                    double h = getHeight() - inset * 2;
                    double arc = 12;
                    Shape outline = new RoundRectangle2D.Double(inset, inset, w, h, arc, arc);

                    // 内层柔光让虚线在浅色任务栏上也看得清
                    g.setColor(new Color(0, 0, 0, active ? 46 : 26));
                    g.setStroke(new BasicStroke(3f));
                    g.draw(outline);

                    g.setColor(new Color(255, 255, 255, active ? 0xE6 : 0x66));
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                            10f, new float[]{6f, 4f}, 0f));
                    g.draw(outline);
                } finally {
                    g.dispose();
                }
            }
        }
    }

    private boolean visibleAreaIntersects(int x, int y) {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle self = new Rectangle(x, y, layout.windowW, layout.windowH);
        for (GraphicsDevice device : env.getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(self)) {
                return true;
            }
        }
        return false;
    }

    /** 窗口完全移出所有屏幕时拉回主屏中央。 */
    private void clampOnScreen() {
        if (visibleAreaIntersects(getX(), getY())) {
            return;
        }
        Rectangle main = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        setLocation(main.x + (main.width - layout.windowW) / 2,
                main.y + (main.height - layout.windowH) / 2);
    }

    /** 把当前窗口挪回主屏中央并刷新记忆坐标；没有窗口时什么都不做。 */
    static void recenter() {
        MiniPlayerWindow w = instance;
        if (w == null) {
            return;
        }
        w.runOnEdt(() -> {
            Rectangle main = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            w.setLocation(main.x + (main.width - w.layout.windowW) / 2,
                    main.y + (main.height - w.layout.windowH) / 2);
            w.rememberPosition();
            w.config.save();
        });
    }

    /**
     * 切换迷你条的显示 / 隐藏。
     *
     * <p>给宿主设置面板的「显示 / 隐藏迷你条」按钮用：窗口藏起来之后，如果没有这个入口，
     * 用户只能重启宿主才能再看到它——这正是「关闭迷你播放器」以前的死路。
     * 面板里的「启用迷你播放器」开关走的是另一条路（{@link #applyConfig}）。</p>
     *
     * <p>窗口还没建（配置里 enabled=false 或建窗失败）时按需新建一个，
     * 这样"隐藏后关掉宿主、下次想用时再打开"也能直接唤出。</p>
     */
    static void toggleMini() {
        MiniPlayerWindow w = instance;
        if (w == null) {
            w = ensureCreated(PluginConfig.load());
            if (w == null) {
                MiniPlayerPlugin.log("toggleMini: window not created");
                return;
            }
            if (!w.isShowingMini()) {
                w.showWindow();
            }
            return;
        }
        final MiniPlayerWindow target = w;
        target.runOnEdt(() -> {
            if (target.isShowingMini()) {
                target.hideMini();
            } else {
                target.showWindow();
            }
        });
    }

    // ---------------------------------------------------------------- 配置热更新

    /**
     * 把磁盘上刚读到的配置应用到活着的窗口，让设置面板里的开关不用重启宿主就生效。
     *
     * <p>入口是 {@link PluginConfig#installChangeListener()} 注册的
     * {@code ConfigManager#addConfigChangeListener}。那个回调<b>不保证在 EDT 上</b>，
     * 所以除"新建窗口"外的所有改动都投递到 EDT 执行。</p>
     *
     * <p><b>这里刻意只读跟进、绝不回写磁盘。</b>窗口自己的 {@code onTick} 里有一条
     * {@code if (config.isDirty()) config.save();}，若在监听回调里也去写同一份文件，
     * 就会「写盘 → WatchService 通知 → 再写盘」自激成死循环。所以这里的赋值一律不调
     * {@code markDirty()}。</p>
     *
     * @param fresh 刚读出来的配置快照；为 null 时直接返回
     */
    static void applyConfig(PluginConfig fresh) {
        if (fresh == null) {
            return;
        }
        MiniPlayerWindow w = instance;
        if (w == null) {
            // 启动时 enabled=false 没有建窗，用户在面板里重新打开：这时按需建一个
            if (fresh.enabled) {
                MiniPlayerWindow created = ensureCreated(fresh);
                if (created != null) {
                    created.showWindow();
                }
            }
            return;
        }
        w.runOnEdt(() -> w.applyConfigOnEdt(fresh));
    }

    /** {@link #applyConfig(PluginConfig)} 的 EDT 内半段：逐项 diff 后应用。 */
    private void applyConfigOnEdt(PluginConfig fresh) {
        // 显隐只在「启用迷你播放器」这个开关真正翻转时才动。
        // 不能写成 if (fresh.enabled != isShowingMini())：用户点右键菜单的「隐藏迷你条」
        // 隐藏窗口时 enabled 仍是 true，此后他改任何别的设置项都会把窗口又弹回来。
        if (config.enabled != fresh.enabled) {
            if (fresh.enabled) {
                showWindow();
            } else {
                hideMini();
            }
        }

        if (!java.util.Objects.equals(config.themeMode, fresh.themeMode)) {
            config.themeMode = fresh.themeMode;
            baseColor = Theme.baseColor(coverSeed, isDark());
        }

        // enabled 也要跟着更新：窗口 onTick 在 isDirty() 时会 save()，那时会把内存里的
        // config.enabled 写回磁盘；若不跟，用户刚从面板关掉，窗口下一次落盘又会把它改回
        // true，下次启动照样弹出来。赋值不触发 dirty，所以不会造成写盘回环。
        config.enabled = fresh.enabled;
        config.locked = fresh.locked;
        config.autoHideControls = fresh.autoHideControls;
        config.opacity = fresh.opacity;

        // 「暂停时隐藏」开关一改就要立刻重算显隐，否则要等到下一次状态回调才生效
        if (config.hideWhenPaused != fresh.hideWhenPaused) {
            config.hideWhenPaused = fresh.hideWhenPaused;
            hidePausedHandled = false;
            applyPausedVisibility();
        }

        repaint();
    }

    // ---------------------------------------------------------------- 右键菜单

    /**
     * 菜单关闭后的收尾。
     *
     * <p>菜单是独立的 heavyweight 弹层，展开/关闭会重排 TOPMOST 层的顺序：用户点完
     * 菜单如果顺手点了任务栏，任务栏此刻就排在我们前面。以前这里靠"重申
     * {@code setAlwaysOnTop(true)} 再 {@code toFront()}"硬抢，代价是每次关菜单都
     * 重排一次 z 序（用户能看到的闪一下）。现在只把节流计时清零，让下一拍的
     * {@link #syncLayer} 去查——它只在<b>任务栏真的压上来了</b>时才动手。</p>
     */
    private void onMenuClosed() {
        menuOpen = false;
        activeMenu = null;
        repaint();
        lastLayerCheckAt = 0L;
    }

    private void showMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        menuOpen = true;
        activeMenu = menu;
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) {
                onMenuClosed();
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) {
                onMenuClosed();
            }
        });

        JCheckBoxMenuItem lockItem = new JCheckBoxMenuItem("锁定位置", config.locked);
        lockItem.addActionListener(ev -> toggleLock());
        menu.add(lockItem);

        JCheckBoxMenuItem pausedItem = new JCheckBoxMenuItem("暂停时隐藏", config.hideWhenPaused);
        pausedItem.addActionListener(ev -> {
            config.hideWhenPaused = pausedItem.isSelected();
            config.save();
            // 打开开关时若正处于暂停，应该立刻藏起来；关掉时立刻回来。重算一遍最省事
            hidePausedHandled = false;
            applyPausedVisibility();
        });
        menu.add(pausedItem);

        // 「总在最前（盖住全屏窗口）」已按用户要求删除：层级语义固定为
        // "常态压住任务栏、前台全屏时让位"，不再提供开关，详见 syncLayer。

        JCheckBoxMenuItem darkItem = new JCheckBoxMenuItem("深色主题", isDark());
        darkItem.addActionListener(ev -> {
            config.themeMode = darkItem.isSelected()
                    ? PluginConfig.MODE_DARK : PluginConfig.MODE_LIGHT;
            baseColor = Theme.baseColor(coverSeed, isDark());
            config.save();
            repaint();
        });
        menu.add(darkItem);

        // 「收起为歌词态」这类偏好原先是「更多设置」子菜单，现已整体删除：
        // 它们都是设一次就不再动的偏好，宿主插件设置面板里都有同一份，
        // 没必要在两处维护同一批开关。（「收起时也显示播控」在 1.0.33 已固化为常显。）
        // 主菜单只留随时会切换的项：锁定位置、深色主题、
        // （停靠时）从任务栏抽出、重置位置、隐藏迷你条。

        if (isDocked()) {
            JMenuItem undockItem = new JMenuItem("从任务栏抽出");
            undockItem.addActionListener(ev -> {
                undockIfDocked();
                snapToEdges();
                rememberPosition();
                config.save();
            });
            menu.add(undockItem);
        }

        menu.addSeparator();

        JMenuItem resetItem = new JMenuItem("重置位置");
        resetItem.addActionListener(ev -> {
            config.windowX = -1;
            config.windowY = -1;
            applyInitialPosition();
        });
        menu.add(resetItem);

        // 「关闭迷你播放器」改成可逆的「隐藏」：以前关掉之后除了重启宿主
        // 没有任何办法再叫回来，用户只能去改配置文件。
        // 唤回入口是宿主设置面板的「启用迷你播放器」开关（即时生效）与
        // 「显示 / 隐藏迷你条」按钮，所以这里只说"可在设置面板唤回"。
        JMenuItem hideMiniItem = new JMenuItem("隐藏迷你条（设置面板可唤回）");
        hideMiniItem.addActionListener(ev -> hideMini());
        menu.add(hideMiniItem);

        menu.show(this, e.getX(), e.getY());
    }

    // ---------------------------------------------------------------- 工具

    /** 异步投递到 EDT，用于进度刷新等不需要等待结果的场合。 */
    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * 同步执行在 EDT 上，用于生命周期操作（显示 / 销毁）。
     *
     * <p>生命周期必须同步：异步投递会让调用方在组件真正就绪之前就继续往下走，
     * 这正是「日志写着 window not created、用户看不到窗口」的成因。</p>
     */
    private static void runOnEdtSync(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MiniPlayerPlugin.log("edt task interrupted");
        } catch (java.lang.reflect.InvocationTargetException e) {
            MiniPlayerPlugin.log("edt task failed: "
                    + MiniPlayerPlugin.describe(e.getTargetException()));
        }
    }
}
