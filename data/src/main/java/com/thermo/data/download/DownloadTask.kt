package com.thermo.data.download

/** 一个下载任务的生命周期状态。 */
enum class DownloadStatus {
    /** 已加入队列，还没开始跑。 */
    QUEUED,

    /** 正在下载。 */
    RUNNING,

    /** 用户主动暂停：分片位图保留，恢复时从断点继续。 */
    PAUSED,

    /** 下载完成，文件已改名为最终名字。 */
    DONE,

    /** 出错停下。error 字段里有原因。 */
    FAILED,
}

/**
 * 一个下载任务。
 *
 * 刻意做成不可变 data class 而不是可变对象：它要往 Compose 里喂，
 * Compose 判断"要不要重组"靠的是 equals，可变对象会让它每次都判为"变了"。
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    /** 总字节数。-1 表示服务端没给（分块传输、或探测失败）。 */
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    /** 实际用的分片数，创建时从设置里取一次并记下来 —— 之后改设置不影响已建任务。 */
    val threads: Int,
    val filePath: String,
    /** 服务端是否支持 Range 请求（HTTP 206）。false 时是单连接下载，断了只能从头来。 */
    val supportsRange: Boolean,
    val createdAtMs: Long,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isActive: Boolean get() = status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED

    val isDone: Boolean get() = status == DownloadStatus.DONE
}

/** 探测结果。 */
internal data class RangeProbe(
    val totalBytes: Long,
    val supportsRange: Boolean,
)