package com.thermo.thermal

import kotlinx.coroutines.flow.Flow

/**
 * 温度来源抽象。
 *
 * 之所以做成接口而不是一个写死的大类：
 *  1. 单测时塞一个假 Flow 就能验证三级降级策略，不需要真机烤机；
 *  2. 小米/华为等厂商的私有温度接口可以后续新增实现，上层不用改；
 *  3. 不同 Android 版本的能力差异被隔离在各实现里，上层只看到统一的 Flow。
 *
 * 实现约定：**无人订阅时必须释放底层监听**，不允许常驻 registerReceiver
 * 或常驻 Handler 轮询——那本身就是耗电源。
 */
interface ThermalSource {
    /** 单调递增的温度流。允许重复值，允许乱序到达（下游会 conflate）。 */
    fun readings(): Flow<ThermalReading>
}
