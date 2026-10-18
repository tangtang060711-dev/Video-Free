package com.thermo.data.vault

/**
 * 加密空间里的一条视频。
 *
 * [name] 是**明文名字**，它本身也存在加密索引里（见 VaultStore），
 * 不写进文件名 —— 否则文件管理器里一眼就能看到"某某视频.tvlt"。
 */
data class VaultEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
)

/**
 * 加密文件的磁盘布局。
 *
 * ```
 *  0..3    magic  "TVLT"
 *  4       version
 *  5..12   nonce      8 字节
 * 13..20   plainSize  8 字节（大端）
 * 21..     AES-256-CTR 密文
 * ```
 *
 * ## 为什么是 CTR 而不是 CBC / GCM
 * **只有 CTR 能随机访问。** CBC 必须从头解到目标位置才能继续；
 * GCM 是 AEAD，校验整段就必须读完整段。两者都做不到"拖进度条直接跳到两小时后"。
 * CTR 是流密码，明文第 N 字节恒对应密文第 N 字节，跳转只需重算计数器。
 *
 * 代价是没有完整性校验。对"防别人翻看"这个目标够用；我们不打算在这里做防篡改
 * （那要额外挂 HMAC，代价是每次打开都要全文件扫一遍 —— 对播放器是不可接受的）。
 */
internal object VaultFormat {

    /** "TVLT" */
    val MAGIC = byteArrayOf(0x54, 0x56, 0x4C, 0x54)

    const val VERSION: Byte = 1

    const val NONCE_OFFSET = 5
    const val NONCE_SIZE = 8
    const val SIZE_OFFSET = 13
    const val HEADER_SIZE = 21

    /** 加密文件后缀。名字里不带任何原始信息。 */
    const val EXT = ".tvlt"

    /** 自定义 URI scheme：`vault://<id>`。 */
    const val SCHEME = "vault"

    /** 索引文件名（本身也是加密的）。 */
    const val INDEX_NAME = "index.bin"

    fun writeLongBe(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = (v ushr (56 - i * 8)).toByte()
    }

    fun readLongBe(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[off + i].toLong() and 0xFFL)
        return v
    }

    /** 拼一个空头（非头字节为 0），调用方再往里填。 */
    fun newHeader(nonce: ByteArray, plainSize: Long): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, h, 0, 4)
        h[4] = VERSION
        System.arraycopy(nonce, 0, h, NONCE_OFFSET, NONCE_SIZE)
        writeLongBe(h, SIZE_OFFSET, plainSize)
        return h
    }
}