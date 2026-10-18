package com.thermo.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize

/**
 * 播放器向上层抛的"一次性事件"。
 *
 * 为什么状态用 StateFlow、事件用 SharedFlow：
 * 状态是"当前是什么"（可以随时重放，UI 重组的任何时候都要能拿到正确的值），
 * 事件是"刚刚发生了什么"（只应该被处理一次）。
 * 把 FirstFrameRendered 塞进 state 会导致旋转屏幕时又触发一遍"隐藏 loading"的动画。
 */
sealed interface ThermoPlayerEvent {

    /** 首帧已上屏。UI 用它来隐藏封面 / 撤掉 loading。 */
    data class FirstFrameRendered(val renderTimeMs: Long) : ThermoPlayerEvent

    /**
     * 实际启用的视频解码器已确定。
     *
     * @param decoderName  MediaCodec 的组件名，如 "c2.qti.avc.decoder"
     * @param software     是否是软件解码。true 时上层应当立刻收紧画质策略 ——
     *                     软解意味着 CPU 在跑解码，是"发热"的主要来源
     */
    data class VideoDecoderSelected(
        val decoderName: String,
        val mimeType: String,
        val software: Boolean,
        val initDurationMs: Long,
    ) : ThermoPlayerEvent

    /** 视频轨输入格式变化（自适应切档 / 首次解出）。 */
    data class VideoFormatChanged(
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val mimeType: String?,
    ) : ThermoPlayerEvent

    /** 渲染尺寸变化（含旋转 / 宽高比）。 */
    data class VideoSizeChanged(val size: VideoSize) : ThermoPlayerEvent

    /** 丢帧。丢帧多 = 解码器跟不上 = 大概率正在发烫。 */
    data class FramesDropped(val droppedCount: Int, val elapsedMs: Long) : ThermoPlayerEvent

    /** 硬件解码路径出错，已经或即将发生降级。:decoder 模块监听它来决定是否切 FFmpeg。 */
    data class DecodeError(val error: PlaybackException) : ThermoPlayerEvent

    /** 播放自然结束。 */
    data object PlaybackEnded : ThermoPlayerEvent
}