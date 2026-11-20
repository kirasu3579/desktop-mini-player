package com.miniplayer;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 封面单击探针：验证「单击封面唤起宿主、点别处什么都不做」这条新契约。
 *
 * <p>为什么值得单独测：这个改动同时动了两件容易出隐性回归的事——</p>
 * <ul>
 *   <li>删掉了"点空白处切换播放/暂停"。那是用户明确要求去掉的，但它藏在
 *       {@code mouseReleased} 的 {@code HIT_NONE} 分支里，一不小心就会被写回来；</li>
 *   <li>新加的封面热区必须真的只覆盖封面。判错就会连带吞掉"点文字区"这种操作
 *       （虽然现在空白处已经不响应，但热区回归到整条会让人误以为哪里都能唤起宿主）。</li>
 * </ul>
 *
 * <p>这里不真去唤起宿主（那会打开一个真实窗口、还要依赖宿主主窗存在），只断言
 * <b>判定结果</b>与 <b>副作用边界</b>。</p>
 */
public final class CoverProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: cover probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Field sf = MiniPlayerWindow.class.getDeclaredField("surface");
        sf.setAccessible(true);
        Component surface = (Component) sf.get(w);

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);
        w.setLocation(400, 300);
        Thread.sleep(300);

        Layout l = currentLayout(w);
        System.out.println("cover x=" + l.coverX + " y=" + l.coverY + " size=" + l.coverSize);

        // 1) 布局：封面必须贴边，21px 的手柄留白已经收掉
        check("封面贴左边缘（coverX <= 4）", l.coverX <= 4);
        check("封面贴边后仍在内容区内", l.coverX >= 0);

        // 2) 热区判定：封面中心与四角内侧为真，文字区/播控键/进度条为假
        int cx = l.coverX + l.coverSize / 2;
        int cy = l.coverY + l.coverSize / 2;
        check("封面中心命中", hitCover(w, cx, cy));
        check("封面左上命中", hitCover(w, l.coverX + 1, l.coverY + 1));
        check("封面右下命中", hitCover(w, l.coverX + l.coverSize - 1, l.coverY + l.coverSize - 1));
        check("文字区不命中", !hitCover(w, l.textX + 20, l.contentH / 2));
        check("播放键不命中", !hitCover(w, l.playCx, l.ctrlY));
        check("进度条不命中", !hitCover(w, l.barX + l.barW / 2, l.barY));

        // 3) 封面仍是可拖区域：单击唤起不该抢占"按住拖"
        check("封面可拖", hitDragArea(w, cx, cy));

        // 4) 单击封面不改变播放状态：唤起宿主是另一条路径，不该顺带播/暂
        PlayerState beforeCover = stateOf(w);
        //    同时这条点击必须**立刻返回**：唤起链路要调一串 Win32 API，历史事故是
        //    它跑在 AWT EDT 上、对别人的窗口同步等待，把宿主整体卡成
        //    TERMINATED_HANG / UNRESPONSIVE。现在链路在后台线程，点击应立即返回。
        long clickStart = System.nanoTime();
        clickAt(surface, cx, cy, l.shadow);
        long clickCostMs = (System.nanoTime() - clickStart) / 1_000_000L;
        System.out.println("cover click dispatch cost = " + clickCostMs + "ms");
        check("单击封面立即返回（<200ms，未阻塞 EDT）", clickCostMs < 200);
        Thread.sleep(200);
        PlayerState afterCover = stateOf(w);
        check("单击封面不改变播放状态",
                beforeCover.playing == afterCover.playing);

        // 5) 单击空白处不再切换播放：直接构造一次 press + release，观察状态有没有被改
        //    togglePlay 会走 WorkshopApi（探针里没有宿主），一旦被调用必然抛异常并留下日志。
        //    这里用"位置离封面足够远"的空白点，保证走的是 HIT_NONE 且 hitCoverAt=false 的分支。
        int blankX = l.textRight - 4;
        int blankY = 2;
        boolean blankOnCover = hitCover(w, blankX, blankY);
        check("所选空白点确实不在封面上", !blankOnCover);
        PlayerState beforeState = stateOf(w);
        clickAt(surface, blankX, blankY, l.shadow);
        Thread.sleep(120);
        PlayerState afterState = stateOf(w);
        check("单击空白处不改变播放状态",
                beforeState.playing == afterState.playing
                        && java.util.Objects.equals(beforeState.title, afterState.title));

        w.setVisible(false);
        w.dispose();
    }

    /**
     * 用<b>内容坐标</b>问"这里算不算封面"。
     *
     * <p>{@code hitCoverAt} 吃的是窗口坐标（含 {@code shadow} 内缩），所以这里统一补上
     * {@code layout.shadow} 再问。早期版本漏了这一步，封面左上角那个断言就会假失败。</p>
     */
    private static boolean hitCover(MiniPlayerWindow w, int x, int y) throws Exception {
        Method m = MiniPlayerWindow.class.getDeclaredMethod("hitCoverAt", Point.class);
        m.setAccessible(true);
        Layout l = currentLayout(w);
        return (Boolean) m.invoke(w, new Point(x + l.shadow, y + l.shadow));
    }

    private static boolean hitDragArea(MiniPlayerWindow w, int x, int y) throws Exception {
        Method m = MiniPlayerWindow.class.getDeclaredMethod("hitDragArea", Point.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(w, new Point(x, y));
    }

    private static Layout currentLayout(MiniPlayerWindow w) throws Exception {
        Method m = MiniPlayerWindow.class.getDeclaredMethod("currentLayout");
        m.setAccessible(true);
        return (Layout) m.invoke(w);
    }

    private static PlayerState stateOf(MiniPlayerWindow w) throws Exception {
        Field f = MiniPlayerWindow.class.getDeclaredField("state");
        f.setAccessible(true);
        return (PlayerState) f.get(w);
    }

    /** 在同一个点发 press + release(clicks=1)，复现"单击"的事件序列。 */
    private static void click(Component target, int lx, int ly) {
        long when = System.currentTimeMillis();
        int ax = absX(target);
        int ay = absY(target);
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, when,
                MouseEvent.BUTTON1_DOWN_MASK, lx, ly, ax + lx, ay + ly, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, when + 20,
                MouseEvent.BUTTON1_DOWN_MASK, lx, ly, ax + lx, ay + ly, 1, false, MouseEvent.BUTTON1));
    }

    /** 同 {@link #click}，但入参是内容坐标，先补上 {@code layout.shadow} 换成窗口坐标。 */
    private static void clickAt(Component target, int lx, int ly, int inset) {
        click(target, lx + inset, ly + inset);
    }

    private static int absX(Component target) {
        try {
            return target.getLocationOnScreen().x;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int absY(Component target) {
        try {
            return target.getLocationOnScreen().y;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
