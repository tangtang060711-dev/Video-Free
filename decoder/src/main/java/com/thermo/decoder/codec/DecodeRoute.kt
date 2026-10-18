package com.thermo.decoder.codec

/**
 * 这条片子最终会走哪条解码路径。
 *
 * 只有三档，刻意不把"硬解"再细分成"能无缝自适应/不能"——
 * 那是 [CodecProbeResult.exceedsCapabilities] 要表达的东西，
 * 混在一起会让调用方的 when 分支变成一个笛卡尔积。
 */
enum class DecodeRoute {
    /**
     * MediaCodec 硬件解码器。骁龙 8 Elite 上典型名字：
     * `c2.qti.avc.decoder` / `c2.qti.hevc.decoder` / `c2.qti.av1.decoder`。
     *
     * 这是唯一"低发热"的路径：硬解是专用电路，解码 4K 的功耗
     * 常常只有 CPU 软解的 1/5 ~ 1/10。
     */
    HARDWARE,

    /**
     * 平台自带的软件解码器，典型名字 `c2.android.avc.decoder` /
     * `c2.android.mpeg4.decoder` / `OMX.google.h264.decoder`。
     *
     * 它也是 MediaCodec，也能出到 Surface，但跑在 CPU 上。
     * 对 MPEG-4 Part 2（Xvid）这类没有硬解的编码，这是唯一能用的路径，
     * 而且**比 FFmpeg 软解更省电** —— 因为它是系统编的、经过了厂商调优，
     * 还省掉了一次 YUV 拷贝。所以 FFmpeg 是它之后的第三顺位。
     */
    SOFTWARE,

    /**
     * 连平台软件解码器都没有。在骁龙 8 Elite 上这基本等于
     * "编码是 RealVideo / VC-1 / 老的 DivX 3"之类的东西。
     * 这时唯一的出路是 FFmpeg，而 FFmpeg 建不起来（见 :ffmpeg 模块说明）时
     * 就只能明确告诉用户"这个格式播不了" ——
     * 说清楚播不了，比转圈三分钟后崩掉好。
     */
    UNSUPPORTED,
}

/**
 * 一次编解码器探测的完整结果。
 *
 * @param route               最终结论
 * @param mimeType            探测用的 MIME（video/avc、video/hevc、video/mp4v-es…）
 * @param decoderName         Media3 会优先选中的解码器名字
 * @param hardwareAccelerated 选中的那个是不是硬解。注意它可能和 [route] 不一致：
 *                            当硬解存在但 **超出能力**（比如 8K）时，
 *                            route 会是 SOFTWARE 而这里仍是 true
 * @param exceedsCapabilities 格式超出了设备能力（分辨率/帧率/码率超了）。
 *                            这一位是"要不要降画质"的直接依据 ——
 *                            超出能力时硬解会静默回退到软解，那是最烫的情况
 * @param profile / @param level 从 codecs 串里解出来的，仅用于诊断面板
 * @param candidateCount      系统里能处理这个 MIME 的解码器总数
 * @param note                给日志/诊断面板看的一句话结论
 */
data class CodecProbeResult(
    val route: DecodeRoute,
    val mimeType: String?,
    val decoderName: String? = null,
    val hardwareAccelerated: Boolean = false,
    val exceedsCapabilities: Boolean = false,
    val profile: Int = -1,
    val level: Int = -1,
    val candidateCount: Int = 0,
    val note: String = "",
    val error: String? = null,
) {
    val isHardware: Boolean get() = route == DecodeRoute.HARDWARE && !exceedsCapabilities
    val isPlayable: Boolean get() = route != DecodeRoute.UNSUPPORTED

    companion object {
        fun unsupported(mimeType: String?, note: String) =
            CodecProbeResult(DecodeRoute.UNSUPPORTED, mimeType, note = note)
    }
}