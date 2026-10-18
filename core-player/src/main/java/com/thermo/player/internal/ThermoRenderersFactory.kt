package com.thermo.player.internal

import android.content.Context
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 构造播放器的 RenderersFactory。
 *
 * ============================ 关于 EXTENSION_RENDERER_MODE 的一点纠正 ============================
 * 原始需求里写的是 `EXTENSION_RENDERER_MODE_PREFER`。**这个值用在这里是错的**，必须纠正为 `ON`：
 *
 *  - `PREFER` 的语义是"只要扩展渲染器能解，就**优先**用它"，也就是 **FFmpeg 软解优先于 MediaCodec 硬解**。
 *    这跟需求里另一条硬性要求"视频默认 MediaCodec 硬解，仅当硬解不支持/报错才切 FFmpeg 软解"**正好相反**。
 *    真按 PREFER 配，结果就是所有能软解的片源都在软解，8 Elite 的大小核一起跑，比夸克还烫。
 *  - `ON` 的语义是"把扩展渲染器**挂在 MediaCodec 后面**作为兜底"，这正是我们要的。
 *  - `OFF` 则完全不用扩展，硬解失败就抛异常。
 *
 * 所以这里用 `ON`。这一处如果不改，前面所有低功耗设计都会被一个枚举值抵消掉。
 * ==============================================================================================
 */
@Singleton
class ThermoRenderersFactory @Inject constructor(
    private val codecSelector: HardwareFirstMediaCodecSelector,
) {

    /** 是否启用扩展渲染器（:ffmpeg）。默认 true。 */
    var extensionRendererEnabled: Boolean = true

    fun create(context: Context): RenderersFactory {
        val factory = DefaultRenderersFactory(context)

        // 硬解优先、FFmpeg 兜底。理由见类注释。
        factory.setExtensionRendererMode(
            if (extensionRendererEnabled) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            },
        )

        // 解码器回退：硬解在 configure / start 阶段抛异常时，Media3 会自动换下一个候选。
        // 这是"不弹错误框、用户无感降级"的关键开关，必须开。
        factory.setEnableDecoderFallback(true)

        // 用我们自己的选择器把软解压到队尾。
        factory.setMediaCodecSelector(codecSelector)

        // 视频拼接（video joining）：Media3 默认为"两段视频无缝拼接"多留 5 秒解码器占用，
        // 意味着可能同时活着两个 MediaCodec 实例。播放器场景根本不需要拼接，
        // 设 0 直接关掉，少一个解码器实例 = 少一路硬件功耗。
        factory.setAllowedVideoJoiningTimeMs(0L)

        // 音频浮点输出关掉：定点 PCM 直接喂 AudioTrack，省一道格式转换。
        factory.setEnableAudioFloatOutput(false)

        // 解码器预热（prewarming）关掉：它会在还没开始播的时候就把 MediaCodec 拉起来，
        // 属于"用功耗换起播速度"。我们要的是低发热，起播慢 100ms 可以接受。
        factory.experimentalSetEnableMediaCodecVideoRendererPrewarming(false)

        Log.i(TAG, "RenderersFactory: extensionMode=${if (extensionRendererEnabled) "ON" else "OFF"}, " +
            "decoderFallback=true, videoJoining=0ms")
        return factory
    }

    private companion object {
        const val TAG = "ThermoRenderers"
    }
}