package com.thermo.thermal.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.BatteryManager
import com.thermo.thermal.ThermalLevel
import com.thermo.thermal.ThermalReading
import com.thermo.thermal.ThermalSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 兼容通道：从电量广播的 EXTRA_TEMPERATURE 读电池温度。
 * API 24 起全版本可用，所以在 24~28 的设备上它是唯一来源。
 *
 * 已知偏差，必须在阈值上补偿：
 *  电池温度**明显滞后**于 SoC 结温，通常差 5~10 度、延迟数十秒。
 *  所以这里把阈值定在 40/43/46：HOT(46 度电池) 时 SoC 结温通常已经七十多度。
 *
 * 采样策略：广播 + 自适应轮询双保险。
 *  广播负责"系统主动告诉我有变化"，
 *  轮询负责"手机静置时广播很久不来"的兜底。
 *  轮询间隔随档位变化（30s / 15s / 6s）——固定 5 秒轮询在挂机场景
 *  一天要白白唤醒一万多次，这本身就是发热源。
 */
class LegacyBatteryThermalSource(
    private val context: Context,
    private val intervalNormalMs: Long = 30_000L,
    private val intervalWarmMs: Long = 15_000L,
    private val intervalHotMs: Long = 6_000L,
) : ThermalSource {

    override fun readings(): Flow<ThermalReading> = callbackFlow {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths != Int.MIN_VALUE) {
                    trySend(buildReading(tenths / 10f, "Battery#broadcast"))
                }
            }
        }

        // API 33+ 注册非系统广播必须显式声明导出标志。
        // ACTION_BATTERY_CHANGED 是受保护广播，用 NOT_EXPORTED 最安全。
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        // 注册返回值就是当前 sticky 值，顺手先发一帧，避免要等下一条广播
        sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { trySend(buildReading(it / 10f, "Battery#sticky")) }

        val pollJob = launch {
            var lastLevel = ThermalLevel.NORMAL
            while (isActive) {
                delay(
                    when (lastLevel) {
                        ThermalLevel.NORMAL -> intervalNormalMs
                        ThermalLevel.WARM -> intervalWarmMs
                        ThermalLevel.HOT, ThermalLevel.CRITICAL -> intervalHotMs
                    }
                )
                // 重新读一次 sticky，不发广播也能拿到当前值
                val fresh = context.registerReceiver(null, filter)
                val t = fresh?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
                if (t != Int.MIN_VALUE) {
                    val r = buildReading(t / 10f, "Battery#poll")
                    lastLevel = r.level
                    trySend(r)
                }
            }
        }

        awaitClose {
            pollJob.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.conflate()

    private fun buildReading(tempC: Float, source: String) = ThermalReading(
        level = levelOf(tempC),
        rawStatus = -1,
        batteryTempC = tempC,
        source = source,
        timestampMs = System.currentTimeMillis(),
    )

    private fun levelOf(tempC: Float): ThermalLevel = when {
        tempC < 40.0f -> ThermalLevel.NORMAL
        tempC < 43.0f -> ThermalLevel.WARM
        tempC < 46.0f -> ThermalLevel.HOT
        else -> ThermalLevel.CRITICAL
    }
}
