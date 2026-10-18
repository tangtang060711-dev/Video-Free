package com.thermo.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.view.OrientationEventListener
import com.thermo.data.settings.OrientationMode

/**
 * 屏幕方向控制。
 *
 * 为什么不在 Manifest 上写死 screenOrientation：
 *  1. Manifest 上的固定值是**应用单方面声明的**，会无视系统那个「自动旋转」开关。
 *     用户把自动旋转关了，应用照样转 —— 与「跟随系统方向锁定」直接冲突。
 *  2. 原来的 fullSensor 是四向全跟传感器。躺床上时手机接近平放，重力在
 *     屏幕平面内的分量很小，传感器噪声会让画面在 0° 和 180° 之间来回翻，
 *     这正是用户说的「旋转 180°」。
 * 所以 Manifest 里不再声明方向，全部由这里在运行期决定。
 *
 * [OrientationMode.AUTO] 的行为：
 *  - 系统「自动旋转」关着：直接照抄系统的锁定方向，其中 180° 会被归一成竖屏正，
 *    **不出现倒过来的画面**。
 *  - 系统「自动旋转」开着：用 [OrientationEventListener] 自己判，只允许
 *    「竖屏正 / 横屏正 / 横屏反」三档。倒竖那一段（135°~224°）沿用上一次的方向，
 *    所以画面永远不会上下颠倒。
 *  - 传感器报 ORIENTATION_UNKNOWN（手机接近平放）时保持不动，不抖。
 *
 * 为什么横屏的两个方向都要保留：把手机翻个面就是另一种横屏握法，
 * 只留一个的话翻面之后画面反而是颠倒的 —— 那才是真的 180°。
 * 被禁止的只有「反向竖屏」。
 */
class OrientationController(
    private val activity: Activity,
    /**
     * 生效方向变了就回调一次。
     *
     * 参数是【给人看的短标签】而不是枚举名：AUTO 档下显示的应该是
     * 「跟随·横屏」这种"现在到底横过来了没有"，而不是"我的策略是什么"。
     */
    private val onEffectiveChanged: (String) -> Unit,
) {

    private var setting: OrientationMode = OrientationMode.PORTRAIT

    /** 播放页那个「转一下」按钮造成的临时覆盖，离开播放页就清掉，不写进设置。 */
    private var session: OrientationMode? = null

    private var running = false

    /** 当前生效的方向（会话覆盖优先）。 */
    var current: OrientationMode = OrientationMode.PORTRAIT
        private set

    /** 现在是不是横着（正横/反横都算）。播放页按钮用它决定切过去还是切回来。 */
    fun isLandscape(): Boolean =
        current == OrientationMode.LANDSCAPE || current == OrientationMode.REVERSE_LANDSCAPE

    /** 倒竖区间里要「沿用上一次」，存的就是上一次那个非倒置的方向。 */
    private var lastUpright: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    private val listener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (!running || effective() != OrientationMode.AUTO) return
            // 接近平放：传感器自己都说不出话，保持原样比乱翻好。
            if (orientation == ORIENTATION_UNKNOWN) return
            val target = when {
                // 135°~224° 就是「倒竖」附近。整段都不允许出现倒立画面，
                // 直接沿用上一次的方向 —— 这就是「不能旋转 180°」的落点。
                orientation in 135..224 -> lastUpright
                orientation in 45..134 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                orientation in 225..314 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            lastUpright = target
            apply(target)
        }
    }

    /**
     * 系统里的「自动旋转 / 旋转锁定」被改动时要立刻重新生效。
     * 不监听的话，用户在下拉快捷开关里一关自动旋转，本应用要等下次进前台才跟上。
     */
    private val systemObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (running && effective() == OrientationMode.AUTO) refresh()
        }
    }

    private fun effective(): OrientationMode = session ?: setting

    /** 设置页改了方向策略。 */
    fun setSetting(mode: OrientationMode) {
        setting = mode
        if (running) refresh()
    }

    /** 播放页按钮的临时覆盖；传 null = 回到设置里的值。 */
    fun setSessionOverride(mode: OrientationMode?) {
        session = mode
        if (running) refresh()
    }

    /** Activity 进前台：开始监听系统锁定开关与传感器。 */
    fun start() {
        if (running) return
        running = true
        val cr = activity.contentResolver
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            systemObserver,
        )
        // 只锁不改的场景：用户把锁定方向从横屏改成竖屏，也要立刻跟。
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.USER_ROTATION),
            false,
            systemObserver,
        )
        // 先把「上一次的方向」按当前屏幕方向播个种，避免切到 AUTO 时闪一下。
        runCatching {
            val rot = activity.display?.rotation ?: 0
            lastUpright = if (rot == 1 || rot == 3) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        refresh()
    }

    /** Activity 退到后台：把传感器关掉，别让它在后台白白耗电。 */
    fun stop() {
        if (!running) return
        running = false
        listener.disable()
        runCatching { activity.contentResolver.unregisterContentObserver(systemObserver) }
    }

    private fun refresh() {
        val mode = effective()
        current = mode
        when (mode) {
            OrientationMode.PORTRAIT -> lock(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            OrientationMode.LANDSCAPE -> lock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            OrientationMode.REVERSE_PORTRAIT -> lock(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            OrientationMode.REVERSE_LANDSCAPE -> lock(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
            OrientationMode.AUTO -> auto()
        }
    }

    private fun lock(value: Int) {
        listener.disable()
        lastUpright = value
        apply(value)
    }

    private fun auto() {
        val cr = activity.contentResolver
        // ACCELEROMETER_ROTATION == 1 表示系统那个「自动旋转」是开着的。
        val autoRotate = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        if (!autoRotate) {
            // 系统锁着：跟锁。USER_ROTATION 的 0/1/2/3 分别是 0°/90°/180°/270°。
            // 180° 归一成竖屏正 —— 用户锁的是「别转」，不是「倒过来」。
            listener.disable()
            val v = when (Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0)) {
                1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            lastUpright = v
            apply(v)
        } else {
            // 系统没锁：先钉到上一次的方向免得闪一下，随后交给传感器逐帧纠正。
            apply(lastUpright)
            listener.enable()
        }
    }

    /** 只在真的变了才赋值：requestedOrientation 每次赋值都会触发一次配置变更。 */
    private fun apply(value: Int) {
        Log.i(TAG, "apply=" + value + " mode=" + current + " requested=" + activity.requestedOrientation)
        if (activity.requestedOrientation != value) {
            activity.requestedOrientation = value
        }
        // 每次都刷一遍标签。StateFlow 会自动去重，重复赋同样的值不会引发重组。
        onEffectiveChanged(labelFor(current, value))
    }

    /**
     * 标签文字。
     *
     * AUTO 档下刻意显示「跟随·横屏/竖屏」，而不是「跟随系统」：
     * 用户按一下按钮之后想知道的是「现在到底横过来了没有」，而不是「我的策略是什么」。
     */
    private fun labelFor(mode: OrientationMode, resolved: Int): String = when (mode) {
        OrientationMode.AUTO -> when (resolved) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "跟随·横屏"
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "跟随·横屏(反)"
            else -> "跟随·竖屏"
        }
        else -> mode.label
    }

    private companion object {
        const val TAG = "Orientation"
    }
}
