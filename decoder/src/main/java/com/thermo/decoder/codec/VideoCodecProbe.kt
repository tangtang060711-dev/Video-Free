package com.thermo.decoder.codec

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "这个 Format 能不能硬解" —— 唯一权威的答案来自这里。
 *
 * ## 为什么用 [MediaCodecVideoRenderer.supportsFormat] 而不是自己遍历 MediaCodecList
 *
 * 自己遍历 `MediaCodecList` 然后比对 `CodecCapabilities` 看起来更"底层"、更可控，
 * 但实际上要重新实现一遍 Media3 已经写好的东西，而且**极容易写错**：
 *
 *   - 要区分 `video/avc` 与 `video/avc-baseline` 这种 MIME 后缀
 *   - 要处理 `profileLevels` 里同时列了 Baseline 和 High 但只在某个 level 下成立的情况
 *   - 要处理"分辨率在 `videoCapabilities.getSupportedWidths()` 区间内、
 *     但帧率×宽高之积超了 `getSupportedFrameRatesFor()`"这种二维约束
 *   - 要处理 Android 12 之后 `isHardwareAccelerated()` 与
 *     `isSoftwareOnly()` 在部分 ROM 上互相矛盾的情况
 *
 * [MediaCodecVideoRenderer.supportsFormat] 是**静态方法**：它不会真的
 * create/configure/start 任何解码器（那才是发热的动作），只是查表比对。
 * 它返回的位掩码里已经把上面的坑都处理完了，并且给了我们三个需要的位：
 *
 *   - `getFormatSupport`      = 支持到什么程度
 *   - `getHardwareAccelerationSupport` = 有没有硬解
 *   - `getAdaptiveSupport`    = 能不能无缝切清晰度
 *
 * ## 一个真实存在的陷阱
 * `isFormatSupported(caps, allowExceedsCapabilities = true)` 会在
 * "格式超出能力"时也返回 true。8K 片子在 4K 上限的机型上就会走到这条路径，
 * 结果是**硬解起不来、静默回退到软解**——用户以为在看硬解，手机在跑 CPU 解码。
 * 所以我们两个都查：严格版决定 route，宽松版用于区分
 * "不支持"和"支持但超能力"。
 */
@Singleton
class VideoCodecProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 探测一个真实的 [Format]。
     * 调用点通常在 `AnalyticsListener.onVideoInputFormatChanged`，
     * 那时 Media3 刚从容器里解出真实轨道参数（分辨率/帧率/codecs 串），
     * 这是信息最全的时刻。
     */
    fun probe(format: Format): CodecProbeResult {
        val mime = format.sampleMimeType
            ?: return CodecProbeResult.unsupported(null, "Format 里没有 sampleMimeType，无法探测")

        val candidates = runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
        }.getOrElse { e ->
            // DecoderQueryException：系统 MediaCodecList 查询失败。
            // 这在极少数 ROM 上会发生，不是"格式不支持"，而是"查不出来"。
            return CodecProbeResult(
                route = DecodeRoute.UNSUPPORTED,
                mimeType = mime,
                note = "查询系统解码器列表失败",
                error = e.message,
            )
        }

        // 按"对这个 Format 的支持程度"排序后取第一个：这就是 Media3
        // 真正会用的那个解码器（它内部也做同样的事）。
        val ordered = runCatching {
            MediaCodecUtil.getDecoderInfosSortedByFormatSupport(context, candidates, format)
        }.getOrDefault(candidates)
        val best = ordered.firstOrNull()

        val caps = runCatching {
            MediaCodecVideoRenderer.supportsFormat(context, MediaCodecSelector.DEFAULT, format)
        }.getOrElse { e ->
            return CodecProbeResult(
                route = DecodeRoute.UNSUPPORTED,
                mimeType = mime,
                decoderName = best?.name,
                candidateCount = candidates.size,
                note = "supportsFormat 查询失败",
                error = e.message,
            )
        }

        val strictSupported = RendererCapabilities.isFormatSupported(caps, false)
        val lenientSupported = RendererCapabilities.isFormatSupported(caps, true)
        val hardwareCaps = RendererCapabilities.getHardwareAccelerationSupport(caps)
        val exceeds = lenientSupported && !strictSupported

        val route = when {
            strictSupported && hardwareCaps == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED ->
                DecodeRoute.HARDWARE
            lenientSupported -> DecodeRoute.SOFTWARE
            else -> DecodeRoute.UNSUPPORTED
        }

        val profileLevel = runCatching { MediaCodecUtil.getCodecProfileAndLevel(format) }.getOrNull()

        return CodecProbeResult(
            route = route,
            mimeType = mime,
            decoderName = best?.name,
            hardwareAccelerated = best?.hardwareAccelerated == true,
            exceedsCapabilities = exceeds,
            profile = profileLevel?.first ?: -1,
            level = profileLevel?.second ?: -1,
            candidateCount = candidates.size,
            note = buildNote(route, exceeds, best, format),
        )
    }

    /**
     * 在**拿到真实 Format 之前**做一次预判。
     *
     * 用在两个地方：
     *   1) AVI / MPEG-PS 这类 Media3 解不了的容器 —— 需要按"里面多半是什么编码"
     *      先估一条路径，才能在播放页上给出"将以软解播放，会比较烫"的提示
     *   2) 诊断面板的"编码能力自检"，那时手上只有 MIME 没有文件
     *
     * @param codecs RFC 6381 的 codecs 串（如 "avc1.640028"、"hev1.1.6.L120.90"），
     *               可以给 null，给了能解出 profile/level，判断更准
     */
    fun probeMimeType(
        mimeType: String,
        width: Int = 0,
        height: Int = 0,
        frameRate: Float = 0f,
        bitrate: Int = 0,
        codecs: String? = null,
    ): CodecProbeResult {
        val builder = Format.Builder().setSampleMimeType(mimeType)
        if (width > 0) builder.setWidth(width)
        if (height > 0) builder.setHeight(height)
        if (frameRate > 0f) builder.setFrameRate(frameRate)
        // Format.Builder 上没有 setBitrate()（1.9.4 实测只有 setAverageBitrate/setPeakBitrate）。
        if (bitrate > 0) { builder.setAverageBitrate(bitrate); builder.setPeakBitrate(bitrate) }
        if (!codecs.isNullOrEmpty()) builder.setCodecs(codecs)
        return probe(builder.build())
    }

    /**
     * 列出系统里所有能处理这个 MIME 的解码器，硬解在前。
     * 用于诊断面板展示"这台机器到底有哪些解码器"。
     */
    fun listDecoders(mimeType: String): List<MediaCodecInfo> = runCatching {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, false, false)
    }.getOrDefault(emptyList())

    private fun buildNote(
        route: DecodeRoute,
        exceeds: Boolean,
        best: MediaCodecInfo?,
        format: Format,
    ): String {
        val size = if (format.width > 0 && format.height > 0) {
            "${format.width}x${format.height}"
        } else {
            "尺寸未知"
        }
        val fps = if (format.frameRate > 0f) "@${format.frameRate.toInt()}fps" else ""
        val name = best?.name ?: "无候选解码器"
        return when {
            exceeds -> "$size$fps 超出设备能力，将回退软解（$name）—— 这是最烫的情况，建议降画质"
            route == DecodeRoute.HARDWARE -> "$size$fps 硬解（$name）"
            route == DecodeRoute.SOFTWARE -> "$size$fps 无硬解，走平台软解（$name）"
            else -> "$size$fps 无任何可用解码器（候选 $name）"
        }
    }
}
