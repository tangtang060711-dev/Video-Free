package com.thermo.player

/**
 * 对 TrackSelector 下发的画质约束。
 *
 * 这里**不做档位枚举**（什么"流畅/标清/高清/超清"），
 * 因为那些是产品话术，映射到具体数值时每个 App 都不一样。
 * 内核只认数值，由 :app 的 ThermalPolicyCoordinator 决定"烫了要降到多少"。
 *
 * 三个数值字段都是"上限"，不是"目标值"：
 * Media3 的 DefaultTrackSelector 会在满足约束的轨道里挑**最高**的那条。
 * 所以填 0 表示"不限制"，而不是"选 0"。
 *
 * @param maxWidth        单边最大宽度，0 = 不限
 * @param maxHeight       单边最大高度，0 = 不限
 * @param maxBitrate      最大码率（bps），0 = 不限。这是省电最有效的一根杠杆，
 *                        因为它直接线性地压低解码器与显示子系统的功耗
 * @param maxFrameRate    最大帧率，0 = 不限。降到 30fps 能让 8 Elite 的
 *                        大核调度明显收敛
 * @param allowNonSeamless 是否允许"非无缝"的自适应切换。关掉它 = 同一时间
 *                        只解码一条轨道，切换时会有一次黑屏，但**发热显著更低**
 */
data class QualityProfile(
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxBitrate: Int = 0,
    val maxFrameRate: Int = 0,
    val allowNonSeamless: Boolean = true,
) {
    /**
     * 合并两个约束，取更严的一侧。
     * 用于"用户手动选了画质" 和 "系统因为发热要降级" 同时生效的场景 ——
     * 这种时候必须以更严的那个为准，否则降级会被用户设置悄悄绕过。
     */
    fun intersect(other: QualityProfile): QualityProfile = QualityProfile(
        maxWidth = minOfNonZero(maxWidth, other.maxWidth),
        maxHeight = minOfNonZero(maxHeight, other.maxHeight),
        maxBitrate = minOfNonZero(maxBitrate, other.maxBitrate),
        maxFrameRate = minOfNonZero(maxFrameRate, other.maxFrameRate),
        allowNonSeamless = allowNonSeamless && other.allowNonSeamless,
    )

    /** 是否比 [other] 更严（或相等）。诊断面板用它显示"降级是否真的生效"。 */
    fun isAtLeastAsStrictAs(other: QualityProfile): Boolean = this == intersect(other)

    companion object {
        /** 完全不约束：能解什么就解什么。 */
        val UNRESTRICTED = QualityProfile()

        /**
         * 1080p 封顶。绝大多数手机屏幕在 6.7" 上人眼已分不出 1080p 与 4K 的差别，
         * 但 4K 解码的功耗是 1080p 的三倍以上 —— 这是性价比最高的一刀。
         */
        val CAP_1080P = QualityProfile(
            maxWidth = 1920,
            maxHeight = 1080,
            maxBitrate = 8_000_000,
        )

        /** 720p 封顶 + 限帧率，用于 HOT 档。 */
        val CAP_720P = QualityProfile(
            maxWidth = 1280,
            maxHeight = 720,
            maxBitrate = 3_000_000,
            maxFrameRate = 30,
        )

        /** 最低画质，用于 CRITICAL 档：只保证"有画面可看"。 */
        val LOWEST = QualityProfile(
            maxWidth = 854,
            maxHeight = 480,
            maxBitrate = 1_200_000,
            maxFrameRate = 30,
            allowNonSeamless = false,
        )

        /** 0 代表"不限制"，所以不能直接 minOf —— minOf(0, 1280) 会错误地取 0。 */
        private fun minOfNonZero(a: Int, b: Int): Int = when {
            a == 0 -> b
            b == 0 -> a
            else -> minOf(a, b)
        }
    }
}