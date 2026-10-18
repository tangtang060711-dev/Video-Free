package com.thermo.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.thermo.data.settings.ThermoSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * 多线程分片下载器。
 *
 * ## 为什么需要它
 *
 * 播放器播网络地址时，数据是"边下边丢"的 —— CacheDataSource 那点缓存只够
 * 顶住网络抖动，看完就没了。用户想反复看、想在没网的地方看，就得有离线。
 * 系统 DownloadManager 不支持分片并行、也不给每片进度，所以自己写。
 *
 * ## 分片模型
 *
 * 文件按 [CHUNK]（1 MiB）切成 N 片，每个 worker 抢一片：
 * "抢"用一个共享的 [AtomicInteger] 自增实现 —— 比给每个线程预先分配固定区间
 * 好，因为慢的那片不会拖住其它线程（预先分配时，某一片落在慢 CDN 节点上，
 * 那个线程就一直卡着，整体速度取决于最慢的一片）。
 *
 * ## 断点续传
 *
 * 每完成一片就把该片下标写进一个 BitSet，落盘成 `<id>.bitmap`。
 * 暂停/被系统杀掉之后重新开始时，先读回 BitSet，跳过已完成的片。
 * 用的是 BitSet 而不是"已下载字节数" —— 后者在有空洞的情况下毫无意义
 * （前面跳过的片会被误认为已下载）。
 *
 * ## 已知限制
 *
 * 服务端不支持 Range（返回 200 而不是 206）时退化成单连接顺序下载，
 * 此时断了只能从头再来。这是 HTTP 的固有限制，不是实现偷懒。
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: ThermoSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = DownloadDb(context)

    /** id -> 正在跑的任务。用它防"同一个任务被起两次"。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 用户主动暂停的 id。用来区分协程被取消是"暂停"还是"进程要死了"。 */
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** 全部任务，新的在前。直接喂给 Compose。 */
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /**
     * 下载落地目录。
     *
     * 用应用私有的 `filesDir` 而不是外部存储的 Downloads：
     * 前者不需要任何存储权限，也不会在拔掉 SD 卡之后变成一堆读不到的文件。
     * 代价是卸载应用时文件一起没了 —— 对下载器来说这是合理的。
     */
    private val dir: File = File(context.applicationContext.filesDir, "downloads")

    init {
        dir.mkdirs()
        scope.launch {
            // 上次进程死掉时状态是 RUNNING/QUEUED 的，现在一律降级成 PAUSED：
            // 没有任何协程在替它跑，界面上显示"正在下载"是骗人的。
            val loaded = db.loadAll().map {
                if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                    it.copy(status = DownloadStatus.PAUSED)
                } else {
                    it
                }
            }
            _tasks.value = loaded
        }
    }

    // ------------------------------------------------------------------ 公开 API

    /** 入队并立刻开始，返回任务 id。 */
    fun enqueue(url: String, title: String? = null): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val name = title?.takeIf { it.isNotBlank() } ?: guessName(url)
        val task = DownloadTask(
            id = id,
            url = url,
            title = name,
            totalBytes = -1L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED,
            // 线程数在**创建时**取一次并记下来：之后用户在设置里改数字，
            // 不该让一个下到一半的任务突然换并行度（位图是按当时的分片大小算的）。
            threads = clampThreads(settings.downloadThreads.value),
            filePath = File(dir, "$id.part").absolutePath,
            supportsRange = false,
            createdAtMs = System.currentTimeMillis(),
        )
        _tasks.update { listOf(task) + it }
        db.upsert(task)
        start(id)
        return id
    }

    /** 开始/继续。已经在跑的会直接返回。 */
    fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        pausedIds.remove(id)
        jobs[id] = scope.launch {
            try {
                runTask(id)
            } finally {
                jobs.remove(id)
            }
        }
    }

    /** 暂停。分片位图保留，恢复时从断点继续。 */
    fun pause(id: String) {
        pausedIds.add(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        patch(id) { it.copy(status = DownloadStatus.PAUSED) }
        persist(id)
    }

    fun resume(id: String) {
        val t = task(id) ?: return
        if (t.status == DownloadStatus.DONE) return
        patch(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        start(id)
    }

    /** 取消并**删除已下载的数据**。半截文件占的空间往往比用户想的多。 */
    fun cancel(id: String) {
        pausedIds.remove(id)
        jobs[id]?.cancel()
        jobs.remove(id)
        task(id)?.let { t ->
            runCatching { File(t.filePath).delete() }
            runCatching { bitmapFile(t).delete() }
        }
        db.delete(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /** 播放已下载的文件。返回 null 表示这条任务的文件不在了。 */
    fun uriFor(id: String): Uri? {
        val t = task(id) ?: return null
        val f = File(t.filePath)
        return if (f.exists()) Uri.fromFile(f) else null
    }

    fun task(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 内部工具

    // ------------------------------------------------------------------ 主流程

    private suspend fun runTask(id: String) {
        val task = task(id) ?: return
        patch(id) { it.copy(status = DownloadStatus.RUNNING, error = null) }
        persist(id)

        val part = File(task.filePath)
        try {
            // totalBytes > 0 说明是"续传"（数据库里记着上次探测到的长度），
            // 不用再探一次。这是省一次网络往返，也是省一次被服务端限流的机会。
            val probe = if (task.totalBytes > 0L) {
                RangeProbe(task.totalBytes, task.supportsRange)
            } else {
                withContext(Dispatchers.IO) { probeRange(task.url) }
            }

            if (probe.totalBytes <= 0L || !probe.supportsRange) {
                patch(id) {
                    it.copy(
                        totalBytes = probe.totalBytes,
                        supportsRange = probe.supportsRange,
                    )
                }
                downloadSequential(task.id, task.url, part)
            } else {
                patch(id) {
                    it.copy(totalBytes = probe.totalBytes, supportsRange = true)
                }
                downloadChunked(task, part, probe.totalBytes)
            }
            finish(id, part)
        } catch (ce: CancellationException) {
            // 暂停/取消走的是这条路，不是错误。状态已经由 pause()/cancel() 写好了。
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "下载失败 id=$id url=${task.url}", t)
            patch(id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
            persist(id)
        }
    }

    /**
     * 分片并行下载。
     *
     * [coroutineScope] 把 N 个 worker 包成一个作用域：任何一个抛异常，
     * 其余的一起被取消 —— 不这样的话某一片失败之后，其它 31 个还在傻跑，
     * 用户看到的是"进度不动了但流量还在烧"。
     */
    private suspend fun downloadChunked(task: DownloadTask, target: File, total: Long) {
        val chunks = ((total + CHUNK - 1L) / CHUNK).toInt()
        val bits = loadBitmap(bitmapFile(task), chunks)

        // 续传时先把已完成的片算进进度，否则界面会从 0% 跳到 30%。
        val done = AtomicLong(0L)
        for (i in 0 until chunks) {
            synchronized(bits) { if (bits.get(i)) done.addAndGet(chunkLength(i, total)) }
        }

        target.parentFile?.mkdirs()
        if (!target.exists()) target.createNewFile()

        val next = AtomicInteger(0)
        // worker 数不能超过片数，否则一堆线程起来抢不到活，纯白开。
        val workers = clampThreads(task.threads).coerceAtMost(chunks)
        val lastPersist = AtomicLong(0L)

        coroutineScope {
            repeat(workers) {
                launch(Dispatchers.IO) {
                    // 每个 worker 开自己的 RandomAccessFile。
                    // 共用一个的话 seek+write 不是原子操作，两个线程会互相把
                    // 文件指针挪走 —— 表现为"下下来的文件大小对但内容是乱的"，
                    // 而且只在多线程下复现，非常难查。
                    RandomAccessFile(target, "rw").use { raf ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val idx = next.getAndIncrement()
                            if (idx >= chunks) break
                            val already = synchronized(bits) { bits.get(idx) }
                            if (already) continue

                            val start = idx * CHUNK
                            val end = (start + CHUNK - 1L).coerceAtMost(total - 1L)
                            fetchRangeWithRetry(task.url, start, end, raf)

                            synchronized(bits) { bits.set(idx) }
                            saveBitmap(bits, bitmapFile(task))

                            val now = done.addAndGet(end - start + 1L)
                            patch(task.id) { it.copy(downloadedBytes = now, totalBytes = total) }
                            val t = System.currentTimeMillis()
                            if (t - lastPersist.get() >= PERSIST_INTERVAL_MS) {
                                lastPersist.set(t)
                                persist(task.id)
                            }
                        }
                    }
                }
            }
        }

        patch(task.id) { it.copy(downloadedBytes = total, totalBytes = total) }
        saveBitmap(bits, bitmapFile(task))
    }

    /**
     * 单连接顺序下载。服务端不支持 Range 时走这条。
     *
     * 每 3 秒落一次盘而不是每个 buffer：128 KiB 一个 buffer，
     * 一个 1 GB 的文件是 8000 次写入 —— 那是拿 flash 寿命换进度显示。
     */
    private suspend fun downloadSequential(id: String, url: String, target: File) {
        target.parentFile?.mkdirs()
        val conn = openConnection(url, "GET")
        // 和分片下载同理：一旦被 gzip，写下去的就不是原始文件了。
        conn.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { raf ->
                    // 从头写：非 Range 服务器上"已下载"没有任何意义。
                    raf.setLength(0L)
                    raf.seek(0L)
                    val buf = ByteArray(128 * 1024)
                    var written = 0L
                    var lastPersist = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        val t = System.currentTimeMillis()
                        if (t - lastPersist >= PERSIST_INTERVAL_MS) {
                            lastPersist = t
                            patch(id) { it.copy(downloadedBytes = written) }
                            persist(id)
                        }
                    }
                    patch(id) { it.copy(downloadedBytes = written) }
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 收尾：把 `.part` 改成可读的文件名、删掉位图、状态置 DONE。 */
    private fun finish(id: String, part: File) {
        val t = task(id) ?: return
        val finalFile = finalFileFor(t)
        runCatching {
            if (finalFile.exists()) finalFile.delete()
            if (part.exists()) {
                if (!part.renameTo(finalFile)) {
                    // renameTo 跨文件系统会失败（这里不会，但别让失败静默）。
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
            } else {
                finalFile.createNewFile()
            }
        }
        runCatching { bitmapFile(t).delete() }
        patch(id) {
            it.copy(
                status = DownloadStatus.DONE,
                filePath = finalFile.absolutePath,
                downloadedBytes = if (it.totalBytes > 0L) it.totalBytes else it.downloadedBytes,
                error = null,
            )
        }
        persist(id)
        Log.i(TAG, "下载完成 id=$id -> ${finalFile.absolutePath} (${finalFile.length()} B)")
    }

    private fun patch(id: String, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    private fun persist(id: String) {
        task(id)?.let { db.upsert(it) }
    }

    private fun clampThreads(n: Int): Int = n.coerceIn(MIN_THREADS, MAX_THREADS)

    // ------------------------------------------------------------------ HTTP

    /**
     * 探测总长度与是否支持 Range。
     *
     * 刻意**只用一次请求**：`GET` + `Range: bytes=0-0`。
     *  - 206 + `Content-Range: bytes 0-0/12345` -> 长度 12345，支持分片
     *  - 200 + `Content-Length: 12345`         -> 长度 12345，不支持分片
     *
     * 为什么不先发 HEAD：不少 CDN 根本不实现 HEAD（405/501），
     * 而且就算实现了，它给的 `Accept-Ranges` 也经常是错的 —— 有判断力的
     * 服务端会漏发这个头。多一次往返换一个不可靠的答案不值得。
     *
     * 注意 200 的那条分支：服务端会开始推整个文件，我们只读响应头就断开，
     * 一个字节都不读 —— 读完就得先下完整个片子才能开始下。
     */
    private fun probeRange(url: String): RangeProbe {
        val conn = openConnection(url, "GET")
        conn.setRequestProperty("Range", "bytes=0-0")
        conn.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range")
            val total = contentRange
                ?.substringAfter('/', "-1")
                ?.trim()
                ?.toLongOrNull() ?: -1L
            if (code == HttpURLConnection.HTTP_PARTIAL && total > 0L) {
                return RangeProbe(total, true)
            }
            val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            return RangeProbe(len, false)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 带重试地拉一片。分片下载最怕"某一片偶发失败整任务就废了"。 */
    private fun fetchRangeWithRetry(url: String, start: Long, end: Long, raf: RandomAccessFile) {
        var attempt = 0
        while (true) {
            try {
                fetchRange(url, start, end, raf)
                return
            } catch (io: IOException) {
                attempt++
                if (attempt >= MAX_RETRY_PER_CHUNK) throw io
                // 线性退避。移动网络下 400ms/800ms 足够躲过一次瞬时丢包，
                // 又不会让用户觉得卡住了。
                Thread.sleep(400L * attempt)
            }
        }
    }

    private fun fetchRange(url: String, start: Long, end: Long, raf: RandomAccessFile) {
        val conn = openConnection(url, "GET")
        // 关键：不接受压缩。开了 gzip 之后服务端返回的是压缩流的字节，
        // Range 偏移对应的就不再是原始文件的位置，拼出来的文件必然损坏。
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Range", "bytes=$start-$end")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(READ_BUFFER)
                var pos = start
                while (true) {
                    // 暂停时协程被 cancel，但这里是阻塞读，感知不到。
                    // 线程中断是 cancel 之外唯一能传进来的信号。
                    if (Thread.currentThread().isInterrupted) throw IOException("interrupted")
                    val n = input.read(buf)
                    if (n <= 0) break
                    raf.seek(pos)
                    raf.write(buf, 0, n)
                    pos += n
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: throw IOException("地址无法解析：$url")
        val conn = parsed.openConnection() as? HttpURLConnection
            ?: throw IOException("只支持 http/https 地址")
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    // ------------------------------------------------------------------ 位图与文件名

    private fun chunkLength(index: Int, total: Long): Long {
        val start = index * CHUNK
        return (total - start).coerceAtMost(CHUNK)
    }

    private fun bitmapFile(task: DownloadTask): File = File(dir, "${task.id}.bitmap")

    private fun loadBitmap(f: File, chunks: Int): BitSet {
        val bits = BitSet(chunks)
        if (!f.exists()) return bits
        runCatching {
            // BitSet.valueOf 按小端位序解释字节，saveBitmap 用的也是同一个
            // toByteArray，往返一致。换实现时两边必须一起换。
            bits.or(BitSet.valueOf(f.readBytes()))
        }
        return bits
    }

    private fun saveBitmap(bits: BitSet, f: File) {
        runCatching { f.writeBytes(bits.toByteArray()) }
    }

    /**
     * 最终文件名：用标题，扩展名从 URL 猜；重名就加 `_1`、`_2`。
     *
     * 标题通常就是 URL 的最后一段（blob.bin），扩展名已经含在里面了，
     * 所以要先判断再拼 —— 否则会得到 blob.bin.bin，看着像文件坏了，
     * 用户会以为自己下错了东西。
     */
    private fun finalFileFor(task: DownloadTask): File {
        val ext = guessExt(task.url)
        val title = sanitize(task.title).ifBlank { task.id }
        val stem = if (ext.isNotEmpty() && title.endsWith(ext, ignoreCase = true)) {
            title.dropLast(ext.length).ifBlank { task.id }
        } else {
            title
        }
        var candidate = File(dir, if (ext.isNotEmpty()) "$stem$ext" else stem)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isNotEmpty()) "${stem}_$n$ext" else "${stem}_$n")
            n++
        }
        return candidate
    }

    /** 去掉文件名里的路径分隔符与控制字符 —— 标题是网络来的，不能直接当文件名。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .take(80)

    private fun guessName(url: String): String {
        val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return last.ifBlank { "未命名视频" }
    }

    private fun guessExt(url: String): String {
        val last = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        if (dot <= 0 || dot == last.length - 1) return ""
        val ext = last.substring(dot)
        // 只接受短且像扩展名的；`.php`、`.aspx` 之类不是媒体后缀，丢掉。
        return if (ext.length in 2..6 && ext.drop(1).all { it.isLetterOrDigit() }) ext else ""
    }
    companion object {
        private const val TAG = "DownloadManager"

        /**
         * 分片大小 1 MiB。
         * 太小（比如 64 KiB）会让 1 GB 的文件产生 16000 个请求，光建连就够呛；
         * 太大（比如 16 MiB）会让并行粒度变粗，32 线程抢不到活干。
         */
        private const val CHUNK = 1L * 1024L * 1024L

        private const val MIN_THREADS = 1
        private const val MAX_THREADS = 32
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_RETRY_PER_CHUNK = 3
        private const val PERSIST_INTERVAL_MS = 3_000L
        private const val READ_BUFFER = 64 * 1024

        /**
         * 写死的桌面 Chrome UA。
         * 很多站点的 CDN 对空 UA 直接返回 403，或者返回降级后的低码率文件；
         * 用浏览器 UA 拿到的是和网页播放完全相同的那一份数据。
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
