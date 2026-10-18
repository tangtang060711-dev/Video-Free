package com.thermo.thermal

/**
 * 对上层暴露的四个温度档位。
 *
 * 刻意不直接把 PowerManager.THERMAL_STATUS_* 的 7 个值透出去，原因有两个：
 *  1. 那 7 个值是"整机"语义，粒度太细，播放器根本做不出 7 档不同动作；
 *  2. 那 7 个常量是 API 29 才有的，透出去会让上层到处写版本判断。
 *
 * [code] 是单调递增的，可以拿来直接比大小做迟滞判断。
 */
enum class ThermalLevel(val code: Int) {
    /** 正常：不做任何额外动作。 */
    NORMAL(0),

    /** 温热：只降预加载，画质不动（用户感知最小）。 */
    WARM(1),

    /** 烫：限码率 / 限分辨率 / 限帧率。 */
    HOT(2),

    /** 过热：释放视频解码器只留音频，并降到最低画质。 */
    CRITICAL(3);

    companion object {
        fun fromCode(code: Int): ThermalLevel = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
