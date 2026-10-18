package com.thermo.ui.util

import java.util.Locale

/**
 * 时长格式化：毫秒 -> "mm:ss" 或 "h:mm:ss"。
 *
 * 刻意不用 `String.format("%02d:%02d", ...)` 那种写法，因为它在
 * 每次 recomposition 里都会新建 Formatter；播放进度条每 250ms 重组一次，
 * 一条两小时的影片光是格式化时间就要分配几万个对象 —— 这在
 * "尽量少发热"的目标下是白白送给 GC 的。
 */
fun Long.toClock(): String {
    if (this <= 0L) return "00:00"
    val totalSec = this / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "$h:${pad2(m)}:${pad2(s)}" else "${pad2(m)}:${pad2(s)}"
}

private fun pad2(v: Long): String = if (v < 10L) "0$v" else v.toString()

/**
 * 播放速度标签：1.0 -> "1x"，1.25 -> "1.25x"，1.5 -> "1.5x"。
 * 整数速度不显示小数点（"2x" 比 "2.00x" 好读），
 * 非整数速度去掉无意义的尾零（"1.5x" 而不是 "1.50x"）。
 */
fun Float.toSpeedLabel(): String {
    val asInt = toInt()
    return if (this == asInt.toFloat()) {
        "${asInt}x"
    } else {
        String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.') + "x"
    }
}

/** 码率显示：1500000 -> "1.5 Mbps"。 */
fun Int.toBitrateLabel(): String {
    if (this <= 0) return "—"
    return if (this >= 1_000_000) {
        String.format(Locale.US, "%.1f Mbps", this / 1_000_000f)
    } else {
        "${this / 1000} kbps"
    }
}

/** 分辨率标签，宽高为 0 时返回 null（表示未知，不要显示成 "0x0"）。 */
fun resolutionLabel(width: Int, height: Int): String? =
    if (width > 0 && height > 0) "${width}x$height" else null

/** 帧率标签。 */
fun frameRateLabel(fps: Float): String? =
    if (fps > 0f) String.format(Locale.US, "%.2f fps", fps) else null

/**
 * 把 Media3 的 MIME 变成人话。
 * 未知 MIME 原样返回 —— 显示 "video/x-unknown" 也比显示空白好，
 * 至少能让用户在反馈里贴出来。
 */
fun String?.toCodecLabel(): String = when (this) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/av01" -> "AV1"
    "video/vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4 Part2"
    "video/mpeg2" -> "MPEG-2"
    "video/3gpp" -> "H.263"
    "video/x-ms-wmv", "video/x-ms-vc1" -> "VC-1"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/flac" -> "FLAC"
    "audio/vorbis" -> "Vorbis"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC3"
    "audio/vnd.dts" -> "DTS"
    "audio/mpeg" -> "MP3"
    "audio/raw" -> "PCM"
    "text/vtt" -> "WebVTT"
    "application/x-subrip" -> "SRT"
    "application/ttml+xml" -> "TTML"
    null, "" -> "未知"
    else -> this!!
}

/** 手势累计位移 -> "+00:32" / "-01:10"，不足 1 秒返回 null 避免提示抖动。 */
fun gapLabel(deltaMs: Long): String? {
    if (deltaMs > -1000L && deltaMs < 1000L) return null
    val abs = if (deltaMs < 0L) -deltaMs else deltaMs
    return (if (deltaMs > 0L) "+" else "-") + abs.toClock()
}
/** 0f~1f -> 0~100 整数百分比。手势提示要用。 */
fun Float.toPercent(): Int = (this * 100f + 0.5f).toInt().coerceIn(0, 100)