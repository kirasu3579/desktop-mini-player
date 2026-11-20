package com.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State;

/**
 * 迷你播放器的一份不可变播放快照，供绘制线程安全读取。
 */
public final class PlayerState {

    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final long positionMs;
    public final boolean playing;
    public final boolean buffering;
    public final boolean favorite;
    public final int volume;
    public final boolean muted;

    public PlayerState(String title, String artist, String album, long durationMs,
                       long positionMs, boolean playing, boolean buffering,
                       boolean favorite, int volume, boolean muted) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.positionMs = positionMs;
        this.playing = playing;
        this.buffering = buffering;
        this.favorite = favorite;
        this.volume = volume;
        this.muted = muted;
    }

    public static PlayerState empty() {
        return new PlayerState("", "", "", 0L, 0L, false, false, false, 100, false);
    }

    public PlayerState with(MediaItem item) {
        if (item == null) {
            return empty();
        }
        return new PlayerState(
                safe(item.getTitle(), "未知歌曲"),
                safe(item.getArtist(), "未知歌手"),
                safe(item.getAlbum(), ""),
                item.getDuration(),
                positionMs,
                playing,
                buffering,
                item.isFavorite(),
                volume,
                muted);
    }

    public PlayerState withPosition(long pos) {
        return new PlayerState(title, artist, album, durationMs, Math.max(0L, pos),
                playing, buffering, favorite, volume, muted);
    }

    public PlayerState withPlaying(boolean value) {
        return new PlayerState(title, artist, album, durationMs, positionMs,
                value, buffering, favorite, volume, muted);
    }

    public PlayerState withState(State state) {
        if (state == null) {
            return this;
        }
        boolean bufferingNow = state == State.Buffering;
        // 播放结束/空闲时按钮回到"播放"形态，等待用户再次点按
        boolean playingNow = switch (state) {
            case Ended, Idle -> false;
            case Buffering, Ready -> playing;
        };
        return new PlayerState(title, artist, album, durationMs, positionMs,
                playingNow, bufferingNow, favorite, volume, muted);
    }

    public PlayerState withFavorite(boolean value) {
        return new PlayerState(title, artist, album, durationMs, positionMs,
                playing, buffering, value, volume, muted);
    }

    public PlayerState withVolume(int value, boolean isMuted) {
        return new PlayerState(title, artist, album, durationMs, positionMs,
                playing, buffering, favorite, value, isMuted);
    }

    /** 0.0–1.0 的播放进度。 */
    public double progress() {
        if (durationMs <= 0L) {
            return 0.0;
        }
        double p = (double) positionMs / (double) durationMs;
        return Math.max(0.0, Math.min(1.0, p));
    }

    public boolean hasTrack() {
        return !title.isEmpty();
    }

    public String timeText() {
        return format(positionMs) + " / " + format(durationMs);
    }

    public static String format(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
