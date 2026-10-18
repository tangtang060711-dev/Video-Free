package com.thermo.player

/**
 * 播放场景。它的唯一作用就是决定缓冲策略 —— 没有别的作用，也不该有。
 *
 * 之所以要在建 ExoPlayer **之前**就把场景定下来，而不是运行期动态改：
 * 因为 LoadControl 的缓冲参数是构造期传入的，Media3 不提供 setter。
 * 想换场景就只能重建 LoadControl + 重建 ExoPlayer，代价远大于收益。
 * 所以这里要求调用方在 [com.thermo.player.ThermoPlayer.prepare] 时一次性说清楚。
 *
 * 三档参数的取值逻辑见 [com.thermo.player.internal.LoadControlFactory]。
 */
enum class PlaybackScenario {
    /**
     * 本地文件 / content:// —— IO 延迟几乎为零，缓冲纯粹是浪费内存和电量。
     * 注意：本地也**不能**把缓冲设成 0，否则一次 GC 停顿就会卡帧。
     */
    LOCAL,

    /** 点播网络流（HTTP/HTTPS/HLS 点播/DASH 点播）—— 要抗抖动，但不必抗太久。 */
    STREAM,

    /** 直播 —— 缓冲越深延迟越大，且直播的码率通常已经很低，深缓冲没有收益。 */
    LIVE,
    ;

    companion object {
        /**
         * 按 URL 猜场景。**只是猜**：HLS/DASH 既可能是点播也可能是直播，
         * 真正的判定要靠 MediaItem 里的 MediaMetadata.isLive 或播放器回调，
         * 调用方拿到确定信息后应显式覆盖。
         */
        fun guessFrom(url: String): PlaybackScenario {
            val u = url.lowercase()
            return when {
                u.startsWith("content://") || u.startsWith("file://") || !u.contains("://") -> LOCAL
                u.contains(".m3u8") || u.contains(".mpd") -> STREAM
                else -> STREAM
            }
        }
    }
}