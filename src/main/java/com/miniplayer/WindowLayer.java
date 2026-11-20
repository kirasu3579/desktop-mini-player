package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.awt.Window;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Win32 窗口层级控制（纯 {@code java.lang.foreign}，不引入 JNA）。
 *
 * <p>为什么不用 Java 的 {@link Window#setAlwaysOnTop(boolean)}：它只提供两态——
 * 要么压在 TOPMOST 层最上面（连全屏窗口也盖住），要么就是普通层（连任务栏都压不住）。
 * 用户要的是中间那一档："压住任务栏，但全屏窗口出现时让位"。Windows 没有这个原生
 * 档位，只能自己算：常态挂 TOPMOST，检测到前台全屏应用就临时降到普通层。</p>
 *
 * <p>另一个关键点：<b>这里不做事倍保活</b>。{@code toFront()} 在 Windows 上等价于一次
 * {@code SetWindowPos(HWND_TOP)}，每 250ms 调一次就是"窗口一闪一闪"的来源。TOPMOST
 * 窗口一旦挂上就在该层内保持位置（探针连续采样 2 秒验证过），只有被任务栏那种同为
 * TOPMOST 的系统窗口插到前面时才需要重排一次。</p>
 *
 * <p>宿主启动参数带 {@code --enable-native-access=ALL-UNNAMED}，所以 FFM 在真实环境可用。
 * 万一某个函数取不到，{@link #available()} 返回 false，调用方退化为 Java 侧行为，
 * 不会崩。</p>
 */
final class WindowLayer {

    private WindowLayer() {
    }

    // ---------------------------------------------------------------- Win32 常量

    /** {@code GetWindow(hwnd, GW_OWNER)}：取属主窗口。 */
    private static final int GW_OWNER = 4;
    /** {@code GetWindow(hwnd, GW_HWNDPREV)}：z 序里紧挨在下面的那个窗口。 */
    private static final int GW_HWNDPREV = 3;
    /** {@code GetWindowLong(hwnd, GWL_STYLE)}。 */
    private static final int GWL_STYLE = -16;
    /** 有它才是"带标题栏的普通窗口"；独占全屏与无边框全屏都没有。 */
    private static final int WS_CAPTION = 0x00C00000;
    private static final int MONITOR_DEFAULTTONEAREST = 2;
    private static final int SW_RESTORE = 9;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;

    private static final MemorySegment HWND_TOPMOST = MemorySegment.ofAddress(-1L);
    private static final MemorySegment HWND_NOTOPMOST = MemorySegment.ofAddress(-2L);

    /**
     * {@code HWND_TOP}：把窗口放在<b>自己所在那一层</b>的最前面。
     *
     * <p>这和 {@link #HWND_TOPMOST} 的区别是本轮修复的关键：对一个已经挂在 TOPMOST
     * 层的窗口再调 {@code SetWindowPos(.., HWND_TOPMOST, ..)} 是<b>层内空操作</b>，
     * 它不会把窗口从该层底部挪到顶部。所以「判到被任务栏压住 → 重新挂 TOPMOST」
     * 可以无限循环而层级一动不动，用户看到的就是迷你条一直待在任务栏下面（消失）。
     * 要把它在层内提回最前，必须用 {@code HWND_TOP}（值为 0）。</p>
     */
    private static final MemorySegment HWND_TOP = MemorySegment.ofAddress(0L);

    /** {@code MONITORINFO} 的 cbSize：4 个坐标 × 4 字节 × 2 组 + 8 字节头 + 4 字节标志。 */
    private static final int MONITORINFO_CBSIZE = 40;

    /**
     * 「主界面窗口」的最小面积（物理像素²）。
     *
     * <p>Compose/Skiko 会在本进程里生成一批辅助 {@code SunAwtFrame}（拖拽代理、
     * 输入法宿主、离屏合成代理），尺寸往往只有几十像素见方。主界面至少是几百
     * 像素宽的窗口，用 200×200 作为下界足够把它们全部排除，又不会误伤用户
     * 手动缩到很小的主窗口。</p>
     */
    private static final long MAIN_WINDOW_MIN_AREA = 200L * 200L;

    // ---------------------------------------------------------------- 函数句柄

    private static boolean available;
    private static Linker linker;

    private static MethodHandle hEnumWindows;
    private static MethodHandle hGetWindowThreadProcessId;
    private static MethodHandle hGetClassNameW;
    /**
     * {@code InternalGetWindowText}：直接读内核缓存的窗口标题，<b>不发 {@code WM_GETTEXT}</b>。
     *
     * <p>必须用它而不是 {@code GetWindowTextW}：后者对<b>非本线程拥有</b>的窗口会
     * {@code SendMessage(WM_GETTEXT)} 并同步阻塞等待对方线程处理。本插件的点击处理
     * 跑在 AWT EDT 上，而宿主进程里 Compose/Skiko 的辅助帧、Tray、IME 宿主各由别的
     * 线程托管——EDT 等它们、它们又可能要等 EDT，直接死锁。日志证据：宿主自己写了
     * {@code failure.kind=TERMINATED_HANG / outcome=UNRESPONSIVE}，时间就停在
     * {@code dumpOwnFrames} 的最后一行。</p>
     *
     * <p>{@code InternalGetWindowText} 是 user32 的未文档化导出（序号 554，带名字），
     * 正是为此类场景准备：不排队、不跨线程等待。</p>
     */
    private static MethodHandle hInternalGetWindowText;
    private static MethodHandle hGetWindowRect;
    private static MethodHandle hGetWindowLongW;
    private static MethodHandle hIsWindowVisible;
    private static MethodHandle hGetWindow;
    private static MethodHandle hSetWindowPos;
    private static MethodHandle hShowWindow;
    private static MethodHandle hBringWindowToTop;
    private static MethodHandle hSetForegroundWindow;
    private static MethodHandle hIsIconic;
    private static MethodHandle hFindWindowW;
    private static MethodHandle hGetForegroundWindow;
    private static MethodHandle hMonitorFromWindow;
    private static MethodHandle hGetMonitorInfoW;
    private static MethodHandle hGetCurrentProcessId;
    private static MethodHandle hSHQueryUserNotificationState;

    /** 最近一次 {@link #syncLayer} 设定的层级，用于只在翻转时才真的重排。 */
    private static boolean lastTopmost = true;

    static {
        try {
            linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

            hEnumWindows = handle(user32, "EnumWindows",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            hGetWindowThreadProcessId = handle(user32, "GetWindowThreadProcessId",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            hGetClassNameW = handle(user32, "GetClassNameW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            // 用 InternalGetWindowText 而不是 GetWindowTextW：后者会 SendMessage(WM_GETTEXT)
            // 并阻塞等待目标窗口所属线程，在 EDT 上调用有死锁风险（见字段注释）。
            // 它是未文档化导出，理论上一定存在；万一取不到，titleOf 会降级为返回空串，
            // 由调用方用「可见性 + 面积」兜底，绝不回退到 GetWindowTextW。
            try {
                hInternalGetWindowText = handle(user32, "InternalGetWindowText",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            } catch (Throwable t) {
                hInternalGetWindowText = null;
            }
            hGetWindowRect = handle(user32, "GetWindowRect",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            hGetWindowLongW = handle(user32, "GetWindowLongW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            hIsWindowVisible = handle(user32, "IsWindowVisible",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            hGetWindow = handle(user32, "GetWindow",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            hSetWindowPos = handle(user32, "SetWindowPos",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            hShowWindow = handle(user32, "ShowWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            hBringWindowToTop = handle(user32, "BringWindowToTop",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            hSetForegroundWindow = handle(user32, "SetForegroundWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            hIsIconic = handle(user32, "IsIconic",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            hFindWindowW = handle(user32, "FindWindowW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            hGetForegroundWindow = handle(user32, "GetForegroundWindow",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            hMonitorFromWindow = handle(user32, "MonitorFromWindow",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            hGetMonitorInfoW = handle(user32, "GetMonitorInfoW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            hGetCurrentProcessId = handle(kernel32, "GetCurrentProcessId",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            // shell32 单独取：取不到只影响全屏判定的一个辅助信号，不影响主流程
            try {
                SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", Arena.global());
                hSHQueryUserNotificationState = handle(shell32, "SHQueryUserNotificationState",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            } catch (Throwable ignored) {
                hSHQueryUserNotificationState = null;
            }

            available = true;
        } catch (Throwable t) {
            available = false;
            MiniPlayerPlugin.log("WindowLayer init failed: " + t);
        }
    }

    private static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.find(name).orElseThrow(), fd);
    }

    static boolean available() {
        return available;
    }

    // ---------------------------------------------------------------- 窗口句柄

    /** {@code EnumWindows} 回调里累积结果；每次查询前清空，只在调用线程内使用。 */
    private static List<Long> collect;

    private static int enumProc(MemorySegment hwnd, long lParam) {
        if (collect != null) {
            collect.add(hwnd.address());
        }
        // 返回 1 继续枚举；任何异常都不能逃出回调
        return 1;
    }

    /** 枚举所有顶层窗口的 HWND（调用方负责后续过滤）。 */
    private static List<Long> enumWindows(Arena arena) {
        List<Long> out = new ArrayList<>();
        try {
            MemorySegment cb = linker.upcallStub(
                    MethodHandles.lookup().findStatic(WindowLayer.class, "enumProc",
                            MethodType.methodType(int.class, MemorySegment.class, long.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    arena);
            collect = out;
            try {
                // invokeExact 的签名必须与 FunctionDescriptor 完全一致——包括返回值。
                // 写成语句（丢弃返回值）会被解析成返回 void 的方法类型，运行时抛
                // WrongMethodTypeException；这里必须显式接住 int。
                int ok = (int) hEnumWindows.invokeExact(cb, 0L);
                if (ok == 0 && out.isEmpty()) {
                    MiniPlayerPlugin.log("WindowLayer: EnumWindows returned 0");
                }
            } finally {
                collect = null;
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("WindowLayer: enumWindows failed: " + t);
        }
        return out;
    }

    private static int pidOf(Arena arena, long hwnd) {
        try {
            MemorySegment pidBuf = arena.allocate(ValueLayout.JAVA_INT);
            // 返回的是线程 id，但同样必须接住——否则方法类型对不上
            int tid = (int) hGetWindowThreadProcessId.invokeExact(MemorySegment.ofAddress(hwnd), pidBuf);
            if (tid == 0) {
                return 0;
            }
            return pidBuf.get(ValueLayout.JAVA_INT, 0L);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int currentPid() {
        try {
            return (int) hGetCurrentProcessId.invokeExact();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String classNameOf(Arena arena, long hwnd) {
        try {
            MemorySegment buf = arena.allocate(256L);
            int n = (int) hGetClassNameW.invokeExact(MemorySegment.ofAddress(hwnd), buf, 256);
            if (n <= 0) {
                return "";
            }
            return new String(buf.asSlice(0, (long) n * 2L).toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 读窗口标题；失败或无标题返回空串。
     *
     * <p>走 {@code InternalGetWindowText}（不发消息、不阻塞）。<b>不要改成
     * {@code GetWindowTextW}</b>：它对非本线程窗口会同步等待对方线程，
     * 在 EDT 上调用会让宿主整体无响应（原因见 {@link #hInternalGetWindowText}）。</p>
     */
    private static String titleOf(Arena arena, long hwnd) {
        if (hInternalGetWindowText == null) {
            return "";
        }
        try {
            MemorySegment buf = arena.allocate(512L);
            int n = (int) hInternalGetWindowText.invokeExact(
                    MemorySegment.ofAddress(hwnd), buf, 256);
            if (n <= 0) {
                return "";
            }
            return new String(buf.asSlice(0, (long) n * 2L).toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int[] rectOf(Arena arena, long hwnd) {
        try {
            MemorySegment r = arena.allocate(16L);
            if ((int) hGetWindowRect.invokeExact(MemorySegment.ofAddress(hwnd), r) == 0) {
                return null;
            }
            int left = r.get(ValueLayout.JAVA_INT, 0L);
            int top = r.get(ValueLayout.JAVA_INT, 4L);
            int right = r.get(ValueLayout.JAVA_INT, 8L);
            int bottom = r.get(ValueLayout.JAVA_INT, 12L);
            if (right <= left || bottom <= top) {
                return null;
            }
            return new int[]{left, top, right, bottom};
        } catch (Throwable t) {
            return null;
        }
    }

    private static long ownerOf(long hwnd) {
        try {
            MemorySegment owner = (MemorySegment) hGetWindow.invokeExact(
                    MemorySegment.ofAddress(hwnd), GW_OWNER);
            return owner.address();
        } catch (Throwable t) {
            return 0L;
        }
    }

    static boolean isVisible(long hwnd) {
        if (!available) {
            return false;
        }
        try {
            return (int) hIsWindowVisible.invokeExact(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 查某个 Java 窗口对应的 HWND。
     *
     * <p>不能用反射读 {@code Component.peer}：宿主 cfg 只对 {@code sun.awt} 与
     * {@code sun.awt.windows} 开了 {@code --add-opens}，{@code java.awt} 没开，
     * 实测抛 {@code InaccessibleObjectException}。所以走"进程 pid + 窗口类名 +
     * 物理尺寸"匹配。</p>
     *
     * <p><b>HWND 的尺寸是物理像素，Java 的尺寸是逻辑像素</b>，必须乘缩放比再比，
     * 否则永远匹配不上（本机 1.5 倍：304×48 逻辑 → 456×72 物理）。</p>
     *
     * @return HWND；找不到或 FFM 不可用时返回 0
     */
    static long hwndOf(Window window) {
        if (!available || window == null || !window.isDisplayable()) {
            return 0L;
        }
        try (Arena arena = Arena.ofConfined()) {
            int pid = currentPid();
            if (pid < 0) {
                return 0L;
            }
            GraphicsConfiguration gc = window.getGraphicsConfiguration();
            double scale = gc == null ? 1.0
                    : gc.getDefaultTransform().getScaleX();
            int wantW = (int) Math.round(window.getWidth() * scale);
            int wantH = (int) Math.round(window.getHeight() * scale);
            String wantClass = window instanceof java.awt.Frame ? "SunAwtFrame" : "SunAwtWindow";

            long best = 0L;
            int bestScore = Integer.MAX_VALUE;
            for (long hwnd : enumWindows(arena)) {
                if (pidOf(arena, hwnd) != pid) {
                    continue;
                }
                if (!wantClass.equals(classNameOf(arena, hwnd))) {
                    continue;
                }
                int[] r = rectOf(arena, hwnd);
                if (r == null) {
                    continue;
                }
                int score = Math.abs((r[2] - r[0]) - wantW) + Math.abs((r[3] - r[1]) - wantH);
                if (score < bestScore) {
                    bestScore = score;
                    best = hwnd;
                }
            }
            // 尺寸差超过 40 物理像素就不认（缩放/边框可能带来几像素误差，但不会差这么多）
            return bestScore <= 40 ? best : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 找本进程里"宿主主界面窗口"的那个 HWND。
     *
     * <p>候选必须同时满足：类名 {@code SunAwtFrame}、无属主、<b>当前可见</b>、
     * 有真实标题，并且<b>面积足够大 <u>或</u> 被丢到屏幕外</b>。</p>
     *
     * <p><b>可见性是硬要求</b>：主界面被关掉/最小化到托盘时，Windows 里仍留着它的
     * HWND（只是隐藏），早先只按"面积最大"挑，就会挑到一个隐藏的帧窗口，再拿
     * {@code ShowWindow(SW_RESTORE)} 一显示——用户看到的就是"点封面弹出一个奇怪的
     * 新窗口"。日志里 {@code show=0} 正是这个信号：ShowWindow 之前那窗口是隐藏的。</p>
     *
     * <p><b>标题也是硬要求</b>：Compose/Skiko 会生成一批无标题的辅助 {@code SunAwtFrame}
     * （拖拽代理、输入法宿主等），它们面积可能不小，但都不是用户认知里的"软件窗口"。</p>
     *
     * <p><b>为什么还要认"屏幕外"的窗口</b>：宿主"关闭主界面"的实现是经典的假最小化——
     * 窗口保持 {@code IsWindowVisible} 为真、标题还在，但矩形被扔到
     * {@code -32000,-32000}。这种窗口面积算出来只有两百多像素宽（日志实测
     * {@code 237×39}），会被面积门槛挡掉，于是点封面静默无事、用户以为坏了。
     * 屏外判据取得很窄（{@code right < -10000 || bottom < -10000}），不会误伤正常窗口。</p>
     *
     * @param exclude 迷你条自己的 HWND，必须排除
     * @return 主界面 HWND；找不到（比如用户把主界面彻底关了）返回 0，调用方据此保持沉默
     */
    static long findMainWindowHwnd(long exclude) {
        if (!available) {
            return 0L;
        }
        try (Arena arena = Arena.ofConfined()) {
            int pid = currentPid();
            if (pid < 0) {
                return 0L;
            }
            long best = 0L;
            long bestArea = 0L;
            boolean bestOffscreen = false;
            int candidates = 0;
            for (long hwnd : enumWindows(arena)) {
                if (hwnd == exclude) {
                    continue;
                }
                if (pidOf(arena, hwnd) != pid) {
                    continue;
                }
                if (!"SunAwtFrame".equals(classNameOf(arena, hwnd))) {
                    continue;
                }
                if (ownerOf(hwnd) != 0L) {
                    continue;
                }
                // 隐藏的窗口不是用户想看的"软件窗口"；把它 ShowWindow 出来
                // 等于凭空造一个窗口，正是之前"点封面冒出奇怪窗口"的来源
                if (!isVisible(hwnd)) {
                    continue;
                }
                if (titleOf(arena, hwnd).isEmpty()) {
                    continue;
                }
                int[] r = rectOf(arena, hwnd);
                if (r == null) {
                    continue;
                }
                candidates++;
                long area = (long) (r[2] - r[0]) * (long) (r[3] - r[1]);
                // 宿主"关掉主窗"= 扔到 -32000 屏外，这种必须认出来
                boolean offscreen = r[2] < -10000 || r[3] < -10000;
                // 太小的不是主界面（Compose 的辅助帧往往几十像素见方），
                // 但屏外的例外：假最小化后宽度会被压到两百多
                if (!offscreen && area < MAIN_WINDOW_MIN_AREA) {
                    continue;
                }
                // 屏外窗口优先（它是"用户以为已经关掉了"的那个），同类里取面积最大的
                boolean better = best == 0L
                        || (offscreen && !bestOffscreen)
                        || (offscreen == bestOffscreen && area > bestArea);
                if (better) {
                    bestArea = area;
                    best = hwnd;
                    bestOffscreen = offscreen;
                }
            }
            if (best != 0L) {
                MiniPlayerPlugin.log("host.main hwnd=0x" + Long.toHexString(best)
                        + " offscreen=" + bestOffscreen
                        + " title=[" + titleOf(arena, best) + "]");
            } else {
                // 日常只记一行：真正逐窗口 dump 只在开了调试开关时做，
                // 因为它是 EDT 上的几十毫秒阻塞（每行一次 open/write/close）
                MiniPlayerPlugin.log("host.main not found (candidates=" + candidates + ")");
                if (Boolean.getBoolean("miniplayer.debug.frames")) {
                    dumpOwnFrames(arena, pid);
                }
            }
            return best;
        } catch (Throwable t) {
            MiniPlayerPlugin.log("WindowLayer: findMainWindowHwnd failed: " + t);
            return 0L;
        }
    }

    /** 诊断：列出本进程所有顶层窗口的类名、标题、可见性与矩形（只在定位失败时调）。 */
    private static void dumpOwnFrames(Arena arena, int pid) {
        try {
            for (long hwnd : enumWindows(arena)) {
                if (pidOf(arena, hwnd) != pid) {
                    continue;
                }
                int[] r = rectOf(arena, hwnd);
                MiniPlayerPlugin.log("  frame 0x" + Long.toHexString(hwnd)
                        + " class=" + classNameOf(arena, hwnd)
                        + " title=[" + titleOf(arena, hwnd) + "]"
                        + " visible=" + isVisible(hwnd)
                        + " owner=0x" + Long.toHexString(ownerOf(hwnd))
                        + " rect=" + (r == null ? "null" : r[0] + "," + r[1] + "," + r[2] + "," + r[3]));
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("WindowLayer: dumpOwnFrames failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 层级

    /**
     * 设定窗口是否挂在 TOPMOST 层。
     *
     * <p>用 {@code SWP_NOACTIVATE} 保证不会抢焦点——迷你条本来就不该抢宿主的键盘。</p>
     */
    static boolean setTopmost(long hwnd, boolean on) {
        if (!available || hwnd == 0L) {
            return false;
        }
        try {
            // 注意：这里不能写成 `on ? HWND_TOPMOST : HWND_NOTOPMOST` 直接传参。
            // 三元表达式会按"最具体公共父类"推断类型，两个 MemorySegment 常量会被提升成
            // Object，invokeExact 的签名随之变成 (MemorySegment,Object,...)，
            // 与 FunctionDescriptor 的 (MemorySegment,MemorySegment,...) 不匹配，
            // 运行时抛 WrongMethodTypeException（踩过一次，别改回去）。
            MemorySegment after = on ? HWND_TOPMOST : HWND_NOTOPMOST;
            int ok = (int) hSetWindowPos.invokeExact(
                    MemorySegment.ofAddress(hwnd),
                    after,
                    0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            if (ok != 0) {
                lastTopmost = on;
            }
            return ok != 0;
        } catch (Throwable t) {
            MiniPlayerPlugin.log("WindowLayer: setTopmost failed: " + t);
            return false;
        }
    }

    static boolean lastTopmost() {
        return lastTopmost;
    }

    /**
     * 任务栏是否压在 {@code hwnd} 之上（也就是我们被任务栏盖住了）。
     *
     * <p><b>判法：沿 {@code GW_HWNDPREV} 链从自己往上走，途中先撞上任务栏就是被压住</b>；
     * 一路走到顶（链断成 0）都没撞上，说明我们排在任务栏前面。</p>
     *
     * <p>两条探针基准线（{@code StableProbe} 实测，可用来校准这个判据）：</p>
     * <ul>
     *   <li>挂 TOPMOST 时：上溯 <b>13–14 层</b>到顶，全程不见任务栏 → 返回 false</li>
     *   <li>降到普通层时：上溯 <b>75–80 层</b>撞上任务栏 → 返回 true</li>
     * </ul>
     *
     * <p><b>不要改回「EnumWindows 返回顺序就是 z 序」那种写法</b>：实测 EnumWindows 的
     * 顺序并不是 z 序（任务栏排在第 10 位、迷你条排在第 80 位，而迷你条实际在任务栏
     * 之上），按索引比较会恒为 true，导致 {@code syncLayer} 每秒都重排一次——
     * 那就是用户报的「和任务栏其他组件交互时一闪一闪」。</p>
     *
     * <p>只看任务栏，不看别的 TOPMOST 窗口：我们自己就是靠"排在任务栏前面"
     * 来工作的，其它置顶应用留给用户自己处理，不该由我们反复抢。</p>
     */
    static boolean trayCovers(long hwnd) {
        if (!available || hwnd == 0L) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            // FindWindowW 的字符串参数必须是 UTF-16 指针：默认 UTF-8 会静默返回 0
            MemorySegment cls = arena.allocateFrom("Shell_TrayWnd", StandardCharsets.UTF_16LE);
            MemorySegment traySeg = (MemorySegment) hFindWindowW.invokeExact(cls, MemorySegment.NULL);
            long trayHwnd = traySeg.address();
            if (trayHwnd == 0L) {
                return false;
            }
            long cur = hwnd;
            for (int i = 0; i < 256; i++) {
                MemorySegment prev = (MemorySegment) hGetWindow.invokeExact(
                        MemorySegment.ofAddress(cur), GW_HWNDPREV);
                cur = prev.address();
                if (cur == 0L) {
                    // 走到 z 序顶端都没遇到任务栏：我们在任务栏之上
                    return false;
                }
                if (cur == trayHwnd) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在<b>保持 TOPMOST 属性不变</b>的前提下，把窗口在自己那一层内部提到最前。
     *
     * <p>这是「迷你条被任务栏盖住/消失」的正解。{@link #setTopmost} 用的是
     * {@code HWND_TOPMOST}，而对一个已经挂在 TOPMOST 层的窗口，这个调用是层内空操作：
     * 无论调多少次，它的层内位置都不变。所以自愈必须走 {@code HWND_TOP}。</p>
     *
     * <p>退路：若 {@code HWND_TOP} 失败，或者调用后 {@link #trayCovers} 仍为真，就做一次
     * {@code HWND_NOTOPMOST → HWND_TOPMOST} 两连击——掉出该层再回来，层内顺序必然重置。
     * 两连击有极短的视觉风险，所以只在第一条路不奏效时才用。</p>
     *
     * @return 是否确认已经（很可能）回到任务栏之上
     */
    static boolean raiseWithinTopmost(long hwnd) {
        if (!available || hwnd == 0L) {
            return false;
        }
        // HWND_TOP 是正路：只动层内顺序，不改变 TOPMOST 属性，也不闪
        if (raiseWith(hwnd, HWND_TOP) && !trayCovers(hwnd)) {
            return true;
        }
        MiniPlayerPlugin.log("layer raise fallback hwnd=0x" + Long.toHexString(hwnd));
        if (!raiseWith(hwnd, HWND_NOTOPMOST)) {
            return false;
        }
        if (!raiseWith(hwnd, HWND_TOPMOST)) {
            return false;
        }
        return !trayCovers(hwnd);
    }

    private static boolean raiseWith(long hwnd, MemorySegment after) {
        try {
            // 同 setTopmost：after 必须先落到变量里，直接在三元里传会把签名变成 Object
            int ok = (int) hSetWindowPos.invokeExact(
                    MemorySegment.ofAddress(hwnd),
                    after,
                    0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            return ok != 0;
        } catch (Throwable t) {
            MiniPlayerPlugin.log("WindowLayer: raiseWith failed: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 全屏判定

    /**
     * 前台是否是一个"真全屏"窗口（全屏视频、游戏、独占全屏、浏览器 F11）。
     *
     * <p>口径（探针校准过）：前台窗口与其显示器的 {@code rcMonitor} 相比，覆盖率
     * ≥ 0.995，或者 ≥ 0.96 且<b>没有标题栏</b>（{@code WS_CAPTION}）。后者是必要的：
     * 普通最大化的窗口会有 -11px 的隐形边框外扩，覆盖率也会到 0.97，但它带标题栏，
     * 不该被判成全屏。</p>
     *
     * @param selfHwnd 迷你条自己的 HWND，前台就是自己时不算全屏
     */
    static boolean fullscreenForeground(long selfHwnd) {
        if (!available) {
            return false;
        }
        try {
            MemorySegment fgSeg = (MemorySegment) hGetForegroundWindow.invokeExact();
            long fg = fgSeg.address();
            if (fg == 0L || fg == selfHwnd) {
                return false;
            }
            try (Arena arena = Arena.ofConfined()) {
                // 桌面与任务栏本身就是全屏尺寸，但它们不是"应用全屏"
                String cls = classNameOf(arena, fg);
                if ("Progman".equals(cls) || "WorkerW".equals(cls)
                        || "Shell_TrayWnd".equals(cls)) {
                    return false;
                }
                if (qunsSaysFullscreen(arena)) {
                    return true;
                }
                int[] r = rectOf(arena, fg);
                if (r == null) {
                    return false;
                }
                MemorySegment mon = (MemorySegment) hMonitorFromWindow.invokeExact(
                        MemorySegment.ofAddress(fg), MONITOR_DEFAULTTONEAREST);
                if (mon.address() == 0L) {
                    return false;
                }
                MemorySegment mi = arena.allocate(64L);
                mi.set(ValueLayout.JAVA_INT, 0L, MONITORINFO_CBSIZE);
                if ((int) hGetMonitorInfoW.invokeExact(mon, mi) == 0) {
                    return false;
                }
                int mLeft = mi.get(ValueLayout.JAVA_INT, 4L);
                int mTop = mi.get(ValueLayout.JAVA_INT, 8L);
                int mRight = mi.get(ValueLayout.JAVA_INT, 12L);
                int mBottom = mi.get(ValueLayout.JAVA_INT, 16L);
                long monArea = (long) (mRight - mLeft) * (long) (mBottom - mTop);
                if (monArea <= 0L) {
                    return false;
                }
                int ix = Math.max(r[0], mLeft);
                int iy = Math.max(r[1], mTop);
                int ir = Math.min(r[2], mRight);
                int ib = Math.min(r[3], mBottom);
                if (ir <= ix || ib <= iy) {
                    return false;
                }
                double cover = (double) (ir - ix) * (ib - iy) / (double) monArea;
                int style = (int) hGetWindowLongW.invokeExact(
                        MemorySegment.ofAddress(fg), GWL_STYLE);
                boolean caption = (style & WS_CAPTION) == WS_CAPTION;
                return cover >= 0.995 || (cover >= 0.96 && !caption);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code SHQueryUserNotificationState} 的旁证：返回 3（D3D 全屏）或 4（演示模式）
     * 时基本可以确定有独占全屏应用。
     */
    private static boolean qunsSaysFullscreen(Arena arena) {
        if (hSHQueryUserNotificationState == null) {
            return false;
        }
        try {
            MemorySegment state = arena.allocate(ValueLayout.JAVA_INT);
            int hr = (int) hSHQueryUserNotificationState.invokeExact(state);
            if (hr != 0) {
                return false;
            }
            int s = state.get(ValueLayout.JAVA_INT, 0L);
            return s == 3 || s == 4;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 唤起宿主

    /**
     * 把窗口还原并尝试提到前台。
     *
     * <p>四级兜底，逐级记日志：</p>
     * <ol>
     *   <li>{@code ShowWindow(SW_RESTORE)}：从最小化恢复</li>
     *   <li><b>从屏幕外拉回</b>：宿主"关闭主界面"用的是经典假最小化（矩形扔到
     *       {@code -32000,-32000}），这时 {@code IsIconic} 返回 0，前一步什么都不做，
     *       用户看到的就是"点封面没反应"。所以必须先把它挪回当前显示器工作区内。</li>
     *   <li>{@code BringWindowToTop}：z 序</li>
     *   <li>{@code SetForegroundWindow}：真正给焦点（受 Windows 前台锁定约束，
     *       只有在用户刚点过我们时才保证成功，所以不假设它一定成功）</li>
     * </ol>
     *
     * <p><b>只对"用户认知里的那个窗口"动手</b>：调用前先确认它是可见的。
     * {@code ShowWindow(SW_RESTORE)} 对隐藏窗口的效果是<b>把它显示出来</b>——
     * 早先不做这层检查，结果点封面会凭空冒出一个没人认识的窗口
     * （日志症状：{@code show=0}，说明 ShowWindow 之前它是隐藏的）。</p>
     *
     * @return 是否确认窗口已显示（不代表抢到了前台）
     */
    static boolean restoreAndActivate(long hwnd) {
        if (!available || hwnd == 0L) {
            return false;
        }
        // 隐藏的窗口不是"主界面"，显示它等于凭空造窗。用户把主界面关掉后再点封面，
        // 这里应该安静地什么都不做。
        if (!isVisible(hwnd)) {
            MiniPlayerPlugin.log("host.activate skipped: hwnd=0x"
                    + Long.toHexString(hwnd) + " not visible");
            return false;
        }
        boolean restored = false;
        try {
            MemorySegment h = MemorySegment.ofAddress(hwnd);
            int iconic = (int) hIsIconic.invokeExact(h);
            if (iconic != 0) {
                // 只有最小化时才需要 SW_RESTORE；对正常显示的窗口调用它没有副作用，
                // 但显式区分能让日志更容易读
                int ret = (int) hShowWindow.invokeExact(h, SW_RESTORE);
                restored = ret != 0;
                MiniPlayerPlugin.log("host.activate hwnd=0x" + Long.toHexString(hwnd)
                        + " iconic=" + iconic + " show=" + ret);
            } else {
                restored = true;
                MiniPlayerPlugin.log("host.activate hwnd=0x" + Long.toHexString(hwnd)
                        + " iconic=0 already shown");
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("host.activate showWindow failed: " + t);
        }
        // 假最小化：窗口还在、还 visible，但被扔到屏幕外。不拉回来用户就看不到。
        try (Arena arena = Arena.ofConfined()) {
            int[] r = rectOf(arena, hwnd);
            if (r != null && (r[2] < -10000 || r[3] < -10000)) {
                int[] work = workAreaOf(arena, hwnd);
                int w = r[2] - r[0];
                int hh = r[3] - r[1];
                int x = work[0] + (work[2] - work[0] - w) / 2;
                int y = work[1] + (work[3] - work[1] - hh) / 2;
                int ok = (int) hSetWindowPos.invokeExact(
                        MemorySegment.ofAddress(hwnd),
                        HWND_TOP,
                        x, y, 0, 0,
                        SWP_NOSIZE | SWP_SHOWWINDOW);
                MiniPlayerPlugin.log("host.activate offscreen=true pull=" + x + "," + y
                        + " ok=" + ok);
                // 拉回屏内后再走一次还原，防止它其实同时是最小化状态
                try {
                    int show2 = (int) hShowWindow.invokeExact(
                            MemorySegment.ofAddress(hwnd), SW_RESTORE);
                    restored = restored || show2 != 0;
                } catch (Throwable ignored) {
                    // 已经正常显示时这次调用是空操作，失败也不影响后续
                }
                restored = ok != 0 || restored;
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("host.activate pull failed: " + t);
        }
        try {
            int top = (int) hBringWindowToTop.invokeExact(MemorySegment.ofAddress(hwnd));
            int fg = (int) hSetForegroundWindow.invokeExact(MemorySegment.ofAddress(hwnd));
            MiniPlayerPlugin.log("host.activate bringToTop=" + top + " foreground=" + fg);
        } catch (Throwable t) {
            MiniPlayerPlugin.log("host.activate foreground failed: " + t);
        }
        return restored;
    }

    /** 取窗口所在显示器的工作区（{@code rcWork}）：{@code {left, top, right, bottom}}。 */
    private static int[] workAreaOf(Arena arena, long hwnd) {
        try {
            MemorySegment mon = (MemorySegment) hMonitorFromWindow.invokeExact(
                    MemorySegment.ofAddress(hwnd), MONITOR_DEFAULTTONEAREST);
            if (mon.address() != 0L) {
                MemorySegment mi = arena.allocate(64L);
                mi.set(ValueLayout.JAVA_INT, 0L, MONITORINFO_CBSIZE);
                if ((int) hGetMonitorInfoW.invokeExact(mon, mi) != 0) {
                    return new int[]{
                            mi.get(ValueLayout.JAVA_INT, 20L),
                            mi.get(ValueLayout.JAVA_INT, 24L),
                            mi.get(ValueLayout.JAVA_INT, 28L),
                            mi.get(ValueLayout.JAVA_INT, 32L)};
                }
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("WindowLayer: workAreaOf failed: " + t);
        }
        // 兜底：主屏常见尺寸，至少要让窗口出现在能看见的地方
        return new int[]{0, 0, 1920, 1080};
    }
}
