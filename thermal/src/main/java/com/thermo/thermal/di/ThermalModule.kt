package com.thermo.thermal.di

import android.content.Context
import com.thermo.thermal.ThermalSource
import com.thermo.thermal.impl.LegacyBatteryThermalSource
import com.thermo.thermal.impl.PowerManagerThermalSource
import com.thermo.thermal.impl.ThermalZoneSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.multibindings.IntoSet
import dagger.hilt.components.SingletonComponent

/**
 * 三条温度通道全部注册进 Set<ThermalSource>，
 * ThermalMonitor 拿到的是一个集合，不需要知道具体有几个来源。
 *
 * 新增厂商通道时只改这里，ThermalMonitor 与上层策略一行都不用动。
 */
@Module
@InstallIn(SingletonComponent::class)
object ThermalModule {

    /**
     * 降温确认时长：同一档更低的温度连续保持这么久才真正生效。
     *
     * 取 60 秒不是拍脑袋——它必须明显大于
     * "一次 seek / 一次轨道重建导致的温度尖峰"的时间尺度，
     * 否则会把正常操作误判成升温抖动，反复重建 MediaCodec，
     * 反而比不降级更烫。
     */
    @Provides
    @DemoteHoldMs
    fun provideDemoteHoldMs(): Long = 60_000L


    /** 官方通道，API 29+。低版本返回空流，不影响其他来源。 */
    @Provides
    @IntoSet
    fun providePowerManagerSource(
        @ApplicationContext context: Context,
    ): ThermalSource = PowerManagerThermalSource(context)

    /** 兼容通道，API 24+ 全版本可用，同时作为官方通道的交叉验证。 */
    @Provides
    @IntoSet
    fun provideLegacyBatterySource(
        @ApplicationContext context: Context,
    ): ThermalSource = LegacyBatteryThermalSource(context)

    /** 增强通道，反射读热区结温。拿不到就静默为空，绝不抛异常。 */
    @Provides
    @IntoSet
    fun provideThermalZoneSource(
        @ApplicationContext context: Context,
    ): ThermalSource = ThermalZoneSource(context)
}
