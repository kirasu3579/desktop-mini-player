package com.miniplayer;

import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.nio.file.Path;

/**
 * 迷你播放器的持久化配置（窗口位置、置顶、主题、锁定、自动隐藏等）。
 *
 * <p>宿主 API 的 {@code ConfigManager} 属于 Unstable 扩展，且读写失败不应影响播放器可用性，
 * 因此这里把全部读写包在 try/catch 里：读失败就用默认值，写失败就丢掉。
 */
public final class PluginConfig {

    private static final String KEY_ENABLED = "enabled";
    /**
     * 旧键，<b>只写不读</b>。
     *
     * <p>语义已经从"是否置顶"改成"是否盖住全屏"：旧值是 {@code true} 的用户
     * 抱怨的正是"勾了之后全屏窗口还盖不住它"，直接沿用旧值会把 bug 带过来，
     * 所以 {@link #load()} 刻意不读它，只在 {@link #save()} 里回写一份给旧版本降级用。</p>
     */
    private static final String KEY_ALWAYS_ON_TOP = "alwaysOnTop";
    private static final String KEY_THEME_MODE = "themeMode";
    private static final String KEY_DARK_THEME = "darkTheme";
    private static final String KEY_FOLLOW_SYSTEM = "followSystemTheme";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_X = "windowX";
    private static final String KEY_Y = "windowY";
    private static final String KEY_LOCKED = "locked";
    private static final String KEY_SNAP = "snapEdges";
    private static final String KEY_AUTO_HIDE = "autoHideControls";
    /** 旧键：1.0.33 起歌词态固定显示播控，只写不读。 */
    private static final String KEY_SHOW_BUTTONS = "showControlsInLyric";
    private static final String KEY_HIDE_WHEN_PAUSED = "hideWhenPaused";
    private static final String KEY_DOCK = "dockToTaskbar";
    private static final String KEY_DOCKED = "docked";

    /** 配色模式取值，与 preference_config.json 的 entry_values 一致。 */
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    /** 是否在宿主启动时显示迷你播放器。 */
    public boolean enabled = true;
    /** 配色模式：system / light / dark，默认跟随系统。 */
    public String themeMode = MODE_SYSTEM;
    /** 窗口不透明度 0.6~1.0。 */
    public float opacity = 1.0f;
    /** 上次窗口坐标；-1 表示未记录。 */
    public int windowX = -1;
    public int windowY = -1;
    /** 锁定后不可拖动，右键菜单也不弹。 */
    public boolean locked = false;
    /** 鼠标离开后按钮变暗。 */
    public boolean autoHideControls = true;
    /**
     * 暂停时整条隐藏，恢复播放自动显示。
     *
     * <p>用户原话："增加一个可选选项，播放时显示、未播放时隐藏。"边界经确认为
     * <b>只有暂停时隐藏</b>：播放中、缓冲、播完、没有曲目都不隐藏——
     * 缓冲时藏起来会显得插件崩了，播完藏起来又让人找不到条子。</p>
     */
    public boolean hideWhenPaused = false;
    /** 上次退出时是否处于停靠态。 */
    public boolean docked = false;

    private ConfigHelper helper;
    private boolean dirty;
    /** 系统是否深色；首次使用时探测一次并缓存（探测要起子进程，绝不能每帧调用）。 */
    private Boolean systemDark;

    /**
     * 缓存的配置管理器与 helper。
     *
     * <p>必须缓存：宿主的 {@code ConfigManager} 按文件名缓存实例，重复
     * {@code createConfigManager()} 拿到的是同一个对象；但要注销
     * {@code addConfigChangeListener} 就得留住引用，否则监听器无法移除。</p>
     */
    private static volatile ConfigManager manager;
    private static volatile ConfigHelper sharedHelper;
    /** 当前注册的变更监听器；非 null 表示已注册（幂等判据）。 */
    private static volatile java.util.function.Consumer<ConfigHelper> listener;

