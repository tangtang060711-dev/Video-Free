package com.thermo.thermal

import android.content.Context
import android.os.PowerManager
import com.thermo.thermal.di.DemoteHoldMs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把所有温度来源汇聚成一条带**迟滞**的等级流。
 *
 * 三个设计决定值得说明：
 *
 * 1) **多源取最严**：任一来源报 HOT 就是 HOT。
 *    各厂商对"哪条通道可信"的处理不一样，取最严是唯一安全的合并方式。
 *
 * 2) **升温立即生效、降温需要持续**（见 ThermalHysteresis）。
 *    不是为了让曲线好看，而是降温抖动的代价很高：
 *    每次级别回落都会让 TrackSelectionParameters 重算，
 *    极端情况下触发 MediaCodec 重建，那一下的功耗尖峰比省下来的还多。
 *
 * 3) **WhileSubscribed(5s)**：前台有页面订阅时才真正挂着监听；
 *    全部退订 5 秒后自动摘掉 PowerManager listener 和 BroadcastReceiver。
 *    这满足 ThermalSource 接口里"无人订阅必须释放底层监听"的约定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    sources: Set<@JvmSuppressWildcards ThermalSource>,
    /** 降温确认时长。低于这个时长的回落一律视为抖动。 */
    @DemoteHoldMs private val demoteHoldMs: Long,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val initialReading = ThermalReading(
        level = ThermalLevel.NORMAL,
        source = "initial",
        timestampMs = 0L,
    )

    /** 最近一次原始采样，仅用于诊断面板显示"是哪条通道在报"。 */
    private val lastRaw = MutableStateFlow(initialReading)

    private val merged: Flow<ThermalReading> =
        if (sources.isEmpty()) {
            flowOf(initialReading)
        } else {
            // spread 进 vararg；onStart 先补一帧 NORMAL，保证订阅后立刻有值
            merge(*sources.map { it.readings() }.toTypedArray())
                .onStart { emit(initialReading) }
        }

    val level: StateFlow<ThermalLevel> = merged
        .map { reading ->
            lastRaw.value = reading
            reading
        }
        .scan(ThermalHysteresis.Hold.INITIAL) { state, reading ->
            ThermalHysteresis.step(
                state = state,
                incoming = reading.level,
                nowMs = if (reading.timestampMs > 0L) reading.timestampMs else System.currentTimeMillis(),
                holdMs = demoteHoldMs,
            )
        }
        .map { it.stable }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), ThermalLevel.NORMAL)

    /** 诊断面板用：原始采样（等级字段已被替换成迟滞后的稳定值）。 */
    val reading: StateFlow<ThermalReading> =
        combine(lastRaw, level) { raw, stable -> raw.copy(level = stable) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), initialReading)

    /**
     * 系统省电模式是否开启。
     * 用户手动开的省电模式优先级高于我们的自动降级——他明确说了要省电。
     */
    fun isSystemPowerSaveMode(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }
}
