package com.thermo.player.internal

import android.view.Display
import android.view.Window
import androidx.media3.common.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 显示刷新率适配 —— 需求里"帧率匹配：读取视频帧率与屏幕刷新率，避免无必要插帧/强制 120Hz"的落地。
 *
 * ## 为什么这件事对发热影响很大
 * 红米 K80 Pro 是 120Hz 的 LTPO 屏。系统的默认"高刷策略"是：只要 App 在动，就切 120Hz。
 * 看视频时：
 *   - 显示子系统（DDIC + 面板 + 合成）的功耗基本上与刷新率**成正比**；
 *   - 120Hz 相对 60Hz，屏幕这一路就要多吃 100~200mW；
 *   - 而视频本身如果是 24/30fps，120Hz 并不会让画面更顺 —— 除非系统做了 MEMC 插帧，
 *     而插帧是**额外**的算力开销（这正是"无必要插帧"要避免的）。
 *
 * ## 策略：挑"刷新率是视频帧率整数倍"里最低的那一档
 * 24fps 的片子：
 *   - 120Hz = 5×24 → 每一帧显示 5 次，均匀，没有 judder；
 *   - 60Hz  = 2.5×24 → 出现 2/3 交替，是肉眼可见的抖动（3:2 pulldown 问题）。
 * 所以 24fps 该配 120Hz，不该无脑锁 60Hz。
 * 30fps 的片子：
 *   - 60Hz  = 2×30 → 均匀，且比 120Hz 省一半显示功耗；
 *   - 120Hz = 4×30 → 也均匀，但纯浪费。
 * 所以规则不是"锁 60"，而是"**在整数倍里取最低**"。
 *
 * 拿不到视频帧率时（还没解出首帧）不切模式，保持系统现状 —— 不猜。
 *
 * ## 与 Media3 的分工
 * Media3 的 `setVideoChangeFrameRateStrategy(ONLY_IF_SEAMLESS)` 走的是
 * `Surface.setFrameRate()`（API 30+），它告诉**系统**"这块 Surface 上是 24fps"，
 * 由系统决定怎么处理。我们这里做的是**更硬的一层**：直接把 Window 的
 * `preferredDisplayModeId` 钉死在某个具体显示模式上，防止系统为了别的原因把屏幕拉到 120Hz。
 * 两者叠加才是完整的"既匹配又不乱跳"。
 */
@Singleton
class DisplayRefreshRateAdapter @Inject constructor() {

    /** 最近一次实际应用的模式，退出播放页时要还原。 */
    private var appliedModeId: Int = 0
    private var appliedOnWindow: Int = 0

    /**
     * 依据视频帧率挑一个显示模式。
     *
     * @param modes             `Display.getSupportedModes()` 的结果
     * @param videoFps          视频帧率；<= 0 表示未知
     * @param allowHighRefresh  是否允许 90/120Hz 这类高刷。省电模式下传 false。
     * @return 应该使用的模式；null 表示"不要干预，保持系统现状"
     */
    fun pickBestMode(
        modes: List<Display.Mode>,
        videoFps: Float,
        allowHighRefresh: Boolean,
    ): Display.Mode? {
        val usable = modes.filter { it.refreshRate > 0f }
        if (usable.isEmpty()) return null

        // 帧率未知：不动。宁可维持现状，也不要在起播的瞬间闪一下黑屏切刷新率。
        if (videoFps <= 0f) return null

        val pool = if (allowHighRefresh) {
            usable
        } else {
            usable.filter { it.refreshRate <= HIGH_REFRESH_CUTOFF_HZ }.ifEmpty { usable }
        }

        // 打分：先把"非整数倍"的惩罚掉，再在同样均匀的候选里挑刷新率最低的。
        var best: Display.Mode? = null
        var bestScore = Float.MAX_VALUE
        for (mode in pool) {
            val ratio = mode.refreshRate / videoFps
            val nearestInt = Math.round(ratio).coerceAtLeast(1)
            // 与最近整数倍的相对偏差。0 表示完全均匀。
            val judder = kotlin.math.abs(ratio - nearestInt) / nearestInt
            // 均匀性优先（权重 10），其次才是刷新率高低（归一化到 120Hz）。
            val score = judder * 10f + (mode.refreshRate / 120f)
            if (score < bestScore - 1e-4f) {
                bestScore = score
                best = mode
            }
        }
        return best
    }

    /**
     * 把挑好的模式钉到 Window 上。
     *
     * @return 实际生效的模式；null 表示没有改动
     */
    fun apply(window: Window, videoFps: Float, allowHighRefresh: Boolean): Display.Mode? {
        val display = currentDisplay(window) ?: return null
        val modes = runCatching { display.supportedModes.toList() }.getOrNull()
        if (modes.isNullOrEmpty()) return null

        val target = pickBestMode(modes, videoFps, allowHighRefresh) ?: return null

        // 已经是这个模式了就别再 setAttributes —— 每次设置都会触发一次
        // WindowManager 的 relayout，本身就有开销，还可能引起一次轻微的闪帧。
        if (appliedModeId == target.modeId && appliedOnWindow == System.identityHashCode(window)) {
            return target
        }

        return runCatching {
            val attrs = window.attributes
            attrs.preferredDisplayModeId = target.modeId
            window.attributes = attrs
            appliedModeId = target.modeId
            appliedOnWindow = System.identityHashCode(window)
            Log.i(
                TAG,
                "显示模式 -> ${target.modeId} @ ${"%.2f".format(target.refreshRate)}Hz " +
                    "(视频 ${"%.2f".format(videoFps)}fps, 高刷${if (allowHighRefresh) "允许" else "禁止"})",
            )
            target
        }.getOrElse { e ->
            // 部分 ROM 会直接拒绝这个字段（澎湃 OS 有自己的高刷白名单）。
            // 这不是错误，Media3 的 Surface#setFrameRate 那一层还在生效，静默即可。
            Log.w(TAG, "设置 preferredDisplayModeId 被拒绝，回落到 Media3 的 setFrameRate", e)
            null
        }
    }

    /** 退出播放页 / 暂停时还原成"系统自己决定"。 */
    fun reset(window: Window) {
        if (appliedModeId == 0) return
        runCatching {
            val attrs = window.attributes
            attrs.preferredDisplayModeId = 0   // 0 = 无偏好
            window.attributes = attrs
        }
        appliedModeId = 0
        appliedOnWindow = 0
    }

    /** 当前屏幕刷新率，仅用于诊断面板显示。 */
    fun currentRefreshRate(window: Window): Float =
        runCatching { currentDisplay(window)?.refreshRate ?: 0f }.getOrDefault(0f)

    @Suppress("DEPRECATION")
    private fun currentDisplay(window: Window): Display? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.display ?: window.context.display
        } else {
            window.windowManager.defaultDisplay
        }
    }.getOrNull()

    private companion object {
        const val TAG = "ThermoDisplay"
        /** 高于这个刷新率的模式算"高刷"，省电模式下会被排除。60Hz 面板正好卡在边界上。 */
        const val HIGH_REFRESH_CUTOFF_HZ = 61f
    }
}