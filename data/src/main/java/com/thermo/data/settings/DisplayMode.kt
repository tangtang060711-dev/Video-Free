package com.thermo.data.settings

/**
 * 主界面视频列表的显示方式。
 *
 * 用枚举而不是布尔值：需求说的是「列表 / 平铺」两种形态，
 * 以后若再加「紧凑列表」也不会破坏已经存进 SharedPreferences 的值。
 */
enum class DisplayMode(val key: String, val label: String) {
    /** 一行一个视频：文件名、时长、大小一字排开，信息密度最高。 */
    LIST("list", "列表"),

    /** 多列卡片：以缩略图为视觉主体，适合快速翻找。 */
    GRID("grid", "平铺"),
    ;

    companion object {
        val ALL = entries.toList()

        fun fromKey(key: String?): DisplayMode =
            entries.firstOrNull { it.key == key } ?: LIST
    }
}