package com.miniplayer;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 桌面迷你播放条插件入口。
 *
 * <p>宿主通过 {@code Plugin-Class} 清单项反射实例化本类，构造器签名必须保留
 * {@link PluginContext} 单参形式。
 *
 * <p>插件生命周期回调里的任何异常都会向上冒泡到宿主，可能导致整个应用退出，
 * 因此这里全部兜底：失败只记日志，不打断宿主。
 */
public final class MiniPlayerPlugin extends SpwPlugin {

    private volatile MiniPlayerWindow window;

    private static final Object LOG_LOCK = new Object();
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long LOG_MAX_BYTES = 512L * 1024L;

    public MiniPlayerPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        try {
            log("start, spw=" + getPluginContext().getSpwVersion()
                    + ", channel=" + getPluginContext().getSpwChannel());
            // 监听先装：即使配置里 enabled=false（用户上次把悬浮条关了），
            // 也得留一个入口能把它再叫回来——入口就是设置面板里的
            // 「启用迷你播放器」开关，改完即时生效，不用重启宿主。
            PluginConfig.installChangeListener();
            PluginConfig config = PluginConfig.load();
            log("config path = " + config.path());
            if (!config.enabled) {
                log("plugin disabled by config, skip showing window (settings switch still works)");
                return;
            }
            // 同步建窗：返回值就是实例，不能再靠 instance() 二次读取（那是竞态的根源）
            MiniPlayerWindow created = MiniPlayerWindow.ensureCreated(config);
            window = created;
            if (created != null) {
                created.showWindow();
                log("window shown");
            } else {
                log("window not created (build window failed, plugin stays idle)");
            }
        } catch (Throwable t) {
            // 建窗失败只跳过，绝不重试，避免拖死宿主启动流程
            log("start failed: " + describe(t));
        }
    }

    @Override
    public void stop() {
        try {
            log("stop");
            PluginConfig.uninstallChangeListener();
            // 窗口未必是本类持有：start() 时配置里 enabled=false 不会建窗，
            // 之后用户在设置面板里打开开关，是 applyConfig 按需建的，
            // 那时 this.window 仍是 null。所以这里退回单例再取一次，
            // 否则停用插件后悬浮条会残留在桌面上。
            MiniPlayerWindow current = window != null ? window : MiniPlayerWindow.peek();
            window = null;
            if (current != null) {
                current.disposeWindow();
            }
            MiniPlayerWindow.reset();
        } catch (Throwable t) {
            log("stop failed: " + describe(t));
        }
    }

    @Override
    public void update() {
        try {
            // 插件热更新：重建窗口，保持行为一致
            stop();
            start();
        } catch (Throwable t) {
            log("update failed: " + describe(t));
        }
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] trace = t.getStackTrace();
        for (int i = 0; i < Math.min(6, trace.length); i++) {
            sb.append("\n    at ").append(trace[i]);
        }
        return sb.toString();
    }

    /**
     * 诊断日志：同时打到 stdout 与临时目录文件。
     *
     * <p>宿主以 GUI 方式启动时 stdout 通常不可见，出问题时拿不到任何线索，
     * 所以额外落盘到 {@code java.io.tmpdir/mini-player.log}。写盘失败只忽略，
     * 绝不能因为日志把插件拖垮。</p>
     */
    static void log(String msg) {
        System.out.println("[MiniPlayer] " + msg);
        try {
            String line = LocalDateTime.now().format(TIME_FORMAT) + " " + msg + System.lineSeparator();
            synchronized (LOG_LOCK) {
                Path path = logPath();
                // 文件不存在时 Files.size 会抛异常，必须先判存在，否则首次写入永远失败
                if (Files.exists(path) && Files.size(path) > LOG_MAX_BYTES) {
                    Files.write(path, new byte[0]);
                }
                Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响插件功能
        }
    }

    static Path logPath() {
        return Paths.get(System.getProperty("java.io.tmpdir", "."), "mini-player.log");
    }
}
