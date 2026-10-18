package com.thermo.data.local

import android.net.Uri

/**
 * 本地媒体库中的一条记录。
 *
 * 刻意**不用 MediaStore 的 Cursor 直接喂给 UI**：Cursor 有生命周期，一旦
 * Activity 重建而 Cursor 失效，列表里所有缩略图会集体变灰。先映射成不可变
 * 的数据类，UI 层就只需要处理一个普通 List。
 *
 * @param id          MediaStore 的主键（同一台设备上稳定，可直接用作播放进度 key）
 * @param uri         内容 URI，可直接交给 Media3 的 MediaItem
 * @param displayName 文件名（含扩展名）
 * @param durationMs  时长；MediaStore 偶尔会给 0，上层要按"未知"处理
 * @param sizeBytes   文件大小
 * @param mimeType    形如 video/mp4、audio/flac
 * @param width       视频宽度，音频为 0
 * @param height      视频高度，音频为 0
 * @param dateAddedSec 入库时间（秒），用于倒序排列
 * @param isVideo     是视频还是纯音频
 */
data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val isVideo: Boolean,
) {
    /** 时长格式化成 mm:ss / h:mm:ss；未知时长（<=0）返回 "--:--"。 */
    val durationLabel: String
        get() {
            if (durationMs <= 0L) return "--:--"
            val totalSec = durationMs / 1000L
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }

    /** 分辨率标签；音频或尺寸未知时返回 null，由 UI 决定不显示。 */
    val resolutionLabel: String?
        get() = if (width > 0 && height > 0) "${width}x${height}" else null
}