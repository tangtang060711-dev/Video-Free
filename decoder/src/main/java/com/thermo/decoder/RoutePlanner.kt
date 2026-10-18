package com.thermo.decoder

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import com.thermo.decoder.codec.CodecProbeResult
import com.thermo.decoder.codec.DecodeRoute
import com.thermo.decoder.codec.VideoCodecProbe
import com.thermo.decoder.container.ContainerProbe
import com.thermo.decoder.container.ContainerType
import com.thermo.decoder.container.SniffResult
import com.thermo.player.QualityProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 打开播放页**之前**能给出的结论。
 *
 * @param playable          这条片子能不能播。false 时 UI 应该直接显示 [note]，
 *                          而不是把 URI 丢给 ExoPlayer 等它报错
 * @param expectedRoute     预计会走的解码路径。注意它是**基于 MIME 的预估**：
 *                          真实分辨率/帧率要等容器解出来才知道，
 *                          所以它只用来做"是不是要提示用户会发烫"这种事，
 *                          真正的决策以运行期 [VideoCodecProbe.probe] 为准
 * @param expectedMimeTypes 该容器常见的视频编码，按"在这台机器上可行性"排过序
 */
data class PreflightDecision(
    val playable: Boolean,
    val container: ContainerType,
    val expectedRoute: DecodeRoute,
    val expectedMimeTypes: List<String>,
    val note: String,
) {
    /** 会以 CPU 软解播放 —— 意味着要提前告诉用户"这条片子会比较烫"。 */
    val willRunOnCpu: Boolean get() = expectedRoute != DecodeRoute.HARDWARE
}

/**
 * 运行期出错后的动作。
 * 分成三种而不是"是/否"，是因为这三种错误的正确应对**完全不同**，
 * 尤其是"超出能力"这一类：直觉告诉我们要切软解，但切软解会把发热放大数倍 ——
 * 正确做法是**降低要解的那路画质**，让硬解重新变得可用。
 */
sealed interface FallbackAction {
    /** 换一条解码路径重试（Media3 的 enableDecoderFallback 会自动做，这里只是记录意图）。 */
    data class RetryWithSoftware(val reason: String) : FallbackAction

    /** 降画质重试：把约束压到硬解能吃的范围内。 */
    data class RetryWithLowerQuality(val profile: QualityProfile, val reason: String) : FallbackAction

    /** 没有可用的退路。把 [reason] 原样展示给用户，别再无意义地转圈。 */
    data class GiveUp(val reason: String, val userMessage: String) : FallbackAction
}

/**
 * 把"容器 + 编码 + 错误码"翻译成一条解码路径。
 *
 * 这个类里**没有一行 Android 播放代码**，全部是查表和映射 ——
 * 它可以在 JVM 单测里被穷举，而"硬解切软解失败"这种 Bug 在真机上
 * 复现一次要几十秒，在单测里只要一行。
 */
