package com.thermo.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * 播放器状态快照。
 *
 * ## 为什么不直接把 ExoPlayer 的状态流出去
 * 一是 ExoPlayer 的接口有 90+ 个方法，UI 层只用到十来个，
 * 直接暴露会让 UI 有机会调用"看似无害但会打断解码"的方法（比如到处 setPlaybackSpeed）；
 * 二是我们需要在里面塞一些 ExoPlayer 自己不知道的东西 —— 当前解码器是不是软解、
 * 当前生效的画质约束是什么。这些是"引擎的策略状态"，不是"播放器的播放状态"。
 *
 * 所有字段都有默认值，保证可以 `PlayerSnapshot()` 直接构造初值。
 */
data class PlayerSnapshot(
    /** [Player.STATE_IDLE] / [Player.STATE_BUFFERING] / [Player.STATE_READY] / [Player.STATE_ENDED] */
    val playbackState: Int = Player.STATE_IDLE,

    /** 是否在"正在播放"（已起播且没有暂停、没有缓冲） */
    val isPlaying: Boolean = false,

    /** 是否正在缓冲。UI 的转圈图标绑这个，而不是绑 playbackState。 */
    val isBuffering: Boolean = false,

    /** 当前播放位置（毫秒） */
    val positionMs: Long = 0L,

    /** 总时长（毫秒）。直播是 [C.TIME_UNSET]（-9223372036854775807）。 */
    val durationMs: Long = C.TIME_UNSET,

    /** 已缓冲到的位置（毫秒），进度条第二段用 */
    val bufferedPositionMs: Long = 0L,

    /** 已缓冲时长（毫秒），"缓冲健康度"用 */
    val totalBufferedMs: Long = 0L,

    /** 视频尺寸 */
    val videoSize: VideoSize = VideoSize.UNKNOWN,

    /** 视频帧率，0 表示未知。显示刷新率适配要用它。 */
    val videoFrameRate: Float = 0f,

    /** 当前倍速 */
    val playbackSpeed: Float = 1f,

    /** 当前生效的画质约束 */
    val qualityProfile: QualityProfile = QualityProfile.UNRESTRICTED,

    /** 实际在用的视频解码器名，未起播时为 null */
    val videoDecoderName: String? = null,

    /** 当前视频解码是否是软解。发热策略要看这个。 */
    val isSoftwareDecoding: Boolean = false,

    /** 播放错误，无错为 null */
    val error: PlaybackException? = null,
) {
    /** 时长是否可信（直播 / 还没解出时长时为 false）。 */
    val hasKnownDuration: Boolean
        get() = durationMs != C.TIME_UNSET && durationMs > 0L

    /** 进度 0f..1f，时长未知时返回 0。 */
    val progress: Float
        get() = if (hasKnownDuration) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** 缓冲进度 0f..1f。 */
    val bufferedProgress: Float
        get() = if (hasKnownDuration) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** 缓冲是否够撑过接下来 3 秒 —— 诊断面板用来提早发现"要开始烫了"。 */
    val bufferedAheadMs: Long
        get() = (bufferedPositionMs - positionMs).coerceAtLeast(0L)
}