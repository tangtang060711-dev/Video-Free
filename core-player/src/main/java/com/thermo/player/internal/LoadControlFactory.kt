package com.thermo.player.internal

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import com.thermo.player.PlaybackScenario
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按场景造 LoadControl。
 *
 * ## 这一块是"低发热"里最容易被忽视、但收益最直接的
 * Media3 的 DefaultLoadControl 默认值是为"网络视频尽量不卡"设计的：
 *   - 最大缓冲 50 秒
 *   - targetBufferBytes 默认 100MB 级别（按 1/8 分辨率帧大小估算，实际可能更大）
 * 也就是说，一个 4K 片源在默认配置下会用掉几百 MB 内存来"提前下载 50 秒"。
 * 内存不是白来的：分配、填充、GC 全都要 CPU；而预加载到的数据如果用户根本没看，
 * 那部分网络 + 内存 + 解密（HTTPS）功耗是 100% 的净浪费。
 *
 * 用户的痛点是"夸克播放发热严重"，而市面上大多数播放器**从没动过 LoadControl**。
 * 这里把每一档都压到"够用就好"的量级。
 *
 * ## 关于 [scale]（温度降级第 1 级真正落地的地方）
 * ExoPlayer 的 LoadControl **在 build 之后就改不了**（缓冲队列按它分配）。
 * 所以温度降级不能当场把缓冲砍小 —— 那会把已经在队列里的数据 flush 掉，
 * 表现为"一降级就卡一下"，比发热更难受。
 * 正确做法是：把缩放因子存下来，**下一次重建播放器时**生效。
 * 于是 [com.thermo.app.ThermalPolicyCoordinator] 在 WARM 档只做两件事：
 *   1) 调 `setPreloadScale(0.35f)`（下一次打开生效）；
 *   2) 立刻做那些**当场就能生效**的事：限码率、降刷新率。
 * 这样用户看到的画面不会抖，但功耗曲线会在几秒内明显下来。
 *
 * ## 教训：DefaultLoadControl 的构造参数有硬性大小关系，违反直接崩
 * Media3 在 `setBufferDurationsMs*` 里逐条 assert，任一条不满足就是
 * `IllegalArgumentException`（不是 logcat 警告，是当场杀进程）：
 *
 *   maxBufferMs                    >= minBufferMs
 *   minBufferMs                    >= bufferForPlaybackMs
 *   minBufferMs                    >= bufferForPlaybackAfterRebufferMs   <-- 最难想到的一条
 *
 * 第三条尤其反直觉：重启阈值看起来该受"最大缓冲"约束，实际被"最小缓冲"约束。
 * 本文件第一版就在这里翻了车 —— LOCAL/LIVE 两档 min=1000ms 而重启阈值 2000ms，
 * 真机上第一次打开本地视频就闪退，栈顶是
 * `minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs`。
 * 现在所有档位一律走 [durations] 出口，由它统一 coerce，调用点不可能绕过。
 *
 * @see PlaybackScenario
 */
@Singleton
class LoadControlFactory @Inject constructor() {

