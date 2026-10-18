package com.thermo.thermal.impl

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.thermo.thermal.ThermalLevel
import com.thermo.thermal.ThermalReading
import com.thermo.thermal.ThermalSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * 增强通道：直读系统的热区（thermal zone）温度。
 *
 * 这是"能拿到就赚到"的通道，**不保证在任何机型上都能用**：
 *
 *  1. android.os.ThermalService 是 @hide 类，只能反射。它的
 *     getCurrentTemperaturesWithType() 在 AOSP 里带
 *     android.permission.DEVICE_POWER 校验（system|signature 级），
 *     普通应用调用会抛 SecurityException。
 *  2. HardwarePropertiesManager.getDeviceTemperatures() 同样是
 *     DEVICE_POWER 保护，普通应用必失败。
 *
 * 那为什么还要写？因为：
 *  - 部分厂商 ROM（含澎湃 OS）对自家白名单应用或开启"开发者选项-温度监控"后
 *    会放开该接口；厂商内部应用普遍走这条路。
 *  - 一旦可用，它是**唯一能拿到 CPU/GPU 结温**的途径，比电池温度准得多，
 *    能让降级提前 20~30 秒触发——这正是低发热的关键窗口。
 *
 * 全流程 try-catch，任何一步失败就降级成空流，绝不影响主链路。
 */
class ThermalZoneSource(
    private val context: Context,
    /** 结温采样间隔。这个值刻意保守：反射调用本身有开销。 */
    private val intervalMs: Long = 10_000L,
) : ThermalSource {

    override fun readings(): Flow<ThermalReading> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return kotlinx.coroutines.flow.emptyFlow()
        }
        return flow {
            val api = ThermalApi.acquire() ?: return@flow   // 拿不到就静默退出
            while (true) {
                val maxCpu = api.readMaxCpuTempC()
                if (maxCpu != null) {
                    emit(
                        ThermalReading(
                            level = levelOf(maxCpu),
                            rawStatus = -1,
                            skinTempC = maxCpu,
                            source = "ThermalZone",
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                }
                delay(intervalMs)
            }
        }.conflate()
    }

    /**
     * 结温阈值比电池温度高一档，因为它反映的是真实的 SoC 温度。
     * 骁龙 8 Elite 在 60 度左右开始明显降频，45 度属于"还早但该准备"的区间。
     */
    private fun levelOf(cpuC: Float): ThermalLevel = when {
        cpuC < 45.0f -> ThermalLevel.NORMAL
        cpuC < 53.0f -> ThermalLevel.WARM
        cpuC < 60.0f -> ThermalLevel.HOT
        else -> ThermalLevel.CRITICAL
    }

    /** 把一整套反射句柄缓存起来，避免每 10 秒重新反射一遍。 */
    private class ThermalApi(
        private val thermalService: Any,
        private val getTemperaturesWithType: java.lang.reflect.Method,
    ) {
        fun readMaxCpuTempC(): Float? = runCatching {
            listOf(TYPE_CPU, TYPE_GPU).maxOfOrNull { type ->
                @Suppress("UNCHECKED_CAST")
                val temps = getTemperaturesWithType.invoke(thermalService, type) as? Array<Any>
                    ?: return@maxOfOrNull Float.NaN
                temps.mapNotNull { t ->
                    runCatching {
                        t.javaClass.getMethod("getValue").invoke(t) as Float
                    }.getOrNull()
                }.maxOrNull() ?: Float.NaN
            }?.takeIf { !it.isNaN() }
        }.getOrNull()

        companion object {
            /** android.os.Temperature.TYPE_CPU / TYPE_GPU 的字面值，避免直接引用 @hide 常量。 */
            private const val TYPE_CPU = 2
            private const val TYPE_GPU = 3

            fun acquire(): ThermalApi? = runCatching {
                val stubClass = Class.forName("android.os.IThermalService\$Stub")
                val serviceManager = Class.forName("android.os.ServiceManager")
                val binder = serviceManager
                    .getMethod("getService", String::class.java)
                    .invoke(null, "thermalservice") as? IBinder
                    ?: return null
                val service = stubClass
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
                    ?: return null
                val clazz = Class.forName("android.os.ThermalService")
                // 方法名在不同 Android 版本上出现过 getCurrentTemperatures /
                // getCurrentTemperaturesWithType 两种，逐个探测
                val method = runCatching {
                    clazz.getMethod("getCurrentTemperaturesWithType", Int::class.javaPrimitiveType)
                }.getOrElse {
                    clazz.getMethod("getCurrentTemperaturesWithType", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                }
                ThermalApi(service, method)
            }.onFailure {
                Log.d(TAG, "热区接口不可用（属于预期情况）: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "ThermalZoneSource"
    }
}
