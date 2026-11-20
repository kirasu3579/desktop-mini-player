package com.miniplayer;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置面板契约探针：验证 {@code preference_config.json} 与插件代码对得上。
 *
 * <p>为什么值得单独测：这张 JSON 是插件与宿主设置界面之间唯一的声明式契约，
 * 但它<b>没有编译期检查</b>——写错一个键名、删掉一个 {@code on_click} 指向的方法名、
 * 或者把按钮改成非静态方法，都只会在用户在宿主里点下去的那一刻才炸，而且宿主侧
 * 只会静默失败。所以这里把它当接口来测。</p>
 *
 * <p>另外三条是"防回流"断言：托盘图标、USB 独占、全局快捷键都是被明确删掉的功能，
 * 一旦有人从旧版本拷回文案，这里会立刻红。</p>
 */
public final class ConfigProbe {

    /** 面板里必须存在的开关键；顺序无关，但集合必须精确相等。 */
    private static final Set<String> EXPECTED_KEYS = new LinkedHashSet<>(List.of(
            "enabled",
            "hideWhenPaused",
            "themeMode",
            "locked",
            "autoHideControls",
            "opacity"));

    /** 已废弃的文案与标识：出现在面板里就说明是旧版本残留。 */
    private static final String[] FORBIDDEN = {
            "USB 独占",
            "托盘",
            "Ctrl+Alt+M",
            "key-bindings",
    };

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: config probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        String text = readResource();
        System.out.println("preference_config.json " + text.length() + " chars");

        // 1) 开关键集合必须精确相等：既防止误删（用户的设置会读不到），也防止误加（面板上
        //    多出一个后端根本不认的开关比没有更糟——用户改了发现没反应）。
        Set<String> keys = new LinkedHashSet<>(grep(text, "\"key\"\\s*:\\s*\"([^\"]+)\""));
        System.out.println("keys = " + keys);
        check("开关键集合与实现一致", keys.equals(EXPECTED_KEYS));

        // 2) 每个 on_click 必须指向"公开静态无参方法"——宿主的硬要求，反射验证
        List<String> hooks = grep(text, "\"on_click\"\\s*:\\s*\"([^\"]+)\"");
        System.out.println("on_click = " + hooks);
        check("至少有三个面板按钮入口", hooks.size() >= 3);
        for (String hook : hooks) {
            checkHook(hook);
        }

        // 3) 防回流：删掉的功能不能从旧文案里爬回来
        for (String bad : FORBIDDEN) {
            check("面板文案不含「" + bad + "」", !text.contains(bad));
        }

        // 4) 「启用迷你播放器」现在是即时生效的开关，文案必须说清这一点；
        //    否则用户会以为还得重启宿主（旧行为）。
        String enabledSummary = summaryFor(text, "enabled");
        System.out.println("enabled.summary = " + enabledSummary);
        check("「启用迷你播放器」说明里写明立刻生效", enabledSummary.contains("立刻"));
        check("「启用迷你播放器」说明里不再提「下次启动」", !enabledSummary.contains("下次启动"));
    }

    private static String readResource() throws Exception {
        try (InputStream in = ConfigProbe.class.getResourceAsStream("/preference_config.json")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        // 探针的 classpath 里只有 build/classes（资源由 build.sh 在打包阶段才并进去），
        // 所以再回退到源码目录读同一份文件——它是打进 spmod 的唯一来源，等价。
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/resources/preference_config.json");
        if (!java.nio.file.Files.exists(p)) {
            throw new IllegalStateException("preference_config.json not found: " + p.toAbsolutePath());
        }
        return new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 取某个 key 所在对象里的 summary 字段（JSON 结构固定，紧邻即可）。 */
    private static String summaryFor(String text, String key) {
        int at = text.indexOf("\"key\": \"" + key + "\"");
        if (at < 0) {
            at = text.indexOf("\"key\":\"" + key + "\"");
        }
        if (at < 0) {
            return "";
        }
        int end = text.indexOf('}', at);
        String block = end < 0 ? text.substring(at) : text.substring(at, end);
        List<String> found = grep(block, "\"summary\"\\s*:\\s*\"([^\"]*)\"");
        return found.isEmpty() ? "" : found.get(0);
    }

    private static void checkHook(String hook) {
        int dot = hook.lastIndexOf('.');
        check("on_click 是类名.方法名：" + hook, dot > 0);
        if (dot <= 0) {
            return;
        }
        String className = hook.substring(0, dot);
        String methodName = hook.substring(dot + 1);
        try {
            Class<?> cls = Class.forName(className);
            Method m = cls.getDeclaredMethod(methodName);
            boolean ok = Modifier.isStatic(m.getModifiers())
                    && Modifier.isPublic(m.getModifiers())
                    && m.getParameterCount() == 0;
            check("入口可用（public static 无参）：" + hook, ok);
        } catch (Throwable t) {
            check("入口可用（public static 无参）：" + hook + " —— " + t, false);
        }
    }

    private static List<String> grep(String text, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
