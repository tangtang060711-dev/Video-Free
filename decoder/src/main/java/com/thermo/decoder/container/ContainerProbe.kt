package com.thermo.decoder.container

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.thermo.decoder.di.ProbeDataSource

/**
 * 容器嗅探的结果。
 *
 * @param container  识别出的容器类型
 * @param detail     附加信息（MP4 的 major brand、MKV 的 DocType），仅用于诊断面板
 * @param bytesRead  实际读到的字节数。小于预期说明文件很小或者被截断
 * @param error      读失败的原因；非 null 时 container 一定是 UNKNOWN
 */
data class SniffResult(
    val container: ContainerType,
    val detail: String?,
    val bytesRead: Int,
    val error: String? = null,
) {
    /** 读到了完整窗口且没报错，说明这个结论是可信的。 */
    val isReliable: Boolean get() = error == null && bytesRead >= MIN_RELIABLE_BYTES

    companion object {
        /** 4096 字节足够覆盖 ISO BMFF 的 ftyp box + 若干 brand，也够 MKV 的 DocType。 */
        const val SNIFF_BYTES = 4096
        private const val MIN_RELIABLE_BYTES = 16
        val UNKNOWN = SniffResult(ContainerType.UNKNOWN, null, 0)
    }
}

/**
 * 从数据源读文件头，判断容器类型。
 *
 * ## 为什么只读 4KB 而不是把文件解一遍
 * 探测的目的是"在打开播放页之前就知道这条路通不通"。
 * 如果真的去解容器，那就等于已经做了播放器要做的事，探测本身变成了开销。
 * 4KB 的随机读在任何介质上都是毫秒级，代价可以忽略。
 *
 * ## 落在这层的好处
 * 走的是 [DataSource.Factory]，所以本地文件、content:// 相册、HTTP 远程
 * 走的是同一条路径 —— 本地文件由 DefaultDataSource 直读，网络走缓存层。
 * 探测一个远程 AVI 不会把整个文件下下来。
 */
@Singleton
class ContainerProbe @Inject constructor(
    @ProbeDataSource
    private val dataSourceFactory: DataSource.Factory,
) {

    suspend fun sniff(uri: Uri): SniffResult = withContext(Dispatchers.IO) {
        val source = dataSourceFactory.createDataSource()
        try {
            // position = 0 很关键：DefaultHttpDataSource 只在 position > 0 时
            // 才强制要求服务端返回 206。position = 0 时服务端返回 200（不支持 Range）
            // 也能正常读完前 4KB，不会抛异常。
            val spec = DataSpec(uri, 0L, SniffResult.SNIFF_BYTES.toLong())
            source.open(spec)
            val buffer = ByteArray(SniffResult.SNIFF_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = source.read(buffer, read, buffer.size - read)
                if (n == -1) break
                read += n
            }
            val detected = ContainerSniffer.detect(buffer, read)
            SniffResult(detected.container, detected.detail, read)
        } catch (t: Throwable) {
            // 探测失败不应该让播放流程中断：上层拿到 UNKNOWN 后会退回
            // "交给 Media3 自己试" 的行为，和没有这个模块时一样。
            SniffResult(ContainerType.UNKNOWN, null, 0, t.message)
        } finally {
            runCatching { source.close() }
        }
    }
}
