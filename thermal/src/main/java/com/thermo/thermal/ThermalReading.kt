package com.thermo.thermal

/**
 * 一次温度采样。
 *
 * 各个字段的可获得性差异很大，所以一律允许为空，让上层能区分
 * "真的凉" 和 "拿不到数据"——这两件事在降级决策里必须区分开。
 *
 * @param level        归一化到四档后的等级
 * @param rawStatus    原始系统状态码（PowerManager.THERMAL_STATUS_*），拿不到时 -1
 * @param batteryTempC 电池温度（摄氏度）。低版本靠 ACTION_BATTERY_CHANGED，高版本可留空
 * @param skinTempC    机身/皮肤温度。只有厂商内部接口或特权应用能拿到，普通应用基本恒为 null
 * @param source       产出该采样的来源标识，排查问题时靠它定位是哪条通道在报
 * @param timestampMs  采样时刻，单位毫秒。迟滞逻辑要拿它算"持续了多久"
 */
data class ThermalReading(
    val level: ThermalLevel,
    val rawStatus: Int = -1,
    val batteryTempC: Float? = null,
    val skinTempC: Float? = null,
    val source: String = "unknown",
    val timestampMs: Long = 0L,
)
