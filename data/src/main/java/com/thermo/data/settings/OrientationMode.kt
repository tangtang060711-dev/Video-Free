package com.thermo.data.settings

/**
 * 屏幕方向策略。
 *
 * 为什么做成五种而不是「跟随系统 / 竖屏 / 横屏」三种：
 * 卧床看片时手机接近平放，重力在屏幕平面内的分量很小，传感器本身分不出
 * 你到底朝哪边躺 —— 这是物理上的信息缺失，不是算法能补的。此时唯一可靠的
 * 办法是人给一个信号，所以把四个物理方向都做成可以手动钉死的选项，
 * 用户看方向不对就再按一次。
 *
 * [PORTRAIT] 是默认值：程序默认锁竖屏。AUTO 表示「跟随系统的方向锁定」：
 * 系统锁了就跟锁，没锁才看传感器，并且**永远不进入反向竖屏**。
 */
enum class OrientationMode(val key: String, val label: String) {

    /** 跟随系统方向锁。系统锁了就照锁，没锁才用传感器。 */
    AUTO("auto", "跟随系统"),

    /** 竖屏，听筒朝上。物理上只有一个方向，不会倒过来。 */
    PORTRAIT("portrait", "竖屏"),

    /** 横屏，听筒朝左。物理上只有一个方向，不会倒过来。 */
    LANDSCAPE("landscape", "横屏"),

    /**
     * 竖屏倒转，听筒朝下。
     *
     * 自动模式下永远不会出现这一档，只有用户手动选才会生效：
     * 躺床上的时候，手机顶部朝哪边完全取决于你朝哪边躺，
     * 传感器答不出来，但用户自己知道。
     */
    REVERSE_PORTRAIT("reverse_portrait", "竖屏·倒转"),

    /** 横屏倒转，听筒朝右。 */
    REVERSE_LANDSCAPE("reverse_landscape", "横屏·倒转");

    /** 顺时针再转 90°。播放页那个「转一下」按钮按这个顺序循环。 */
    fun next(): OrientationMode = when (this) {
        AUTO -> PORTRAIT
        PORTRAIT -> LANDSCAPE
        LANDSCAPE -> REVERSE_PORTRAIT
        REVERSE_PORTRAIT -> REVERSE_LANDSCAPE
        REVERSE_LANDSCAPE -> AUTO
    }

    companion object {
        /** 设置页要按这个顺序列出来。 */
        val ALL: List<OrientationMode> = entries.toList()

        /** 从持久化的字符串还原。认不出来就回到 [PORTRAIT]（默认锁竖屏）。 */
        fun fromKey(key: String?): OrientationMode =
            entries.firstOrNull { it.key == key } ?: PORTRAIT
    }
}
