package com.thermo.decoder.container

/**
 * 纯字节的容器嗅探。
 *
 * 刻意做成**不依赖任何 Android API 的纯函数**，这样 JVM 单测可以
 * 直接喂各种文件头去穷举边界情况 —— 真机上造一个 AVI 文件可没有构造
 * 一个 ByteArray 方便。
 *
 * 判断依据全部来自各容器的官方魔数（magic number），不做任何启发式猜测：
 * 猜错容器会让上层走到"以为能硬解、结果起解码器才发现不行"的路径上，
 * 比直接说"不认识"糟糕得多。
 */
internal object ContainerSniffer {

    /** 嗅探结果：容器 + 附加信息（MP4 的 major brand / MKV 的 DocType）。 */
    data class Detected(val container: ContainerType, val detail: String? = null)

    fun detect(buffer: ByteArray, size: Int): Detected {
        if (size < 4) return Detected(ContainerType.UNKNOWN)
        // 把"实际读到的字节数"裁出来当作 head。
        // SNIFF 缓冲区是定长的（4096），文件比它短时尾部全是 0，
        // 而下面所有 ByteArray 扩展函数内部用的 `size` 是**数组长度** ——
        // 不裁就会把末尾的 0 当成文件内容，把短文件误判成带魔数的格式。
        val head = if (size >= buffer.size) buffer else buffer.copyOf(size)

        // ---- RIFF 家族：AVI / WAV / WebP 共用 "RIFF????" 头，靠偏移 8 的 FourCC 区分 ----
        // 必须最先判：AVI 的魔数只有这 12 个字节，后面全是 chunk，没有别的特征。
        if (head.matchAscii(0, "RIFF")) {
            return when {
                head.matchAscii(8, "AVI ") -> Detected(ContainerType.AVI, "RIFF/AVI")
                head.matchAscii(8, "WAVE") -> Detected(ContainerType.WAV, "RIFF/WAVE")
                else -> Detected(ContainerType.UNKNOWN, "RIFF/" + head.ascii(8, 4))
            }
        }

        // ---- ISO BMFF（MP4 / MOV / 3GP）：偏移 4 是 "ftyp"，偏移 8 是 major brand ----
        if (head.matchAscii(4, "ftyp")) {
            val brand = head.ascii(8, 4)
            val container = if (brand == "qt  ") ContainerType.MOV else ContainerType.MP4
            return Detected(container, brand)
        }

        // ---- Matroska / WebM：EBML 头 1A 45 DF A3 ----
        // 两者共用同一个容器格式，只靠 DocType 字符串区分（"webm" vs "matroska"）。
        if (head.matchBytes(0, EBML_MAGIC)) {
            val docType = head.findAscii("webm") ?: head.findAscii("matroska")
            val container = if (docType == "webm") ContainerType.WEBM else ContainerType.MKV
            return Detected(container, docType)
        }

        // ---- MPEG-TS：包长固定 188，同步字节 0x47 ----
        // 单看第一个字节 0x47 会误判（任何二进制都可能以 0x47 开头），
        // 必须验第二个包的同步字节。这是 TS 嗅探的标准做法。
        if (head.size > 188 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) {
            return Detected(ContainerType.MPEG_TS, "188-byte packets")
        }

        // ---- FLV："FLV" + version(1) + flags(1) ----
        if (head.matchAscii(0, "FLV")) {
            return Detected(ContainerType.FLV, "v${head[3].toInt() and 0xFF}")
        }

        // ---- Ogg ----
        if (head.matchAscii(0, "OggS")) return Detected(ContainerType.OGG)

        // ---- FLAC ----
        if (head.matchAscii(0, "fLaC")) return Detected(ContainerType.FLAC)

        // ---- MP3：ID3 标签头，或裸 MPEG 音频帧同步字 0xFFEx ----
        // 这个判断放在后面：0xFF 开头的概率太高，前面能匹配上的都应该先匹配走。
        if (head.matchAscii(0, "ID3")) return Detected(ContainerType.MP3, "ID3")
        if (head.size >= 2 &&
            head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0
        ) {
            return Detected(ContainerType.MP3, "raw frame sync")
        }

        // ---- AMR ----
        if (head.matchAscii(0, "#!AMR")) return Detected(ContainerType.AMR)

        // ---- MPEG-PS：起始码 00 00 01 BA ----
        if (head.matchBytes(0, MPEG_PS_START_CODE)) {
            return Detected(ContainerType.MPEG_PS, "pack start code")
        }

        // ---- RealMedia：".RMF" ----
        if (head.matchAscii(0, ".RMF")) return Detected(ContainerType.REAL_MEDIA)

        // ---- ASF / WMV：16 字节 GUID ----
        if (head.matchBytes(0, ASF_GUID)) return Detected(ContainerType.ASF)

        return Detected(ContainerType.UNKNOWN)
    }

    private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val MPEG_PS_START_CODE = byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte())

    /** ASF 头的 16 字节 GUID，逐字节写出来比一串 \u 转义可读得多。 */
    private val ASF_GUID = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
    )

    private fun ByteArray.matchBytes(offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > size) return false
        for (i in needle.indices) if (this[offset + i] != needle[i]) return false
        return true
    }

    private fun ByteArray.matchAscii(offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > size) return false
        for (i in text.indices) {
            if (this[offset + i].toInt() and 0xFF != text[i].code) return false
        }
        return true
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        if (offset + length > size) return ""
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val b = this[offset + i].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        return sb.toString()
    }

    private fun ByteArray.findAscii(text: String): String? {
        if (text.isEmpty() || text.length > size) return null
        val needle = text.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return text
        }
        return null
    }
}