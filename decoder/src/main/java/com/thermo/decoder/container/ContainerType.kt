package com.thermo.decoder.container

/**
 * 容器类型，以及**Media3 1.9.4 到底认不认它**。
 *
 * 这张表是整个 :decoder 模块存在的理由。
 * 用户要求的格式清单里有 MP4/MKV/**AVI**/MOV/FLV/TS，
 * 而 Media3 的默认 extractor 集合里**没有 RIFF/AVI 解析器**。
 * 这意味着 AVI 文件交给 DefaultMediaSourceFactory 会直接
 * 报 "UnrecognizedInputFormatException"，而不是"能播但很慢"。
 *
 * 不把这层写清楚，上层就只能靠"试一下、报错再换"来探测 ——
 * 那是黑盒，用户会先看到一个转圈，然后看到一个错误。
 * 提前嗅探出容器类型，可以在**打开播放页之前**就决定
 * "能不能硬解""要不要提示不支持"。
 *
 * @param label           给用户看的名字
 * @param media3CanExtract Media3 1.9.4 的默认 extractor 集合能否解析它。
 *                         **这一列才是真正决定"能不能播"的**；
 *                         至于里面装的是什么编码、有没有硬解，
 *                         交给 [com.thermo.decoder.codec.VideoCodecProbe] 去实测。
 */
enum class ContainerType(
    val label: String,
    val media3CanExtract: Boolean,
) {
    MP4("MP4", true),
    MOV("QuickTime MOV", true),
    MKV("Matroska MKV", true),
    WEBM("WebM", true),
    MPEG_TS("MPEG-TS", true),
    FLV("FLV", true),
    OGG("Ogg", true),
    FLAC("FLAC", true),
    WAV("WAV", true),
    MP3("MP3", true),
    AMR("AMR", true),

    /**
     * AVI。**Media3 不支持**。
     *
     * AVI 里几乎总是装着 MPEG-4 Part 2（也就是 Xvid/DivX）或 MPEG-2 视频。
     * 骁龙 8 Elite 的硬解列表里这两种都不存在（它只认 H.264/HEVC/VP9/AV1），
     * 所以就算补上 extractor，也必然落到软解。
     * 因此 :decoder 对 AVI 的策略是：**明确告知上层"这条片子只能软解"**，
     * 由 :app 决定是拒绝播放、还是降分辨率软解。
     */
    AVI("AVI", false),

    /** MPEG-PS（老式 .mpg/.vob）。Media3 也没有这个 extractor。 */
    MPEG_PS("MPEG-PS", false),

    /** RealMedia。无 extractor、无硬解，纯软解。 */
    REAL_MEDIA("RealMedia", false),

    /** ASF/WMV。无 extractor、无硬解。 */
    ASF("ASF/WMV", false),

    UNKNOWN("未知", false),
    ;

    /**
     * 这个容器是不是"打开就是死路"。
     * UI 用它决定要不要在播放页上直接显示一条提示，而不是等 ExoPlayer 报错。
     */
    val isUnplayableByMedia3: Boolean get() = this != UNKNOWN && !media3CanExtract

    companion object {
        /**
         * 用户要求的六个格式里，哪些真的能播。
         * 放在这里是为了让 :app 的自检面板能直接展示，不用再抄一遍。
         */
        val REQUIREMENT_COVERAGE: List<Pair<String, Boolean>> = listOf(
            "MP4" to true,
            "MKV" to true,
            "MOV" to true,
            "FLV" to true,
            "TS" to true,
            "AVI" to false,
        )
    }
}