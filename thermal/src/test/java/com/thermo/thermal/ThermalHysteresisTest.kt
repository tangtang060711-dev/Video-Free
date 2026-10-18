package com.thermo.thermal

import com.thermo.thermal.ThermalHysteresis.Hold
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 迟滞状态机单测。
 * 因为 ThermalHysteresis 是纯函数（没有任何 Android API），
 * 这里可以在 JVM 上把真实的抖动场景穷举掉，不需要真机烤机。
 */
class ThermalHysteresisTest {

    private val holdMs = 60_000L

    /** 把一串 (等级, 时刻) 喂进去，返回每一步的稳定等级。 */
    private fun run(vararg inputs: Pair<ThermalLevel, Long>): List<ThermalLevel> {
        var state = Hold.INITIAL
        return inputs.map { (lvl, ts) ->
            state = ThermalHysteresis.step(state, lvl, ts, holdMs)
            state.stable
        }
    }

    @Test
    fun `升温立刻生效`() {
        val out = run(
            ThermalLevel.NORMAL to 0L,
            ThermalLevel.WARM to 1_000L,
            ThermalLevel.HOT to 2_000L,
            ThermalLevel.CRITICAL to 3_000L,
        )
        assertEquals(
            listOf(ThermalLevel.NORMAL, ThermalLevel.WARM, ThermalLevel.HOT, ThermalLevel.CRITICAL),
            out,
        )
    }

    @Test
    fun `降温需要持续 holdMs 才生效`() {
        // 0 秒时到 HOT，10 秒时回落到 NORMAL —— 只持续 10 秒，不应该降级
        val first = run(
            ThermalLevel.HOT to 0L,
            ThermalLevel.NORMAL to 10_000L,
        )
        assertEquals(ThermalLevel.HOT, first.last())

        // 继续等到 70 秒（累计持续 70 秒 > 60 秒），这时才认
        var state = Hold.INITIAL
        state = ThermalHysteresis.step(state, ThermalLevel.HOT, 0L, holdMs)
        state = ThermalHysteresis.step(state, ThermalLevel.NORMAL, 10_000L, holdMs)
        state = ThermalHysteresis.step(state, ThermalLevel.NORMAL, 70_000L, holdMs)
        assertEquals(ThermalLevel.NORMAL, state.stable)
    }

    @Test
    fun `抖动不会造成反复降级`() {
        // HOT -> WARM -> HOT -> WARM ... 每次回落都不到 60 秒
        var state = Hold.INITIAL
        var t = 0L
        state = ThermalHysteresis.step(state, ThermalLevel.HOT, t, holdMs)
        repeat(20) {
            t += 5_000L
            state = ThermalHysteresis.step(state, ThermalLevel.WARM, t, holdMs)
            t += 5_000L
            state = ThermalHysteresis.step(state, ThermalLevel.HOT, t, holdMs)
        }
        // 全程不应该降到 WARM
        assertEquals(ThermalLevel.HOT, state.stable)
    }

    @Test
    fun `pending 目标变化时计时器重置`() {
        var state = Hold.INITIAL
        state = ThermalHysteresis.step(state, ThermalLevel.CRITICAL, 0L, holdMs)
        // 10 秒时目标变成 HOT
        state = ThermalHysteresis.step(state, ThermalLevel.HOT, 10_000L, holdMs)
        assertEquals(ThermalLevel.CRITICAL, state.stable)
        // 40 秒时目标又变成 WARM —— 目标变了，计时应该从 40 秒重新算
        state = ThermalHysteresis.step(state, ThermalLevel.WARM, 40_000L, holdMs)
        assertEquals(ThermalLevel.CRITICAL, state.stable)
        // 90 秒：从 40 秒起持续 50 秒，仍不足 60 秒
        state = ThermalHysteresis.step(state, ThermalLevel.WARM, 90_000L, holdMs)
        assertEquals(ThermalLevel.CRITICAL, state.stable)
        // 105 秒：累计 65 秒，够了
        state = ThermalHysteresis.step(state, ThermalLevel.WARM, 105_000L, holdMs)
        assertEquals(ThermalLevel.WARM, state.stable)
    }
}