@Singleton
class RoutePlanner @Inject constructor(
    private val containerProbe: ContainerProbe,
    private val videoCodecProbe: VideoCodecProbe,
) {

    /** 嗅探容器，顺手给出预判。UI 打开播放页时先调它。 */
    suspend fun preflight(uri: Uri): PreflightDecision {
        val sniff = containerProbe.sniff(uri)
        return preflight(sniff)
    }

    fun preflight(sniff: SniffResult): PreflightDecision {
        val container = sniff.container
        val mimes = container.probableVideoMimeTypes()

        // 纯音频容器（FLAC/WAV/MP3/AMR/OGG）：不需要视频路径判断。
        if (mimes.isEmpty()) {
            return PreflightDecision(
                playable = container.media3CanExtract,
                container = container,
                expectedRoute = DecodeRoute.SOFTWARE,
                expectedMimeTypes = emptyList(),
                note = if (container.media3CanExtract) {
                    "纯音频容器（${container.label}），无视频解码开销"
                } else {
                    "无法识别的容器：${sniff.detail ?: "未知"}"
                },
            )
        }

        if (!container.media3CanExtract) {
            // Media3 根本解不开这个容器。就算编码有硬解也没用 ——
            // 硬解器拿不到码流。这里直接判不可播，不给出虚假希望。
            val hint = when (container) {
                ContainerType.AVI ->
                    "AVI 容器 Media3 没有解析器（RIFF 不在其 extractor 集合里）。" +
                        "AVI 里通常是 MPEG-4 Part 2(Xvid) 或 MPEG-2，骁龙 8 Elite 也没有这两种硬解，" +
                        "即使补上解析器也必然走 CPU 软解。"
                ContainerType.MPEG_PS ->
                    "MPEG-PS（.mpg/.vob）Media3 没有解析器，且内容多为 MPEG-2，无硬解。"
                ContainerType.REAL_MEDIA ->
                    "RealMedia 是已淘汰的私有格式，无解析器、无硬解。"
                ContainerType.ASF ->
                    "ASF/WMV 基于 VC-1，骁龙 8 Elite 无硬解，Media3 也无解析器。"
                else -> "该容器不在 Media3 支持列表内。"
            }
            return PreflightDecision(
                playable = false,
                container = container,
                expectedRoute = DecodeRoute.UNSUPPORTED,
                expectedMimeTypes = mimes,
                note = hint,
            )
        }

        // 逐个 MIME 探测，挑可行性最高的那个。
        // 这里 width/height/frameRate 都给 0 —— 也就是说这是**编码级**的探测，
        // 回答的是"这台机器认不认 H.265"而不是"能不能解 4K H.265"。
        // 后者必须等容器解出真实 Format 才能答（见 VideoCodecProbe.probe）。
        val probes = mimes.map { mime -> mime to videoCodecProbe.probeMimeType(mime) }
        val best = probes
            .filter { (_, r) -> r.isPlayable }
            .maxByOrNull { (_, r) -> score(r) }

        return if (best == null) {
            PreflightDecision(
                playable = false,
                container = container,
                expectedRoute = DecodeRoute.UNSUPPORTED,
                expectedMimeTypes = mimes,
                note = "${container.label} 容器可以解析，但里面的编码（${mimes.joinToString()}）本机没有解码器。",
            )
        } else {
            val (mime, result) = best
            PreflightDecision(
                playable = true,
                container = container,
                expectedRoute = result.route,
                expectedMimeTypes = listOf(mime) + mimes.filter { it != mime },
                note = when (result.route) {
                    DecodeRoute.HARDWARE -> "${container.label} / $mime：可硬解"
                    DecodeRoute.SOFTWARE -> "${container.label} / $mime：无硬解，将走平台软解（会更耗电）"
                    DecodeRoute.UNSUPPORTED -> "${container.label} / $mime：无可用解码器"
                },
            )
        }
    }

    /** 运行期拿到真实 [Format] 后调用，结论最准。 */
    fun probeFormat(format: Format): CodecProbeResult = videoCodecProbe.probe(format)

    /**
     * 播放出错后决定怎么办。
     *
     * @param error       ExoPlayer 抛出的 PlaybackException
     * @param attempt     这是第几次尝试（0 = 第一次失败）。用它防止无限重试：
     *                    降画质最多一次、切软解最多一次，两次之后就该认输。
     * @param current     当前生效的画质约束，降级时在它基础上再压一档
     */
    fun onPlaybackError(
        error: PlaybackException,
        attempt: Int,
        current: QualityProfile,
    ): FallbackAction {
        val code = error.errorCode
        val detail = error.cause?.message ?: error.message

        return when (code) {
            // ---- 磁盘/网络类：和解码无关，降级没有任何意义 ----
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            ->
                FallbackAction.GiveUp(
                    reason = "IO 错误 code=$code $detail",
                    userMessage = when (code) {
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或已被删除"
                        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取该文件的权限"
                        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "该地址是 HTTP 明文，被系统拦截"
                        else -> "网络或文件读取失败：$detail"
                    },
                )

            // ---- 超出设备能力：绝！对！不！要！切软解 ----
            // 8K 片子在 4K 上限的机器上，软解是"能出画面"，但那是一帧一帧
            // 用 CPU 硬算出来的，骁龙 8 Elite 满负荷跑 8K 软解，
            // 几分钟就能把温度顶到 45°C 以上并触发系统降频。
            // 正确动作是把约束压到硬解吃得下的范围，让硬解重新回来。
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                if (attempt >= 1) {
                    FallbackAction.GiveUp(
                        reason = "超出能力且已降过一次画质 code=$code",
                        userMessage = "该视频分辨率超出本机解码能力，降画质后仍无法播放",
                    )
                } else {
                    FallbackAction.RetryWithLowerQuality(
                        profile = current.intersect(QualityProfile.CAP_1080P),
                        reason = "格式超出解码能力，先降到 1080p 让硬解重新可用",
                    )
                }
            }

            // ---- 解码器起不来 / 解码中途失败：这才是真正的"该切软解" ----
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> {
                if (attempt >= 1) {
                    FallbackAction.GiveUp(
                        reason = "硬解与软解都失败 code=$code $detail",
                        userMessage = "解码失败：$detail",
                    )
                } else {
                    // 注意这里没有"真的去切"—— :core-player 的
                    // ThermoRenderersFactory 已经开了 enableDecoderFallback(true)，
                    // Media3 会在硬解失败时自动沿解码器列表往下试（我们的
                    // HardwareFirstMediaCodecSelector 把软解排在列表最后）。
                    // 这里返回的动作是让上层**重建一次播放器**，
                    // 清掉上一次失败留下的半初始化状态。
                    FallbackAction.RetryWithSoftware(reason = "解码器失败 code=$code $detail")
                }
            }

            // ---- 音频输出问题：多半是 AudioTrack 被抢 ----
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            ->
                FallbackAction.GiveUp(
                    reason = "音频输出失败 code=$code $detail",
                    userMessage = "音频输出被占用或初始化失败，请关闭其他正在播放的应用",
                )

            // ---- 容器解析失败 ----
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            ->
                FallbackAction.GiveUp(
                    reason = "容器解析失败 code=$code $detail",
                    userMessage = "无法解析该文件格式（容器或文件已损坏）",
                )

            else -> FallbackAction.GiveUp(
                reason = "未归类的播放错误 code=$code $detail",
                userMessage = "播放失败：$detail",
            )
        }
    }

    /** 排序用：越靠前越"省电"。硬解 >> 平台软解 >> 无。 */
    private fun score(r: CodecProbeResult): Int = when {
        r.isHardware -> 100
        r.route == DecodeRoute.SOFTWARE -> 50
        else -> 0
    }
}

