package com.thermo.thermal

/**
 * 迟滞（hysteresis）状态机。
 *
 * 单独抽出来、做成纯函数，有两个实打实的好处：
 *  1. 可以在 JVM 单测里把抖动场景穷举一遍，不需要真机烤机；
 *  2. 状态机里一行 Android API 都没有，这保证了它不会因为版本差异行为不同。
 *
 * 规则：
 *  - **升温立刻生效**：安全优先，宁可多降一档，也不要在已经烫了之后还在犹豫。
 *  - **降温必须持续**：同一个更低的档位连续保持 holdMs 毫秒才认。
 *    中间只要有任何一帧回到当前档位，计时就清零重来。
 */
internal object ThermalHysteresis {

    /** 一次状态迁移的完整上下文。 */
    data class Hold(
        /** 当前对外生效的等级。 */
        val stable: ThermalLevel,
        /** 正在排队等待确认的更低档位；没有则为 null。 */
        val pending: ThermalLevel?,
        /** pending 第一次出现的时刻，用来算"持续了多久"。 */
        val pendingSinceMs: Long,
    ) {
        companion object {
            val INITIAL = Hold(ThermalLevel.NORMAL, null, 0L)
        }
    }

    /**
     * 推进一步。
     *
     * @param state   上一步的状态
     * @param incoming 本次采样得到的等级
     * @param nowMs   本次采样时刻（毫秒，单调即可，不需要绝对时间）
     * @param holdMs  降温确认需要的持续时间
     */
    fun step(state: Hold, incoming: ThermalLevel, nowMs: Long, holdMs: Long): Hold = when {
        // 升温或同档：立刻更新，并清掉所有排队中的降温意图
        incoming.code >= state.stable.code -> Hold(incoming, null, 0L)

        // 降温：同一个目标档位持续够久才生效
        else -> {
            val samePending = state.pending == incoming
            val since = if (samePending) state.pendingSinceMs else nowMs
            if (samePending && nowMs - since >= holdMs) {
                Hold(incoming, null, 0L)
            } else {
                Hold(state.stable, incoming, since)
            }
        }
    }
}
