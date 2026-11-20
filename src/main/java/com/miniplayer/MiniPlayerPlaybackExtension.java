package com.miniplayer;

import java.util.List;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State;

/**
 * 播放事件扩展点：把宿主推送的播放状态转发给悬浮窗口。
 *
 * <p>必须在 {@code META-INF/extensions.idx} 中登记，否则 PF4J 不会加载。
 */
public final class MiniPlayerPlaybackExtension implements PlaybackExtensionPoint {

    @Override
    public void onStateChanged(State state) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onStateChanged(state);
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onIsPlayingChanged(isPlaying);
        }
    }

    @Override
    public void onSeekTo(long position) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onPositionUpdated(position);
        }
    }

    @Override
    public void onPositionUpdated(long position) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onPositionUpdated(position);
        }
    }

    @Override
    public String onBeforeLoadLyrics(MediaItem mediaItem) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onMediaItemChanged(mediaItem);
        }
        return null;
    }

    @Override
    public String onAfterLoadLyrics(MediaItem mediaItem) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onMediaItemChanged(mediaItem);
        }
        return null;
    }

    /** 整首歌的歌词行加载完成（dev21+）。 */
    @Override
    public void onLyricsLinesUpdated(List<LyricsLine> lines) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onLyricsLinesUpdated(lines);
        }
    }

    /** 当前演唱到的那一行变了。 */
    @Override
    public void onLyricsLineUpdated(LyricsLine line) {
        MiniPlayerWindow w = MiniPlayerWindow.peek();
        if (w != null) {
            w.onLyricLineUpdated(line);
        }
    }
}
