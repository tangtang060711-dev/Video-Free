package com.thermo.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application 入口。
 *
 * 只做两件事，别的什么都不做 —— 尤其是**不在 Application 里初始化播放器**：
 * ExoPlayer 一建就会申请 MediaCodec 与 AudioTrack，冷启动就付这个代价，
 * 会让"打开 App 就感觉手机动了一下"。
 */
@HiltAndroidApp
class ThermoApp : Application() {

    @Inject lateinit var thermalPolicy: ThermalPolicyCoordinator

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 当前处于"已 start"状态的 Activity 个数。为 0 才是真的退到后台。
     *
     * 为什么不用 ProcessLifecycleOwner：它内部有 700ms 防抖，真机上实测出现过
     * "应用明明还在前台却报了一次 ON_STOP"。而优化 2 要求退到后台立刻停播 ——
     * 误判的代价变成"看着看着突然暂停"，这比晚 700ms 严重得多。
     */
    private var startedActivities = 0

    /**
     * 延迟判定后台。
     *
     * ## 为什么单纯数 Activity 不够（红米 K80 Pro / 澎湃 OS 4 实测）
     * 冷启动时刚拉起的 Activity 会先收到一次 `onActivityStopped`，之后**不会**
     * 再收到 `onActivityStarted`，但 `dumpsys activity activities` 里它已经是
     * `ResumedActivity`。也就是说"数 Activity"这条路在澎湃 OS 上会被骗到。
     *
     * 所以计数归零后先等 [BACKGROUND_GRACE_MS]，再向系统确认一次进程优先级。
     */
    private val confirmBackground = Runnable {
        if (startedActivities > 0) return@Runnable
        if (isProcessForeground()) {
            thermalPolicy.onAppForeground()
            return@Runnable
        }
        thermalPolicy.onAppBackground()
    }

    override fun onCreate() {
        super.onCreate()

        thermalPolicy.start()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                android.util.Log.i("ThermoApp", "CB start")
                val wasBackground = startedActivities == 0
                startedActivities++
                handler.removeCallbacks(confirmBackground)
                if (wasBackground) thermalPolicy.onAppForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                android.util.Log.i("ThermoApp", "CB stop")
                if (startedActivities > 0) startedActivities--
                if (startedActivities > 0) return
                // 旋转屏幕、切换深色模式、改字体大小都会走"先 stop 再 start"。
                // 判据用系统给的 isChangingConfigurations，而不是自己数秒数。
                if (activity.isChangingConfigurations) return
                handler.removeCallbacks(confirmBackground)
                handler.postDelayed(confirmBackground, BACKGROUND_GRACE_MS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) { android.util.Log.i("ThermoApp", "CB resume") }
            override fun onActivityPaused(activity: Activity) { android.util.Log.i("ThermoApp", "CB pause") }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 向系统要一次"我们进程现在什么优先级"。
     *
     * 用 [ActivityManager.getRunningAppProcesses]：Android 11 起它只返回本应用自己的
     * 进程，不需要任何权限。判据是 `IMPORTANCE_FOREGROUND`(100)，不是"小于等于
     * 前台服务(125)" —— 本工程后台播放会拉 mediaPlayback 前台服务，那时 importance
     * 正好是 125；按 125 判会让优化 2 直接失效。
     */
    private fun isProcessForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val procs = am.runningAppProcesses
        val myPid = Process.myPid()
        val me = procs?.firstOrNull { it.pid == myPid }
        android.util.Log.i(
            "ThermoApp",
            "前台判定 procs=" + (procs?.size ?: -1) + " pid=" + myPid +
                " imp=" + (me?.importance ?: -999),
        )
        return me != null &&
            me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 600L
    }
}
