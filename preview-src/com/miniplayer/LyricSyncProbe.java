package com.miniplayer;

import java.awt.GraphicsConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine;

/**
 * 歌词行同步探针：验证「画哪一行」由播放位置决定，而不是由宿主推送时机决定。
 *
 * <p>宿主的 {@code onLyricLineUpdated} 可能滞后几帧。如果盲信它，播放位置已经进了下一行
 * 时仍画上一行，进度就会算成 100%——整句突然全亮，等回调到了再跳回行首重扫，
 * 观感就是「一下全渲染、一下又一顿一顿」。</p>
 */
public final class LyricSyncProbe {

    private static int failures;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println(failures == 0 ? "PASS: lyric sync probe all green" : "FAIL: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        Constructor<MiniPlayerWindow> ctor =
                MiniPlayerWindow.class.getDeclaredConstructor(GraphicsConfiguration.class, PluginConfig.class);
        ctor.setAccessible(true);
        PluginConfig cfg = new PluginConfig();
        cfg.themeMode = PluginConfig.MODE_LIGHT;
        MiniPlayerWindow w = ctor.newInstance(null, cfg);

        List<LyricsLine> lines = List.of(
                line(0L, 2000L, "第一句"),
                line(2000L, 4000L, "第二句"),
                line(4000L, 6000L, "第三句"));

        set(w, "lyricsLines", lines);
        set(w, "lyricsLines", lines);
        Method currentOrMatched = MiniPlayerWindow.class.getDeclaredMethod("currentOrMatchedLyric");
        currentOrMatched.setAccessible(true);
        Method matchLyric = MiniPlayerWindow.class.getDeclaredMethod("matchLyric", long.class);
        matchLyric.setAccessible(true);

        // 1) 宿主已推到第 2 行、播放位置也在第 2 行：就用第 2 行
        set(w, "currentLyric", lines.get(1));
        setPosition(w, 2500L);
        check("位置与推送一致时用推送行", text(currentOrMatched.invoke(w)).equals("第二句"));

        // 2) 宿主滞后：仍推第 1 行，但位置已到第 2 行 —— 必须追上第 2 行，不能把第 1 行算成 100%
        set(w, "currentLyric", lines.get(0));
        setPosition(w, 2600L);
        check("宿主滞后时用位置匹配行", text(currentOrMatched.invoke(w)).equals("第二句"));

        // 3) 宿主提前推来第 3 行，位置还在第 2 行 —— 继续画第 2 行，让它亮满停留一会儿，
        //    等位置真正到第 3 行的 startTime 再换行（否则最后一个字刚亮完就跳行）
        set(w, "currentLyric", lines.get(2));
        setPosition(w, 2600L);
        check("宿主提前时仍画当前行", text(currentOrMatched.invoke(w)).equals("第二句"));

        // 3b) 位置到达第 3 行 startTime 后，才切到第 3 行
        setPosition(w, 4100L);
        check("位置到点后才换行", text(currentOrMatched.invoke(w)).equals("第三句"));

        // 3c) 宿主滞后（推送行还在第 1 行、位置已进第 2 行）仍要追上，不能把上一行算成 100%
        set(w, "currentLyric", lines.get(0));
        setPosition(w, 2600L);
        check("宿主滞后时用位置匹配行", text(currentOrMatched.invoke(w)).equals("第二句"));

        // 4) 没有推送时纯靠位置
        set(w, "currentLyric", null);
        setPosition(w, 5000L);
        check("无推送时靠位置匹配", text(currentOrMatched.invoke(w)).equals("第三句"));

        // 5) 二分匹配本身：位置在行首时刻应命中该行
        check("二分：1500 -> 第一句", text(matchLyric.invoke(w, 1500L)).equals("第一句"));
        check("二分：2000 -> 第二句", text(matchLyric.invoke(w, 2000L)).equals("第二句"));
        check("二分：9999 -> 第三句", text(matchLyric.invoke(w, 9999L)).equals("第三句"));
        check("二分：-1 -> null", matchLyric.invoke(w, -1L) == null);

        w.setVisible(false);
        w.dispose();
    }

    private static LyricsLine line(long start, long end, String text) {
        return new LyricsLine(start, end, Collections.emptyList(), text, "");
    }

    private static String text(Object line) {
        return line == null ? "null" : ((LyricsLine) line).getPureMainText();
    }

    private static void set(MiniPlayerWindow w, String field, Object value) throws Exception {
        Field f = MiniPlayerWindow.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(w, value);
    }

    private static void setPosition(MiniPlayerWindow w, long pos) throws Exception {
        Field f = MiniPlayerWindow.class.getDeclaredField("state");
        f.setAccessible(true);
        PlayerState st = (PlayerState) f.get(w);
        f.set(w, new PlayerState(st.title, st.artist, st.album, st.durationMs, pos,
                st.playing, st.buffering, st.favorite, st.volume, st.muted));
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
