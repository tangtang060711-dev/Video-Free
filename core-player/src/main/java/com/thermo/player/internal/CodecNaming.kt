package com.thermo.player.internal

/**
 * 从 MediaCodec 组件名判断是不是软解。
 *
 * ## 为什么不用 MediaCodecInfo.softwareOnly
 * 那个字段是有的（:core-player 的 HardwareFirstMediaCodecSelector 就在用），
 * 但**选择阶段拿到的 MediaCodecInfo** 和**运行阶段 AnalyticsListener 报出来的名字**
 * 是两条独立通道：后者只给一个 String。播放中途要判断"现在是不是在软解"，
 * 只能靠名字反推。
 *
 * ## 命名规律（覆盖到 Android 16 的主流实现）
 * | 前缀 | 归属 | 性质 |
 * |---|---|---|
 * | `OMX.google.*`      | AOSP 自带（libavc/libhevc/libvpx） | 软件 |
 * | `c2.android.*`      | 同上的 C2 版本                     | 软件 |
 * | `c2.qti.*`          | 高通骁龙（8 Elite 上就是它）        | 硬件 |
 * | `OMX.qcom.*`        | 高通旧版 OMX                       | 硬件 |
 * | `c2.mtk.*` / `OMX.MTK.*` | 联发科                        | 硬件 |
 * | `c2.exynos.*` / `OMX.Exynos.*` | 三星                    | 硬件 |
 * | `c2.hisi.*`         | 海思                               | 硬件 |
 * | `c2.ffmpeg.*`       | 我们自己的 :ffmpeg 扩展            | 软件（CPU 解码） |
 *
 * 注意 `OMX.ffmpeg.*` 也要算软件 —— 有些第三方 AAR 会注册这个名字。
 */
internal object CodecNaming {

    private val SOFTWARE_PREFIXES = arrayOf(
        "OMX.google.",
        "OMX.ffmpeg.",
        "c2.android.",
        "c2.ffmpeg.",
    )

    /** 这些是"名字里带但其实是硬件"的厂商前缀，先判它们，避免误伤。 */
    private val HARDWARE_HINTS = arrayOf(
        "c2.qti.", "OMX.qcom.",
        "c2.mtk.", "OMX.MTK.",
        "c2.exynos.", "OMX.Exynos.",
        "c2.hisi.", "OMX.hisi.",
        "c2.intel.", "OMX.Intel.",
        "c2.amlogic.", "c2.nvidia.",
        "c2.sprd.", "c2.unisoc.",
    )

    fun isSoftware(decoderName: String?): Boolean {
        if (decoderName.isNullOrEmpty()) return false
        if (HARDWARE_HINTS.any { decoderName.startsWith(it) }) return false
        if (SOFTWARE_PREFIXES.any { decoderName.startsWith(it) }) return true
        // 兜底：名字里带 ".sw." / ".software." 的一律按软解处理。
        // 宁可误判成软解（策略收紧一点），也不要漏判（放任 CPU 烤机）。
        val lower = decoderName.lowercase()
        return lower.contains(".sw.") || lower.contains(".software.")
    }
}