/**
 * 各容器里最常见的视频编码，按"在骁龙 8 Elite 上的可行性"从高到低排。
 *
 * 这张表是**先验**不是事实：一个 MKV 里可能装 MPEG-2（罕见但合法）。
 * 它的作用是在还没有真实 Format 的时候给一个合理猜测，
 * 猜错了也不会误判 —— 运行期 [RoutePlanner.probeFormat] 会拿到真实 Format 覆盖它。
 *
 * MIME 对照：
 *   video/avc      H.264
 *   video/hevc     H.265
 *   video/av01     AV1
 *   video/vp9      VP9
 *   video/mp4v-es  MPEG-4 Part 2（Xvid/DivX）—— 无硬解
 *   video/mpeg2    MPEG-2 —— 无硬解
 */
internal fun ContainerType.probableVideoMimeTypes(): List<String> = when (this) {
    // MP4 家族：H.264 最普遍，H.265 次之，AV1 正在起步。
    // 把 avc 放最前面还有一个原因：H.264 的硬解在低端机上也最稳，
    // 而我们排序只是为了"预判"，把最可能命中的排前面能少做几次探测。
    ContainerType.MP4,
    ContainerType.MOV,
    -> listOf("video/avc", "video/hevc", "video/av01", "video/mp4v-es")

    // MKV 是万能容器，什么都可能装。VP9 在 MKV 里比在 MP4 里常见得多。
    ContainerType.MKV -> listOf("video/avc", "video/hevc", "video/vp9", "video/av01", "video/mpeg2")

    // WebM 规范上只允许 VP8/VP9/AV1，绝不能装 H.264。
    ContainerType.WEBM -> listOf("video/vp9", "video/av01", "video/x-vnd.on2.vp8")

    // FLV 历史上装 H.263 / VP6，现在（直播）几乎清一色 H.264。
    // 老 FLV 里的 VP6/FLV1 本机没有解码器，探测会自动把它们筛掉。
    ContainerType.FLV -> listOf("video/avc", "video/x-flv")

    // MPEG-TS：广播流以 H.264/MPEG-2 为主，H.265 在 UHD 广播里。
    ContainerType.MPEG_TS -> listOf("video/avc", "video/hevc", "video/mpeg2")

    // ---- 以下三种 Media3 都解不开，列出来只是为了在诊断面板显示"里面可能是什么" ----
    ContainerType.AVI -> listOf("video/mp4v-es", "video/mpeg2")
    ContainerType.MPEG_PS -> listOf("video/mpeg2", "video/mp4v-es")
    ContainerType.REAL_MEDIA -> listOf("video/x-realvideo")

    // 纯音频容器与未知容器：没有视频轨要判断。
    ContainerType.OGG,
    ContainerType.FLAC,
    ContainerType.WAV,
    ContainerType.MP3,
    ContainerType.AMR,
    ContainerType.ASF,
    ContainerType.UNKNOWN,
    -> emptyList()
}