package com.miniplayer;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 拖动探针：验证「除播控三键与进度条外整条都能按住拖动」这条规则。
 *
 * <p>本机指针不受 Robot 控制（{@code Robot.mouseMove} 无效），所以沿用 DispatchProbe
 * 的做法，把合成的 MouseEvent 送进 Surface。断言四件事：</p>
 * <ul>
 *   <li>文字区 / 封面区（含贴边处）都能按住拖动，窗口精确跟随指针总位移；</li>
 *   <li>拖动是「起点 + 总位移」，连发多个 dragged 事件不会越拖越快；</li>
 *   <li>播控三键与进度条的区域内不接管拖动；</li>
 *   <li>按住不放（没有位移）松手不放走吸附分支，光标与拖动状态都复位。</li>
 * </ul>
 */
public final class DragProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: drag probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        Field f = MiniPlayerWindow.class.getDeclaredField("surface");
        f.setAccessible(true);
        Component surface = (Component) f.get(w);

        Method show = MiniPlayerWindow.class.getDeclaredMethod("showWindow");
        show.setAccessible(true);
        show.invoke(w);

        // 挪到屏幕中间，远离边缘：snapToEdges / clampOnScreen 才不会干扰坐标断言
        w.setLocation(400, 300);
        Thread.sleep(300);

        Layout l = currentLayout(w);
        System.out.println("window size " + w.getSize() + " at " + w.getLocation());
        System.out.println("cover x=" + l.coverX + " size=" + l.coverSize
                + " contentH=" + l.contentH + " barX=" + l.barX);

        // 0) 热区归属：文字区/封面区可拖，播控键与进度条不可拖
        int textX = l.textX + 20;
        int midY = l.contentH / 2;
        check("文字区可拖", hitDragArea(w, textX, midY));
        check("封面区可拖", hitDragArea(w, l.coverX + l.coverSize / 2, midY));
        // 左侧不再有拖动手柄，原来那 21px 留白现在就是封面本身：贴边处也该能拖
        check("封面贴边处可拖", hitDragArea(w, l.coverX, midY));
        check("播控键不可拖", !hitDragArea(w, l.playCx, l.ctrlY));
        check("上一首键不可拖", !hitDragArea(w, l.prevCx, l.ctrlY));
        check("进度条不可拖", !hitDragArea(w, l.barX + l.barW / 2, l.barY));
        check("窗口外不可拖", !hitDragArea(w, -20, midY));

        // 1) 文字区拖动：窗口应该跟着走 (+40, +24)
        Point before = w.getLocation();
        drag(surface, textX, midY, 40, 24);
        Thread.sleep(150);
        Point after = w.getLocation();
        check("文字区拖动移动窗口 +40/+24",
                after.x - before.x == 40 && after.y - before.y == 24);

        // 2) 封面区拖动：同样应该移动
        before = w.getLocation();
        drag(surface, l.coverX + l.coverSize / 2, midY, -30, 12);
        Thread.sleep(150);
        after = w.getLocation();
        check("封面区拖动移动窗口 -30/+12",
                after.x - before.x == -30 && after.y - before.y == 12);

        // 3) 多次 dragged 事件：定位必须是「起点 + 总位移」，不能累加成 10+20+30=60
        before = w.getLocation();
        multiDrag(surface, textX, midY, new int[]{10, 20, 30});
        Thread.sleep(150);
        after = w.getLocation();
        check("连发 dragged 不越拖越快（总位移 30）", after.x - before.x == 30);
        check("连发 dragged 纵向不动", after.y - before.y == 0);

        // 4) 按住不动就松手：不该把窗口挪走，也不该进入吸附分支
        before = w.getLocation();
        press(surface, textX, midY);
        release(surface, textX, midY, 1);
        Thread.sleep(100);
        after = w.getLocation();
        check("按住不动松手不动窗口", after.equals(before));

        // 5) 进度条上拖动：窗口不该移动（那是调进度）
        before = w.getLocation();
        drag(surface, l.barX + l.barW / 2, l.barY, 40, 24);
        Thread.sleep(150);
        after = w.getLocation();
        check("进度条拖动不移动窗口", after.equals(before));

        // 6) 悬停封面左缘（原手柄位置）：封面现在单击唤起宿主，光标给 HAND 提示"这里可以点"
        //    注意要加 shadow：命中测试吃的是窗口坐标，而 coverX 是内容坐标
        move(surface, l.coverX + l.shadow, midY);
        Thread.sleep(80);
        check("封面左缘悬停光标为 HAND", w.getCursor().getType() == Cursor.HAND_CURSOR);

        // 7) 悬停文字区：也是 MOVE（整条都能拖）
        move(surface, textX, midY);
        Thread.sleep(80);
        check("文字区悬停光标为 MOVE", w.getCursor().getType() == Cursor.MOVE_CURSOR);

        // 8) 悬停播控键：HAND，而不是 MOVE
        move(surface, l.playCx, l.ctrlY);
        Thread.sleep(80);
        check("播控键悬停为 HAND", w.getCursor().getType() == Cursor.HAND_CURSOR);

        // 9) 锁定：拖动必须彻底失效，且光标不能再暗示"能拖"。
        //    锁定从前只挡拖动、右键菜单照弹，用户要求两样一起关掉；拖动这一半在这里钉住，
        //    菜单那一半靠 mousePressed/mouseReleased 里的 locked 早退（见 LockProbe）。
        Field cfgField = MiniPlayerWindow.class.getDeclaredField("config");
        cfgField.setAccessible(true);
        PluginConfig liveCfg = (PluginConfig) cfgField.get(w);
        liveCfg.locked = true;

        bendDragAssert(w, surface, textX, midY, l);
        move(surface, textX, midY);
        Thread.sleep(80);
        check("锁定后悬停文字区不给 MOVE 光标",
                w.getCursor().getType() != Cursor.MOVE_CURSOR);

        liveCfg.locked = false;

        w.setVisible(false);
        w.dispose();
    }

    /** 锁定态下按住拖：窗口不该动。 */
    private static void bendDragAssert(MiniPlayerWindow w, Component surface,
                                       int lx, int ly, Layout l) throws Exception {
        Point before = w.getLocation();
        drag(surface, lx, ly, 40, 24);
        Thread.sleep(150);
        check("锁定后拖动不移动窗口", w.getLocation().equals(before));
        // 顺便确认尺寸也没被布局顺手改掉
        check("锁定后窗口尺寸不变",
                w.getWidth() == l.windowW && w.getHeight() == l.windowH);
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

    /** 合成事件的屏幕坐标：源组件不可见时 LocationOnScreen 会抛，这里直接按窗口位置补。 */
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

    /**
     * 合成事件。
     *
     * <p>{@code absX/absY} 必须由调用方按「按下那一刻的屏幕原点 + 像素坐标」算好并一直沿用。
     * 窗口在拖动过程中会移动，如果每个事件都用当时的 {@code getLocationOnScreen()} 重新算，
     * 屏幕坐标会随窗口一起漂移，{@code mouseDragged} 里算出来的总位移就被重复计入——
     * 探针会误报"越拖越快"，而真实指针的屏幕位置本来就是不会跟着窗口跑的。</p>
     */
    private static MouseEvent event(Component target, int id, long when, int x, int y,
                                    int absX, int absY, int clicks) {
        return new MouseEvent(target, id, when, MouseEvent.BUTTON1_DOWN_MASK,
                x, y, absX + x, absY + y, clicks, false, MouseEvent.BUTTON1);
    }

    private static void press(Component target, int lx, int ly) {
        long when = System.currentTimeMillis();
        target.dispatchEvent(event(target, MouseEvent.MOUSE_PRESSED, when, lx, ly,
                absX(target), absY(target), 1));
    }

    private static void release(Component target, int lx, int ly, int clicks) {
        release(target, lx, ly, clicks, absX(target), absY(target));
    }

    private static void release(Component target, int lx, int ly, int clicks, int bx, int by) {
        long when = System.currentTimeMillis();
        target.dispatchEvent(event(target, MouseEvent.MOUSE_RELEASED, when + 60, lx, ly, bx, by, clicks));
    }

    private static void drag(Component target, int lx, int ly, int dx, int dy) {
        long when = System.currentTimeMillis();
        int bx = absX(target);
        int by = absY(target);
        target.dispatchEvent(event(target, MouseEvent.MOUSE_PRESSED, when, lx, ly, bx, by, 1));
        target.dispatchEvent(event(target, MouseEvent.MOUSE_DRAGGED, when + 30, lx + dx, ly + dy, bx, by, 1));
        release(target, lx + dx, ly + dy, 1, bx, by);
    }

    /** 从同一起点连续发多个 dragged 事件，检查定位没有被累加。 */
    private static void multiDrag(Component target, int lx, int ly, int[] steps) {
        long when = System.currentTimeMillis();
        int bx = absX(target);
        int by = absY(target);
        target.dispatchEvent(event(target, MouseEvent.MOUSE_PRESSED, when, lx, ly, bx, by, 1));
        for (int i = 0; i < steps.length; i++) {
            target.dispatchEvent(event(target, MouseEvent.MOUSE_DRAGGED,
                    when + 20L * (i + 1), lx + steps[i], ly, bx, by, 1));
        }
        release(target, lx + steps[steps.length - 1], ly, 1, bx, by);
    }

    private static void move(Component target, int lx, int ly) {
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                0, lx, ly, absX(target) + lx, absY(target) + ly, 0, false, MouseEvent.NOBUTTON));
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
