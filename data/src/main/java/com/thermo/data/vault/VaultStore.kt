package com.thermo.data.vault

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import javax.crypto.CipherOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 未解锁就访问加密文件时抛这个。数据源会把它转成 DataSourceException。 */
class VaultLockedException : Exception("加密空间尚未解锁")

/**
 * 加密空间的文件仓库。
 *
 * ## 目录结构
 * ```
 * filesDir/vault/
 *   index.bin     加密的索引（id -> 名字/大小/时间）
 *   <id>.tvlt     加密的视频，见 VaultFormat 的布局说明
 * ```
 * 文件名是随机 id，**不含任何原始信息**：在文件管理器里只能看到一堆乱码名。
 *
 * ## 解锁状态放在哪里
 * 只放在内存里（[unlocked]），进程一死就回到锁定。
 * 不持久化是刻意的：持久化等于"解过一次就永远开着"，加密空间就没意义了。
 */
@Singleton
class VaultStore @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val crypto: VaultCrypto,
) {

    /** 加密文件所在目录。internal：只有同模块的数据源要用。 */
    internal val dir: File = File(context.applicationContext.filesDir, "vault")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexLock = Mutex()

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** 身份校验通过后调用。会顺带把索引读出来。 */
    fun unlock() {
        _unlocked.value = true
        scope.launch { reload() }
    }

    fun lock() {
        _unlocked.value = false
        _entries.value = emptyList()
    }

    /**
     * 把一个外部视频搬进加密空间。
     *
     * 是"复制 + 加密"而不是"移动"：源文件可能来自 MediaStore 或 SD 卡，
     * 我们对它没有删除权限（也不该有）。要真删得让用户在文件管理器里自己来。
     */
    suspend fun import(source: Uri, displayName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!_unlocked.value) throw VaultLockedException()
            dir.mkdirs()
            val id = UUID.randomUUID().toString().replace("-", "")
            val target = fileFor(id)
            val input = context.contentResolver.openInputStream(source) ?: return@runCatching false
            val size = input.use { encryptInto(it, target) }
            if (size <= 0L) {
                target.delete()
                return@runCatching false
            }
            indexLock.withLock {
                val map = readIndex().toMutableMap()
                map[id] = VaultEntry(id, displayName, size, System.currentTimeMillis())
                writeIndex(map)
            }
            reload()
            true
        }.getOrDefault(false)
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!_unlocked.value) throw VaultLockedException()
            indexLock.withLock {
                val map = readIndex().toMutableMap()
                map.remove(id)
                writeIndex(map)
            }
            fileFor(id).delete()
            reload()
            true
        }.getOrDefault(false)
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        if (!_unlocked.value) {
            _entries.value = emptyList()
            return@withContext
        }
        _entries.value = indexLock.withLock { readIndex().values.toList() }
            .sortedBy { it.name }
    }

    // ---------------- URI 映射 ----------------

    fun uriFor(id: String): Uri = Uri.parse("${VaultFormat.SCHEME}://$id")

    fun isVaultUri(uri: Uri?): Boolean = uri?.scheme == VaultFormat.SCHEME

    fun idFromUri(uri: Uri?): String? {
        if (!isVaultUri(uri)) return null
        return uri?.host?.takeIf { it.isNotEmpty() } ?: uri?.path?.trimStart('/')?.takeIf { it.isNotEmpty() }
    }

    internal fun fileFor(id: String): File = File(dir, id + VaultFormat.EXT)

    internal fun requireUnlocked() {
        if (!_unlocked.value) throw VaultLockedException()
    }

    // ---------------- 加解密 ----------------

    /**
     * 把 [input] 加密写进 [target]，返回**明文**字节数。
     *
     * 头里要写明文长度，但流式读的时候还不知道长度 —— 所以先占位写一个空头，
     * 等密文写完再回头用 FileChannel 的绝对定位补上（不重写整个文件）。
     * 另一种做法是先写临时文件再拼头，但那要多写一整份 GB 级数据，纯浪费 flash。
     */
    private fun encryptInto(input: InputStream, target: File): Long {
        val nonce = crypto.newNonce()
        val cipher = crypto.encryptStart(nonce)
        var total = 0L

        FileOutputStream(target).use { fos ->
            val buffered = BufferedOutputStream(fos, 128 * 1024)
            // 头不参与加密：计数器是从密文第 0 字节开始算的，
            // 把头也塞进 cipher 会让偏移计算多一层换算，容易错。
            buffered.write(ByteArray(VaultFormat.HEADER_SIZE))
            val sink = CipherOutputStream(buffered, cipher)

            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sink.write(buf, 0, n)
                total += n
            }
            sink.flush()

            val header = java.nio.ByteBuffer.wrap(VaultFormat.newHeader(nonce, total))
            var pos = 0L
            while (header.hasRemaining()) {
                val written = fos.channel.write(header, pos)
                if (written <= 0) break
                pos += written
            }
        }
        return total
    }

    private fun readIndex(): Map<String, VaultEntry> {
        val f = File(dir, VaultFormat.INDEX_NAME)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val all = f.readBytes()
            if (all.size <= VaultFormat.HEADER_SIZE) return@runCatching emptyMap<String, VaultEntry>()
            val nonce = all.copyOfRange(
                VaultFormat.NONCE_OFFSET,
                VaultFormat.NONCE_OFFSET + VaultFormat.NONCE_SIZE,
            )
            val (cipher, _) = crypto.decryptAt(nonce, 0L)
            val plain = cipher.doFinal(all, VaultFormat.HEADER_SIZE, all.size - VaultFormat.HEADER_SIZE)
            val map = mutableMapOf<String, VaultEntry>()
            plain.toString(Charsets.UTF_8).lineSequence().forEach { line ->
                if (line.isEmpty()) return@forEach
                val parts = line.split('\t')
                if (parts.size < 4) return@forEach
                val id = parts[0]
                map[id] = VaultEntry(
                    id = id,
                    name = parts[1],
                    sizeBytes = parts[2].toLongOrNull() ?: 0L,
                    addedAtMs = parts[3].toLongOrNull() ?: 0L,
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun writeIndex(map: Map<String, VaultEntry>) {
        dir.mkdirs()
        val text = buildString {
            map.values.forEach { e ->
                // 名字里的制表和换行会破坏行格式，落盘前统一替换成空格。
                append(e.id).append('\t')
                append(e.name.replace('\t', ' ').replace('\n', ' ')).append('\t')
                append(e.sizeBytes).append('\t')
                append(e.addedAtMs).append('\n')
            }
        }
        val nonce = crypto.newNonce()
        val cipher = crypto.encryptStart(nonce)
        val body = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        File(dir, VaultFormat.INDEX_NAME)
            .writeBytes(VaultFormat.newHeader(nonce, body.size.toLong()) + body)
    }

    /** 只读打开一个加密文件，验证头并返回 (nonce, 明文长度)。 */
    internal fun openHeader(id: String): Triple<RandomAccessFile, ByteArray, Long> {
        val f = fileFor(id)
        if (!f.exists()) throw java.io.FileNotFoundException("加密文件不存在: $id")
        val raf = RandomAccessFile(f, "r")
        try {
            val header = ByteArray(VaultFormat.HEADER_SIZE)
            raf.readFully(header)
            if (!header.copyOfRange(0, 4).contentEquals(VaultFormat.MAGIC)) {
                throw java.io.IOException("不是加密空间的格式")
            }
            if (header[4] != VaultFormat.VERSION) {
                throw java.io.IOException("加密格式版本不支持: ${header[4]}")
            }
            val nonce = header.copyOfRange(
                VaultFormat.NONCE_OFFSET,
                VaultFormat.NONCE_OFFSET + VaultFormat.NONCE_SIZE,
            )
            val size = VaultFormat.readLongBe(header, VaultFormat.SIZE_OFFSET)
            return Triple(raf, nonce, size)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }
}