    /**
     * @param scenario 播放场景，决定基准缓冲深度
     * @param scale    预加载预算缩放，1.0 = 正常，0.25 = 最小（下限）
     */
    fun create(scenario: PlaybackScenario, scale: Float = 1f): LoadControl {
        val s = scale.coerceIn(MIN_SCALE, 1f)
        val builder = DefaultLoadControl.Builder()

        when (scenario) {
            // ---- 本地文件 ----
            // 本地读的瓶颈只有存储带宽，抖动来源是 GC 和调度，1~4 秒足够扛过去。
            // 存储本身不会因为"提前读"而变快，深缓冲在这里没有任何收益，只有内存和功耗代价。
            PlaybackScenario.LOCAL -> {
                val d = durations(1_200, 4_000, 300, 800, s)
                builder.setBufferDurationsMsForLocalPlayback(d[0], d[1], d[2], d[3])
                builder.setTargetBufferBytes(scale(16 * 1024 * 1024, s, 3 * 1024 * 1024))
                builder.setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
                // 回退缓冲 5 秒：允许用户往回拖，但不再往前囤。
                builder.setBackBuffer(scale(5_000, s, 1_000), /* retainBackBufferFromKeyframe = */ false)
            }

            // ---- 网络点播 ----
            // 这是最纠结的一档：缓冲太浅会被网络抖动打穿（一卡一卡），
            // 太深就是上面说的纯浪费。15/40 秒是"地铁、WiFi 边缘"场景下的经验折中。
            PlaybackScenario.STREAM -> {
                val d = durations(15_000, 40_000, 1_500, 5_000, s)
                builder.setBufferDurationsMsForStreaming(d[0], d[1], d[2], d[3])
                builder.setTargetBufferBytes(scale(32 * 1024 * 1024, s, 6 * 1024 * 1024))
                // 时间优先：允许为了"多囤 5 秒"而突破 32MB 一点点，
                // 避免高码率片源因为内存阈值而频繁停在 minBuffer 附近来回拉流。
                builder.setPrioritizeTimeOverSizeThresholdsForStreaming(true)
                builder.setBackBuffer(scale(10_000, s, 2_000), false)
            }

            // ---- 直播 ----
            // 直播的第一约束是**延迟**不是卡顿：缓冲越深，画面离主播越远。
            // 而且直播码率通常已经很低（2~6Mbps），40 秒也就 20MB，深缓冲连存储都省不了多少。
            // 这里把目标定在"3 秒内起播、8 秒内稳住"。
            PlaybackScenario.LIVE -> {
                val d = durations(1_200, 8_000, 400, 800, s)
                builder.setBufferDurationsMs(d[0], d[1], d[2], d[3])
                builder.setTargetBufferBytes(scale(8 * 1024 * 1024, s, 2 * 1024 * 1024))
                builder.setPrioritizeTimeOverSizeThresholds(true)
                // 直播不回退：往回拖就是"看回放"，那要另外走一套 seek 逻辑，
                // 在直播场景白占内存，直接关掉。
                builder.setBackBuffer(0, false)
            }
        }

        return builder.build()
    }

    private companion object {
        /**
         * 下限 0.25。
         * 再小就没意义了：本地 4 秒缓冲砍到 1 秒以下，只要主线程被
         * MediaCodec 的 output buffer 回调堵一下（这在软解时很常见），
         * 就会卡顿 —— 而卡顿会让用户反复拖进度条，反而更费电。
         * "降级"的目标是让功耗曲线下来，不是把体验打碎。
         */
        const val MIN_SCALE = 0.25f

        /** minBufferMs 的绝对下限。别让它小到比一帧的调度抖动还短。 */
        const val MIN_BUFFER_FLOOR_MS = 300

        /** 通用的"缩放 + 下限"。用于 targetBufferBytes / backBuffer 这类没有大小关系的参数。 */
        fun scale(value: Int, scale: Float, floor: Int): Int =
            (value * scale).toInt().coerceAtLeast(floor)

        /**
         * 算出四个缓冲时长，并**强制满足 DefaultLoadControl 的三条断言**。
         * 返回 [minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs]。
         *
         * 先算 min，再把后两个都 coerceIn(0, min) —— 顺序不能反，
         * 因为 clamp 的上界就是 min 本身；最后用 coerceAtLeast(min + 1) 顶住 max。
         * 这样即便 [scale] 取到 0.25 甚至被传入非法值，四个数的关系也永远合法。
         */
        fun durations(minBase: Int, maxBase: Int, playbackBase: Int, afterBase: Int, s: Float): IntArray {
            val min = (minBase * s).toInt().coerceAtLeast(MIN_BUFFER_FLOOR_MS)
            val playback = (playbackBase * s).toInt().coerceIn(0, min)
            val afterRebuffer = (afterBase * s).toInt().coerceIn(0, min)
            val max = (maxBase * s).toInt().coerceAtLeast(min + 1)
            return intArrayOf(min, max, playback, afterRebuffer)
        }
    }
}