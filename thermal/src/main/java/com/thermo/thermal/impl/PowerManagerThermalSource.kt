package com.thermo.thermal.impl

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.thermo.thermal.ThermalLevel
import com.thermo.thermal.ThermalReading
import com.thermo.thermal.ThermalSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow

/**
 * 官方通道：PowerManager 热状态监听（Android 10 / API 29 起）。
 *
 * 关键认知：这个 API 报的不是"温度"，而是**系统已经决定要限频了**。
 * 也就是说 THERMAL_STATUS_MODERATE 出现时，GPU/CPU 通常已经被降过频了。
 * 因此我们把映射整体前移一档——在 SEVERE 之前就开始降级，而不是等 SEVERE。
 *
 * 映射表：
 *   NONE / LIGHT          -> NORMAL（LIGHT 只是轻微，播放器不必动作）
 *   MODERATE              -> WARM
 *   SEVERE                -> HOT
 *   CRITICAL/EMERGENCY/SHUTDOWN -> CRITICAL
 */
class PowerManagerThermalSource(
    private val context: Context,
) : ThermalSource {

    override fun readings(): Flow<ThermalReading> {
        // 低版本设备直接返回空流，由 LegacyBatteryThermalSource 顶上
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyFlow()
        return QImpl(context).readings()
    }

    /** 单独拆一个 @RequiresApi 类，避免在同一个函数里混着写版本保护，Lint 也不会误报。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class QImpl(private val context: Context) {

        fun readings(): Flow<ThermalReading> = callbackFlow {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                trySend(
                    ThermalReading(
                        level = mapStatus(status),
                        rawStatus = status,
                        source = "PowerManager",
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            }

            // 先补一帧当前值：否则订阅后要等到系统下次状态翻转才有数据，
            // 而"打开播放器那一刻手机已经是热的"恰恰是最常见的情况。
            val initial = pm.currentThermalStatus
            trySend(
                ThermalReading(
                    level = mapStatus(initial),
                    rawStatus = initial,
                    source = "PowerManager#initial",
                    timestampMs = System.currentTimeMillis(),
                )
            )

            // 用主线程 Executor：回调极低频（分钟级），不值得为它开线程。
            pm.addThermalStatusListener(context.mainExecutor, listener)

            // 无人订阅时摘掉监听，避免进程被系统唤醒
            awaitClose { pm.removeThermalStatusListener(listener) }
        }.conflate() // 温度是"只要最新值"的样本流，中间态丢掉不影响决策
    }

    private companion object {
        @RequiresApi(Build.VERSION_CODES.Q)
        fun mapStatus(status: Int): ThermalLevel = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
            else -> ThermalLevel.NORMAL
        }
    }
}
