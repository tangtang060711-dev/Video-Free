package com.thermo.data.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 边读边解密的数据源。
 *
 * 它不把整个文件解到内存或临时文件 —— 那对 4K 长片等于把 flash 写穿。
 * 它做的是：把 ExoPlayer 的"读第 N 字节"直接翻译成"读密文第 N 字节并解密"，
 * 内存里始终只有几十 KB。
 *
 * 之所以能做到随机访问，全靠 CTR 的计数器可重算（见 VaultFormat 的说明）。
 */
class VaultDataSource(private val store: VaultStore) : BaseDataSource(false) {

    private var uri: Uri? = null
    private var raf: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var opened = false

    /** 明文剩余字节数（从当前读取位置算起）。 */
    private var remaining = 0L

    /** 首块需要丢弃的字节数（起点不在 16 字节边界上时非 0）。 */
    private var skip = 0

    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)

    /** 上一次多解出来的明文（只可能出现在文件末尾），下次 read 先吐它。 */
    private var pending = ByteArray(0)
    private var pendingPos = 0
    private var pendingLen = 0

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)

        val id = store.idFromUri(dataSpec.uri)
            ?: throw FileNotFoundException("不是加密空间的地址: ${dataSpec.uri}")

        // 顺序很重要：先确认解锁，再碰文件。
        // 反过来的话，未解锁时也会读一次文件头 —— 那属于"没解锁就动了密文"。
        store.requireUnlocked()

        val (file, nonce, plainSize) = store.openHeader(id)
        val pos = dataSpec.position
        if (pos > plainSize) {
            file.close()
            throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        }

        // 必须从 16 字节对齐处开始读：CTR 的计数器只能按块走。
        val aligned = pos / 16L * 16L
        val (c, s) = store.crypto.decryptAt(nonce, pos)
        file.seek(VaultFormat.HEADER_SIZE + aligned)

        raf = file
        cipher = c
        skip = s
        remaining = plainSize - pos
        uri = dataSpec.uri
        opened = true

        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT

        // 先把上次多解的字节发出去（只在文件末尾出现）
        if (pendingLen > 0) {
            val n = minOf(pendingLen, length)
            System.arraycopy(pending, pendingPos, buffer, offset, n)
            pendingPos += n
            pendingLen -= n
            remaining -= n
            bytesTransferred(n)
            return n
        }

        val f = raf ?: return C.RESULT_END_OF_INPUT
        val c = cipher ?: return C.RESULT_END_OF_INPUT

        val want = minOf(length.toLong(), remaining).toInt()
        val need = want + skip
        if (cipherBuf.size < need) cipherBuf = ByteArray(need)
        if (plainBuf.size < need) plainBuf = ByteArray(need)

        var got = 0
        while (got < need) {
            val n = f.read(cipherBuf, got, need - got)
            if (n < 0) break
            got += n
        }
        if (got <= skip) {
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }

        // CTR 是 1:1 的流密码，update 的输出长度恒等于输入长度。
        val produced = c.update(cipherBuf, 0, got, plainBuf, 0)
        val available = produced - skip
        if (available <= 0) {
            skip = 0
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }

        val n = minOf(available, want)
        System.arraycopy(plainBuf, skip, buffer, offset, n)
        if (available > n) {
            // 只在读到文件末尾、一次读不满时才会走到这里。
            if (pending.size < available - n) pending = ByteArray(available - n)
            System.arraycopy(plainBuf, skip + n, pending, 0, available - n)
            pendingPos = 0
            pendingLen = available - n
        }
        skip = 0
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } catch (_: IOException) {
            // 关不掉就算了，不外抛：close 抛异常会把清晰的播放错误盖成 IO 错误。
        } finally {
            raf = null
            cipher = null
            uri = null
            remaining = 0L
            skip = 0
            pendingPos = 0
            pendingLen = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** 加密空间的 DataSource 工厂。 */
@Singleton
class VaultDataSourceFactory @Inject constructor(
    private val store: VaultStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = VaultDataSource(store)
}