    /** 当前是否应使用深色配色。 */
    public boolean isDark() {
        if (MODE_DARK.equals(themeMode)) {
            return true;
        }
        if (MODE_LIGHT.equals(themeMode)) {
            return false;
        }
        if (systemDark == null) {
            systemDark = Boolean.valueOf(systemPrefersDark());
        }
        return systemDark.booleanValue();
    }

    /** 切换明暗；跟随系统时按当前系统外观反转为显式模式，便于右键菜单一键生效。 */
    public void toggleDark() {
        themeMode = isDark() ? MODE_LIGHT : MODE_DARK;
        markDirty();
    }

    /**
     * 判断系统是否偏好深色。
     *
     * <p>没有可靠的跨平台 API：Windows 走注册表读取 AppsUseLightTheme，读取失败按浅色处理。
     */
    private static boolean systemPrefersDark() {
        try {
            String value = System.getenv("SPW_MINI_FORCE_DARK");
            if (value != null) {
                return !"false".equalsIgnoreCase(value);
            }
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            // 值形如 "    AppsUseLightTheme    REG_DWORD    0x0"，0x0 表示深色
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").matcher(out);
            if (m.find()) {
                return "0".equals(m.group(1));
            }
        } catch (Throwable t) {
            // 读不到就按浅色，避免外观判断失败影响启动
        }
        return false;
    }

    /** 读取配置；任何异常都退回默认值。 */
    public static PluginConfig load() {
        PluginConfig cfg = new PluginConfig();
        ConfigHelper h = helper();
        if (h == null) {
            return cfg;
        }
        cfg.helper = h;
        try {
            cfg.enabled = bool(h, KEY_ENABLED, cfg.enabled);
            cfg.locked = bool(h, KEY_LOCKED, cfg.locked);
            cfg.autoHideControls = bool(h, KEY_AUTO_HIDE, cfg.autoHideControls);
            cfg.hideWhenPaused = bool(h, KEY_HIDE_WHEN_PAUSED, cfg.hideWhenPaused);
            cfg.docked = bool(h, KEY_DOCKED, cfg.docked);
            cfg.windowX = intValue(h, KEY_X, cfg.windowX);
            cfg.windowY = intValue(h, KEY_Y, cfg.windowY);
            cfg.opacity = floatValue(h, KEY_OPACITY, cfg.opacity);
            cfg.themeMode = themeMode(h, cfg.themeMode);
        } catch (Throwable t) {
            MiniPlayerPlugin.log("config load failed: " + t);
        }
        return cfg;
    }

