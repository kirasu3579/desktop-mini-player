package com.miniplayer;

/**
 * 把 Salt Player 的主窗口叫到前面来。
 *
 * <p>宿主插件 API 没有"打开主窗口"这种能力（{@code WorkshopApi.Ui} 只有
 * {@code toast}），所以只能走 Win32：在同一个进程里挑出宿主主窗，还原它并尝试前置。</p>
 *
 * <p><b>候选必须可见、有非空标题</b>，并且「面积 ≥200×200 <b>或</b> 被丢到屏幕外」
 * （见 {@link WindowLayer#findMainWindowHwnd(long)}）。只剩"最大且无属主的
 * {@code SunAwtFrame}"这一条是不够的：Compose/Skiko 会在宿主进程里生成一批拖拽代理、
 * 输入法宿主、离屏合成代理，它们无属主、面积时大时小，谁撞上"最大"就会被
 * {@code ShowWindow(SW_RESTORE)} 硬显示出来——用户看到的就是"点封面弹出一个奇怪的
 * 新窗口"，日志里表现为 {@code show=0}（调用前该窗口是隐藏的）。</p>
 *
 * <p><b>为什么还要认"屏幕外"的窗口</b>：宿主"关闭主界面"是经典的假最小化——窗口
 * 仍然可见、标题仍在，但矩形被扔到 {@code -32000,-32000}（日志实测
 * {@code rect=-32000,-32000,-31763,-31961}，算出来只有 237×39）。这种状态
 * {@code IsIconic} 返回 0，旧逻辑会认为"已经显示了"而什么都不做，用户就是"点封面
 * 打不开软件窗口"。现在这种窗口会被识别出来，并在
 * {@link WindowLayer#restoreAndActivate(long)} 里拉回工作区居中。</p>
 *
 * <p>为什么不复用 junique 的单实例通道（{@code %LOCALAPPDATA%\Salt Player for
 * Windows\junique\1456f4089.port} 里写着 {@code 13385}）：那是 junique 的内部协议，
 * 没有公开格式，靠猜字节格式发消息极易随宿主版本失效；而且它本来是用来"启动第二个
 * 实例时把参数转给已运行实例"的，未必会做"显示主窗口"这件事。</p>
 *
 * <p><b>调用线程</b>：这个方法绝不能跑在 AWT EDT 上。它内部要调一串 Win32 窗口 API，
 * 只要有一个对别的窗口同步等待，整条 UI 线程就跟着卡死（宿主会写
 * {@code failure.kind=TERMINATED_HANG / outcome=UNRESPONSIVE}）。调用方
 * {@code MiniPlayerWindow.openHostWindow()} 已经在后台线程里调它。</p>
 */
final class HostWindow {

    private HostWindow() {
    }

    /**
     * 唤起宿主主窗。
     *
     * <p>整条链路包在 try/catch 里：唤起失败绝不能影响播放，最多是用户觉得"点了没反应"，
     * 日志里有痕迹可查。</p>
     *
     * @param selfHwnd 迷你条自己的 HWND，用来把它从候选里排除
     * @return 是否真的执行了（不代表一定抢到了前台）
     */
    static boolean activate(long selfHwnd) {
        try {
            long hwnd = WindowLayer.findMainWindowHwnd(selfHwnd);
            if (hwnd == 0L) {
                MiniPlayerPlugin.log("host.activate: main window not found");
                return false;
            }
            return WindowLayer.restoreAndActivate(hwnd);
        } catch (Throwable t) {
            MiniPlayerPlugin.log("host.activate failed: " + t);
            return false;
        }
    }
}
