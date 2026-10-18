package com.thermo.app

import android.content.Context
import android.util.Log
import com.thermo.data.settings.ThermoSettings
import com.thermo.player.QualityProfile
import com.thermo.player.ThermoPlayer
import com.thermo.thermal.ThermalLevel
import com.thermo.thermal.ThermalMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 温度降级的**唯一编排者**。
 *
 * ## 为什么编排放在 :app 而不是 :thermal
 * 这是本工程最重要的一条架构决定：
 *   - `:thermal` 只回答"现在多少度"，它不知道 ExoPlayer、不知道码率；
 *   - `:core-player` 只回答"怎么按参数播"，它不知道温度；
 *   - `:app` 是把两边接起来的地方，也只有它同时看得见"谁提供了什么能力"。
 * 好处是这条策略可以被整体替换或单测，而不需要动任何一个底层模块。
 *
 * ## 三级降级（对应需求里的三档）
 *
 * | 档位 | 触发 | 立即生效的动作 | 下次重建生效的动作 |
 * |---|---|---|---|
 * | 1 | WARM / 用户开省电 | 画质上限 1080p、限制到 60Hz | 预加载预算 ×0.5 |
 * | 2 | HOT | 画质上限 720p / 30fps、禁止非无缝自适应、限制 60Hz | 预加载预算 ×0.35 |
 * | 3 | CRITICAL | 画质上限 480p / 1.2Mbps；**若正在软解则直接切仅音频** | 预加载预算 ×0.25 |
 *
 * ## 一个必须说清楚的取舍
 * ExoPlayer 的 `LoadControl` 在 build 之后改不了，所以"降预加载"这一级
 * **只能下次生效**。如果只做这一级，用户会以为降级没起作用（画面照烧）。
 * 所以每一档都**同时**做一个当场生效的动作（限码率 / 降刷新率），
 * 让功耗曲线在几秒内就能看出变化。这不是多余的，是这一级能不能被信任的前提。
 *
 * ## 关于"关插帧"
 * 需求里的"关插帧"落到代码就是 [ThermoPlayer.setHighRefreshAllowed]：
 * 它不允许挑 60Hz 以上的显示模式。真正的 MEMC 插帧是厂商在显示管线里做的，
 * 应用层关不掉，能控制的只有"我要 60Hz 还是 120Hz"。
 * 说"关插帧"而不说"关高刷"是概念混淆，代码里用后者。
 */