    /** 读取配色模式；老版本只存了 darkTheme/followSystemTheme，这里做一次兼容迁移。 */
    private static String themeMode(ConfigHelper h, String fallback) {
        try {
            Object v = h.get(KEY_THEME_MODE, fallback);
            if (v instanceof String) {
                String s = (String) v;
                if (MODE_SYSTEM.equals(s) || MODE_LIGHT.equals(s) || MODE_DARK.equals(s)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 继续尝试旧键
        }
        boolean follow = bool(h, KEY_FOLLOW_SYSTEM, true);
        if (follow) {
            return MODE_SYSTEM;
        }
        return bool(h, KEY_DARK_THEME, false) ? MODE_DARK : MODE_LIGHT;
    }

    /** 立即写盘；失败静默。 */
    public void save() {
        ConfigHelper h = helper;
        if (h == null) {
            return;
        }
        try {
            h.set(KEY_ENABLED, enabled);
            // 层级固定为"常态压住任务栏、全屏时让位"，旧键只写常量 false 给降级版本用
            h.set(KEY_ALWAYS_ON_TOP, false);
            h.set(KEY_THEME_MODE, themeMode);
            // 同步写回旧键，保证降级到旧版本宿主时外观不跳变
            h.set(KEY_FOLLOW_SYSTEM, MODE_SYSTEM.equals(themeMode));
            h.set(KEY_DARK_THEME, MODE_DARK.equals(themeMode));
            h.set(KEY_OPACITY, (double) opacity);
            h.set(KEY_LOCKED, locked);
            // 吸附与停靠已固定开启、不再提供开关，仍写 true 让降级版本行为一致
            h.set(KEY_SNAP, true);
            h.set(KEY_AUTO_HIDE, autoHideControls);
            // 歌词态固定显示播控三键，仍写 true 让降级版本行为一致
            h.set(KEY_SHOW_BUTTONS, true);
            h.set(KEY_HIDE_WHEN_PAUSED, hideWhenPaused);
            h.set(KEY_DOCK, true);
            h.set(KEY_DOCKED, docked);
            h.set(KEY_X, windowX);
            h.set(KEY_Y, windowY);
            h.save();
            dirty = false;
        } catch (Throwable t) {
            MiniPlayerPlugin.log("config save failed: " + t);
        }
    }

    /** 标记已变更，稍后由定时器合并写盘（避免拖动时每帧落盘）。 */
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** 配置文件的绝对路径，用于日志与排查；不可用时返回 null。 */
    public Path path() {
        try {
            return helper == null ? null : helper.getConfigPath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ConfigHelper helper() {
        try {
            ConfigHelper cached = sharedHelper;
            if (cached != null) {
                return cached;
            }
            ConfigManager m = WorkshopApi.manager().createConfigManager();
            if (m == null) {
                return null;
            }
            ConfigHelper h = m.getConfig();
            manager = m;
            sharedHelper = h;
            return h;
        } catch (Throwable t) {
            // 未授权、宿主版本过低或 Unstable API 未开放时都会走到这里
            return null;
        }
    }

    /**
     * 注册配置变更监听：设置面板里的开关改完即时作用到活着的窗口，不用重启宿主。
     *
     * <p>这是宿主的公开 API（{@code ConfigManager#addConfigChangeListener}），也是本插件
     * 与宿主本体界面之间唯一可用的双向通道——{@code WorkshopApi.Ui} 只有 {@code toast}，
     * 没有「显示 / 隐藏插件窗口」的入口；宿主托盘菜单内容写死在 {@code AppTray} 的私有
     * Composable 里，而 {@code HookRegistrar} 要求挂钩目标由插件的 {@code PluginClassLoader}
     * 加载（宿主类不满足，会抛 {@code Hook target must be visible to the host loader}），
     * 所以插不了宿主托盘菜单项。设置面板 + 配置监听是官方支持的等价路径。</p>
     *
     * <p>幂等：{@code update()} 会 stop + start 各走一遍，重复调用不会叠加监听。
     * 失败只记日志，窗口照常可用，只是改动要下次启动才生效。</p>
     */
    public static void installChangeListener() {
        if (listener != null) {
            return;
        }
        try {
            ConfigManager m = WorkshopApi.manager().createConfigManager();
            if (m == null) {
                MiniPlayerPlugin.log("config listener skipped: no config manager");
                return;
            }
            manager = m;
            ConfigHelper h = m.getConfig();
            if (h != null) {
                sharedHelper = h;
            }
            // 回调不保证在 UI 线程，也不保证配置文件已经写完；
            // 这里只负责重读 + 交给 MiniPlayerWindow 在 EDT 上应用（applyConfig 内部投递）。
            java.util.function.Consumer<ConfigHelper> l = changed -> {
                try {
                    if (changed != null) {
                        try {
                            changed.reload();
                        } catch (Throwable ignored) {
                            // 文件正被写入时 reload 可能失败，随后 get() 仍能取到旧值
                        }
                    }
                    MiniPlayerWindow.applyConfig(load());
                } catch (Throwable t) {
                    MiniPlayerPlugin.log("config change failed: " + MiniPlayerPlugin.describe(t));
                }
            };
            m.addConfigChangeListener(l);
            listener = l;
            MiniPlayerPlugin.log("config listener installed");
        } catch (Throwable t) {
            MiniPlayerPlugin.log("config listener failed: " + MiniPlayerPlugin.describe(t));
        }
    }

    /** 注销配置变更监听；插件停用 / 升级前调用，避免监听器泄漏与重复注册。 */
    public static void uninstallChangeListener() {
        java.util.function.Consumer<ConfigHelper> l = listener;
        if (l == null) {
            return;
        }
        listener = null;
        try {
            ConfigManager m = manager;
            if (m != null) {
                m.removeConfigChangeListener(l);
            }
            MiniPlayerPlugin.log("config listener removed");
        } catch (Throwable t) {
            MiniPlayerPlugin.log("config listener remove failed: " + t);
        }
    }

    /**
     * 配置界面「重置窗口位置」按钮的入口（{@code on_click} 要求公开静态无参方法）。
     *
     * <p>清掉记录坐标并让正在显示的窗口回到主屏中央；任何异常都只提示，不抛出。
     */
    public static void resetWindowPosition() {
        boolean ok = false;
        try {
            ConfigHelper h = helper();
            if (h != null) {
                h.set(KEY_X, -1);
                h.set(KEY_Y, -1);
                ok = h.save();
            }
        } catch (Throwable t) {
            MiniPlayerPlugin.log("resetWindowPosition failed: " + MiniPlayerPlugin.describe(t));
        }
        try {
            MiniPlayerWindow.recenter();
        } catch (Throwable t) {
            MiniPlayerPlugin.log("recenter failed: " + t);
        }
        toast(ok ? "迷你播放器位置已重置" : "位置已重置（配置未能写入磁盘）", ok);
    }

    /**
     * 配置界面「显示 / 隐藏迷你条」按钮的入口（{@code on_click} 要求公开静态无参方法）。
     *
     * <p>悬浮条右键菜单里点「隐藏」之后，唤回入口有三个：设置面板的「启用迷你播放器」
     * 开关（改完即时生效）、这里的「显示 / 隐藏迷你条」按钮，以及右键菜单本身（隐藏前）。</p>
     */
    public static void toggleMiniPlayer() {
        try {
            // 切换动作是投递到 EDT 异步执行的，所以提示语按"将要变成什么状态"来定，
            // 不能切换完立刻去读 isShowingMini()——那一刻 EDT 可能还没跑到。
            MiniPlayerWindow w = MiniPlayerWindow.peek();
            boolean willShow = w == null || !w.isShowingMini();
            MiniPlayerWindow.toggleMini();
            toast(willShow ? "迷你播放器已显示" : "迷你播放器已隐藏", true);
        } catch (Throwable t) {
            MiniPlayerPlugin.log("toggleMiniPlayer failed: " + MiniPlayerPlugin.describe(t));
            toast("切换迷你播放器失败", false);
        }
    }

    /** 配置界面「显示当前设置」按钮的入口。 */
    public static void showCurrentSettings() {
        PluginConfig cfg = load();
        toast("配色=" + cfg.themeMode + " 不透明度="
                + Math.round(cfg.opacity * 100) + "% 锁定=" + cfg.locked
                + " 自动隐藏=" + cfg.autoHideControls
                + " 暂停时隐藏=" + cfg.hideWhenPaused, true);
    }

    private static void toast(String text, boolean success) {
        try {
            WorkshopApi.ui().toast(text, success
                    ? WorkshopApi.Ui.ToastType.Success
                    : WorkshopApi.Ui.ToastType.Warning);
        } catch (Throwable ignored) {
            // 宿主接口不可用时放弃提示
        }
    }

    private static boolean bool(ConfigHelper h, String key, boolean fallback) {
        try {
            Object v = h.get(key, Boolean.valueOf(fallback));
            return v instanceof Boolean ? (Boolean) v : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int intValue(ConfigHelper h, String key, int fallback) {
        try {
            Object v = h.get(key, Integer.valueOf(fallback));
            return v instanceof Number ? ((Number) v).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float floatValue(ConfigHelper h, String key, float fallback) {
        try {
            Object v = h.get(key, Float.valueOf(fallback));
            if (v instanceof Number) {
                return ((Number) v).floatValue();
            }
        } catch (Throwable ignored) {
            // 有些宿主把 seekbar 存成 Integer，退一步再试
        }
        try {
            Object v = h.get(key, null);
            if (v instanceof Number) {
                return ((Number) v).floatValue();
            }
        } catch (Throwable ignored) {
            // 放弃
        }
        return fallback;
    }
}
