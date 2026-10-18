package com.thermo.player.internal

import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.thermo.player.QualityProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 [QualityProfile] 翻译成 Media3 的 TrackSelectionParameters。
 *
 * ## 三个"必须做对否则会出事故"的点
 *
 * 1) **`setExceedVideoConstraintsIfNecessary(true)`**
 *    这一句是画质降级的保险丝。它的意思是"如果所有轨道都超出约束，那就允许超"。
 *    如果设成 false，一旦我们把 maxHeight 限到 480p、而片源只有 1080p 一条轨道，
 *    DefaultTrackSelector 会**一条都不选** → 用户看到的是纯黑屏 + 有声音。
 *    限画质是为了省电，不是为了看黑屏。
 *
 * 2) **`setAllowVideoNonSeamlessAdaptiveness`**
 *    这个开关控制"允不允许选中需要重建解码器才能切过去的轨道"。
 *    关掉（false）= 自适应切换必须无缝 = 不会重建 MediaCodec。
 *    对发热敏感的场景，MediaCodec 的重建（release + create + configure + start）
 *    是一次几十到几百毫秒的持续高功耗操作，代价远大于"少切一档清晰度"。
 *    所以降到 HOT 以下时我们把它关掉。
 *
 * 3) **不做"软解就不要隧道解码"这类自作聪明的判断**
 *    隧道模式（tunneling）在部分 SoC 上确实更省电（解码器直出显示控制器，
 *    绕过 SurfaceFlinger 合成），但它在 Media3 里对字幕、倍速、seek 的行为都有影响，
 *    而且不同厂商实现质量参差。默认关掉，把它做成显式开关交给上层。
 */
@Singleton
class TrackSelectionFactory @Inject constructor() {

    fun build(
        profile: QualityProfile,
        tunnelingEnabled: Boolean = false,
    ): DefaultTrackSelector.Parameters {
        // 用无参构造：带 Context 的那个重载在 1.9.x 已废弃
        // （它只是为了给 setViewportSizeToPhysicalDisplaySize 取屏幕尺寸，而我们从不用那条路径）。
        val builder = DefaultTrackSelector.Parameters.Builder()

        // ---- 视频 ----
        if (profile.maxWidth > 0 || profile.maxHeight > 0) {
            // Media3 要求宽高都 > 0，只给了一边时另一边用 Int.MAX_VALUE 兜住。
            builder.setMaxVideoSize(
                if (profile.maxWidth > 0) profile.maxWidth else Int.MAX_VALUE,
                if (profile.maxHeight > 0) profile.maxHeight else Int.MAX_VALUE,
            )
        } else {
            builder.clearVideoSizeConstraints()
        }
        if (profile.maxBitrate > 0) {
            // 理论上限设为无穷 + 允许超约束：避免"码率约束把所有轨道都排除掉"。
            builder.setMaxVideoBitrate(profile.maxBitrate)
            builder.setMinVideoBitrate(0)
        }
        if (profile.maxFrameRate > 0) {
            builder.setMaxVideoFrameRate(profile.maxFrameRate)
        } else {
            builder.setMaxVideoFrameRate(Int.MAX_VALUE)
        }
        // 见上面第 1 点：宁可超约束，也不能黑屏。
        builder.setExceedVideoConstraintsIfNecessary(true)
        // 允许跨 mimeType（H264 <-> H265）自适应：切清晰度时不必重新协商容器。
        builder.setAllowVideoMixedMimeTypeAdaptiveness(true)
        // 见上面第 2 点：是否允许重建解码器。
        builder.setAllowVideoNonSeamlessAdaptiveness(profile.allowNonSeamless)
        // 不允许"因为解码器支持差异"而切轨 —— 那等于把选轨权交给一个我们控制不了的启发式。
        builder.setAllowVideoMixedDecoderSupportAdaptiveness(false)
        builder.setTunnelingEnabled(tunnelingEnabled)

        // ---- 音频 ----
        // 立体声足够：多声道（5.1/7.1）在手机上要做下混，
        // 下混本身是 CPU 活，而下混完再送双扬声器纯属白干。
        builder.setMaxAudioChannelCount(2)
        builder.setConstrainAudioChannelCountToDeviceCapabilities(true)
        builder.setExceedAudioConstraintsIfNecessary(true)
        builder.setAllowAudioMixedMimeTypeAdaptiveness(true)
        builder.setAllowAudioMixedSampleRateAdaptiveness(true)
        // 声道数不允许自适应：从 2.0 切到 5.1 会重建音频输出，要重新起 AudioTrack。
        builder.setAllowAudioMixedChannelCountAdaptiveness(false)

        // ---- 字幕 ----
        // 默认不自动选中字幕，交给用户在 :ui 里显式开；自动选中会在
        // 每次选轨变化时多解一路文本轨道，白费一点 CPU。
        builder.setSelectTextByDefault(false)
        builder.setPreferredTextLanguage("zh")

        return builder.build()
    }
}