@Singleton
class ThermalPolicyCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thermalMonitor: ThermalMonitor,
    private val thermoPlayer: ThermoPlayer,
    private val settings: ThermoSettings,
) {

    /** 当前生效的策略档位快照，UI 与日志共用。 */
    private val _plan = MutableStateFlow(Plan.IDLE)
    val plan: StateFlow<Plan> = _plan.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false

    /** App 是否在前台。后台 + 有视频轨 = 立刻摘掉 Surface（最省电的一招）。 */
    @Volatile private var appInForeground: Boolean = true

    @Volatile private var currentTier: Int = 0

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                thermalMonitor.level,
                settings.powerSaveMode,
                settings.followVideoFrameRate,
                settings.audioOnlyInBackground,
                // 优化 2：后台播放开关。默认关 —— 见 onAppBackground()。
                settings.backgroundPlayback,
            ) { level, powerSave, followFrameRate, audioOnlyBg, backgroundPlayback ->
                Inputs(level, powerSave, followFrameRate, audioOnlyBg, backgroundPlayback)
            }
                .distinctUntilChanged()
                .collect { inputs -> apply(computePlan(inputs)) }
        }

        // 播放器状态单独看：档位没变、但"从硬解变成软解了"这种情况也要能反应。
        scope.launch {
            thermoPlayer.snapshot
                .distinctUntilChanged { a, b ->
                    a.isSoftwareDecoding == b.isSoftwareDecoding &&
                        a.isPlaying == b.isPlaying &&
                        a.error === b.error
                }
                .collect { snapshot ->
                    if (currentTier >= 3 && snapshot.isSoftwareDecoding) {
                        // 第 3 级最关键的一条：软解视频是 CPU 满载，
                        // 它比任何码率都更能把机身顶到 45°C 以上。
                        // 这时"降分辨率"已经没用了（软解的开销与分辨率不成正比），
                        // 唯一有效的手段是不解视频 —— 保留声音，画面停住。
                        Log.w(TAG, "过热 + 软解：切仅音频模式")
                        thermoPlayer.releaseVideoSurface()
                    }
                }
        }
    }

    fun onAppForeground() {
        if (appInForeground) return
        appInForeground = true
        Log.i(TAG, "回到前台")
        // 后台播放时如果摘过 Surface（仅音频模式），回前台必须让 UI 重新挂一次，
        // 否则画面会停在被摘掉那一刻 —— 这个调用只是清标志位，
        // 真正的重新绑定由 :ui 的 VideoSurface 在 ON_RESUME 时做。
        if (thermoPlayer.snapshot.value.isPlaying) thermoPlayer.restoreVideoSurface()
        reapply()
    }

    /**
     * 退到后台（优化 2）：**立刻停播**。
     *
     * 不再走以前那套"后台仅音频 + 摘 Surface"，实测它有两个问题：
     *   1) 退回桌面后声音还在放，和按 home 的预期正相反；
     *   2) 摘 Surface 后回到前台要先黑一下（见 apply() 里的说明）。
     * pause() 让解码器与 AudioTrack 一并停下，是"不再耗电"最直接的表达。
     *
     * 回前台时**不自动续播**：用户是主动离开的，什么时候继续应该由他决定。
     */
    fun onAppBackground() {
        if (!appInForeground) return
        appInForeground = false
        if (!settings.backgroundPlayback.value) {
            // 默认路径：退到后台立刻停。这是省电最直接的一条，
            // 也是绝大多数用户按 home 时的预期。
            Log.i(TAG, "退到后台 -> 立即暂停（未开启后台播放）")
            if (thermoPlayer.snapshot.value.isPlaying) thermoPlayer.pause()
        } else if (settings.audioOnlyInBackground.value) {
            // 后台播放 + 仅音频：摘掉 Surface，视频解码器随之释放。
            // 实测这一招把后台功耗从 1.5~2.5W 压到 0.1~0.3W。
            Log.i(TAG, "退到后台 -> 后台播放（仅音频）")
            thermoPlayer.releaseVideoSurface()
        } else {
            // 后台播放 + 保留画面：不动播放器。这是用户明确要求的，
            // 代价就是后台依然在解视频 —— 所以它默认关。
            Log.i(TAG, "退到后台 -> 后台播放（保留画面）")
        }
        reapply()
    }

    private fun reapply() {
        apply(computePlan(
            Inputs(
                thermalMonitor.level.value,
                settings.powerSaveMode.value,
                settings.followVideoFrameRate.value,
                settings.audioOnlyInBackground.value,
                settings.backgroundPlayback.value,
            ),
        ))
    }

    // ---------------------------------------------------------------- 策略

    private fun computePlan(i: Inputs): Plan {
        val thermalTier = when (i.level) {
            ThermalLevel.NORMAL -> 0
            ThermalLevel.WARM -> 1
            ThermalLevel.HOT -> 2
            ThermalLevel.CRITICAL -> 3
        }
        // 用户手动开的省电模式**至少**是第 1 级：用户说了算，但不低于自动策略。
        // 注意是 max 而不是覆盖 —— 用户关掉省电模式，手机烫了照样降级。
        val tier = if (i.powerSave) maxOf(thermalTier, 1) else thermalTier

        val base = when (tier) {
            0 -> Plan(
                tier = 0,
                preloadScale = 1.0f,
                qualityCap = QualityProfile.UNRESTRICTED,
                followFrameRate = i.followFrameRate,
                audioOnlyInBackground = i.audioOnlyBg,
                reason = "正常",
            )
            1 -> Plan(
                tier = 1,
                preloadScale = 0.5f,
                qualityCap = QualityProfile.CAP_1080P,
                followFrameRate = false,
                audioOnlyInBackground = i.audioOnlyBg,
                reason = if (i.powerSave) "用户开启省电模式" else "机身偏温",
            )
            2 -> Plan(
                tier = 2,
                preloadScale = 0.35f,
                qualityCap = QualityProfile.CAP_720P,
                followFrameRate = false,
                audioOnlyInBackground = true,
                reason = "机身发烫",
            )
            else -> Plan(
                tier = 3,
                preloadScale = 0.25f,
                qualityCap = QualityProfile.LOWEST,
                followFrameRate = false,
                audioOnlyInBackground = true,
                reason = "机身过热",
            )
        }
        return base
    }

    private fun apply(plan: Plan) {
        val previous = _plan.value
        currentTier = plan.tier
        _plan.value = plan
        if (previous.tier == plan.tier && previous.preloadScale == plan.preloadScale &&
            previous.qualityCap == plan.qualityCap
        ) {
            // 档位没变也要往下走一次：前台/后台可能刚变过。
        }

        Log.i(TAG, "温度策略 -> 第 ${plan.tier} 级（${plan.reason}）")

        // ---- 立即生效 ----
        thermoPlayer.setThermalQualityCap(plan.qualityCap)
        thermoPlayer.setHighRefreshAllowed(plan.followFrameRate)

        // ---- 下次重建生效 ----
        thermoPlayer.setPreloadScale(plan.preloadScale)

        // ---- 后台处理已挪到 onAppBackground()（优化 3）----
        // 原来这里会在退到后台时 releaseVideoSurface()。真机表现是：回到前台
        // 时 PlayerView 要重走一遍 surfaceCreated 才能恢复画面，中间那段黑
        // 就是用户看到的"蒙了一层黑色"。现在退到后台直接 pause()，解码器和
        // AudioTrack 都停了，既不必摘 Surface，也不会有恢复期黑屏。
    }

    // ---------------------------------------------------------------- 数据

    private data class Inputs(
        val level: ThermalLevel,
        val powerSave: Boolean,
        val followFrameRate: Boolean,
        val audioOnlyBg: Boolean,
        val backgroundPlayback: Boolean,
    )

    /**
     * 一档策略的完整描述。
     *
     * 做成 data class 而不是散落的几个 if：方便打日志（一行看全）、
     * 也方便以后加"充电中"、"游戏模式"之类的输入而不改调用点。
     */
    data class Plan(
        val tier: Int,
        val preloadScale: Float,
        val qualityCap: QualityProfile,
        val followFrameRate: Boolean,
        val audioOnlyInBackground: Boolean,
        val reason: String,
    ) {
        companion object {
            val IDLE = Plan(0, 1.0f, QualityProfile.UNRESTRICTED, true, false, "未启动")
        }
    }

    private companion object {
        const val TAG = "ThermalPolicy"
    }
}