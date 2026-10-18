package com.thermo.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户偏好。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 * 这里只有几个布尔/数字/字符串标志，全部是"设置页勾一下、播放器读一下"的用法。
 * DataStore 的卖点是异步、事务、无 apply() 死锁风险 —— 这些对
 * "一棵 20 行的设置项" 都不构成问题，代价却是 3 个额外 artifact
 * （datastore / datastore-core / okio）和一套 Flow 样板。
 *
 * ## 为什么对外暴露 StateFlow 而不是 Flow
 * 本类是这些设置的**唯一写入方**（UI 通过它的 setter 改），所以不需要
 * 注册 OnSharedPreferenceChangeListener 去监听别的写入者 —— 那是过度设计。
 * 一个 MutableStateFlow 就够了，而且 UI 能立刻拿到当前值（不用先等一帧）。
 *
 * ## 为什么用 apply() 不用 commit()
 * `commit()` 是同步的：它会 fsync 到磁盘后才返回，在主线程调用就是一次磁盘阻塞。
 * `apply()` 只是改内存 + 丢给后台线程写。设置项丢一次写入无关紧要，
 * 用一个可能的主线程卡顿去换它不值得。
 */
@Singleton
class ThermoSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _powerSaveMode = MutableStateFlow(prefs.getBoolean(KEY_POWER_SAVE, false))

    /**
     * 用户手动开启的省电模式。
     *
     * 它的优先级**高于**自动温度降级：用户主动说"我要省电"，
     * 就按最严的策略走，不管手机烫不烫。见 :app 的 ThermalPolicyCoordinator。
     */
    val powerSaveMode: StateFlow<Boolean> = _powerSaveMode.asStateFlow()

    private val _followVideoFrameRate =
        MutableStateFlow(prefs.getBoolean(KEY_FOLLOW_FRAME_RATE, true))

    /**
     * 是否让屏幕刷新率跟随视频帧率。
     *
     * 默认开。24fps 电影在 120Hz 屏上如果每帧都重绘，GPU 与显示控制器
     * 的功耗是 60Hz 的两倍，而人眼看到的画面**完全一样**。
     * 关掉它的唯一场景是"厂商 ROM 切模式时会有闪黑"。
     */
    val followVideoFrameRate: StateFlow<Boolean> = _followVideoFrameRate.asStateFlow()

    private val _allowSoftwareFallback =
        MutableStateFlow(prefs.getBoolean(KEY_SOFTWARE_FALLBACK, true))

    /** 是否允许在硬解不可用时回落到 FFmpeg 软解。 */
    val allowSoftwareFallback: StateFlow<Boolean> = _allowSoftwareFallback.asStateFlow()

    private val _audioOnlyInBackground =
        MutableStateFlow(prefs.getBoolean(KEY_AUDIO_ONLY_BACKGROUND, true))

    /**
     * 切到后台时是否释放视频 Surface、只留音频。
     * 这是整个 App 里**单点收益最大**的一条省电策略：
     * 后台还在解码 1080p 视频，等于屏幕关了但解码器和 GPU 没关。
     */
    val audioOnlyInBackground: StateFlow<Boolean> = _audioOnlyInBackground.asStateFlow()

    private val _backgroundPlayback =
        MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND_PLAYBACK, false))

    /**
     * 视频后台播放。**默认关**。
     *
     * 关着的时候：退出播放页（返回手势或左上角返回按钮）与退到桌面，
     * 都会把这一轮播放直接收掉。理由是本工程没有小窗、也没有通知栏控件 ——
     * 声音还在响而用户找不到任何能按的暂停键，那不是"后台播放"这个功能。
     *
     * 打开之后：[audioOnlyInBackground] 才起作用 —— 它决定退到后台时
     * 要不要把视频 Surface 摘掉、只留声音。开着这个开关还继续解码视频，
     * 等于屏幕关了但解码器和 GPU 没关，是整个 App 里最费电的一种状态。
     */
    val backgroundPlayback: StateFlow<Boolean> = _backgroundPlayback.asStateFlow()

    private val _downloadThreads =
        MutableStateFlow(prefs.getInt(KEY_DOWNLOAD_THREADS, DEFAULT_DOWNLOAD_THREADS))

    /**
     * 多线程下载的分片数（1~[MAX_DOWNLOAD_THREADS]）。
     *
     * 默认 8 而不是 32：家用宽带上 8 条连接已经能跑满绝大多数 CDN，
     * 线程再往上加，服务端限速、TCP 拥塞控制与手机侧的调度开销会把收益吃掉，
     * 而 flash 的随机写与网络模块的功耗是实打实往上涨的。
     * 32 是上限，不是推荐值。
     */
    val downloadThreads: StateFlow<Int> = _downloadThreads.asStateFlow()

    // ---------------- 长按加速 ----------------

    private val _longPressSpeed =
        MutableStateFlow(prefs.getFloat(KEY_LONG_PRESS_SPEED, DEFAULT_LONG_PRESS_SPEED))

    /**
     * 长按全屏时的临时倍速，范围 [MIN_LONG_PRESS_SPEED]~[MAX_LONG_PRESS_SPEED]。
     *
     * 默认 2.0：再往上，软解或高码率片子的 CPU 会被直接拉满，
     * 倍速越高越费电 —— 所以留给用户自己调，而不是替用户钉死。
     */
    val longPressSpeed: StateFlow<Float> = _longPressSpeed.asStateFlow()

    // ---------------- 主界面显示 ----------------

    private val _displayMode = MutableStateFlow(
        DisplayMode.fromKey(prefs.getString(KEY_DISPLAY_MODE, null)),
    )

    /** 主界面视频列表是列表还是平铺。 */
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

    private val _showThumbnails = MutableStateFlow(prefs.getBoolean(KEY_SHOW_THUMBNAILS, true))

    /**
     * 是否在列表/平铺里显示视频缩略图。
     *
     * 默认开。抽缩略图是"读 1 帧就释放"的轻操作，但视频一多（几百条）
     * 每次进列表都要抽一轮，低端机上能感到加载 —— 关掉后只显示分辨率占位块。
     */
    val showThumbnails: StateFlow<Boolean> = _showThumbnails.asStateFlow()

    // ---------------- 屏幕方向 ----------------

    private val _orientationMode = MutableStateFlow(
        OrientationMode.fromKey(prefs.getString(KEY_ORIENTATION, null)),
    )

    /** 屏幕方向策略。默认 [OrientationMode.AUTO] = 跟随系统方向锁。 */
    val orientationMode: StateFlow<OrientationMode> = _orientationMode.asStateFlow()

    fun setPowerSaveMode(enabled: Boolean) {
        if (_powerSaveMode.value == enabled) return
        prefs.edit().putBoolean(KEY_POWER_SAVE, enabled).apply()
        _powerSaveMode.value = enabled
    }

    fun setFollowVideoFrameRate(enabled: Boolean) {
        if (_followVideoFrameRate.value == enabled) return
        prefs.edit().putBoolean(KEY_FOLLOW_FRAME_RATE, enabled).apply()
        _followVideoFrameRate.value = enabled
    }

    fun setAllowSoftwareFallback(enabled: Boolean) {
        if (_allowSoftwareFallback.value == enabled) return
        prefs.edit().putBoolean(KEY_SOFTWARE_FALLBACK, enabled).apply()
        _allowSoftwareFallback.value = enabled
    }

    fun setAudioOnlyInBackground(enabled: Boolean) {
        if (_audioOnlyInBackground.value == enabled) return
        prefs.edit().putBoolean(KEY_AUDIO_ONLY_BACKGROUND, enabled).apply()
        _audioOnlyInBackground.value = enabled
    }

    fun setBackgroundPlayback(enabled: Boolean) {
        if (_backgroundPlayback.value == enabled) return
        prefs.edit().putBoolean(KEY_BACKGROUND_PLAYBACK, enabled).apply()
        _backgroundPlayback.value = enabled
    }

    fun setDownloadThreads(count: Int) {
        val v = count.coerceIn(1, MAX_DOWNLOAD_THREADS)
        if (_downloadThreads.value == v) return
        prefs.edit().putInt(KEY_DOWNLOAD_THREADS, v).apply()
        _downloadThreads.value = v
    }

    fun setLongPressSpeed(speed: Float) {
        val v = speed.coerceIn(MIN_LONG_PRESS_SPEED, MAX_LONG_PRESS_SPEED)
        if (kotlin.math.abs(_longPressSpeed.value - v) < 0.05f) return
        prefs.edit().putFloat(KEY_LONG_PRESS_SPEED, v).apply()
        _longPressSpeed.value = v
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (_displayMode.value == mode) return
        prefs.edit().putString(KEY_DISPLAY_MODE, mode.key).apply()
        _displayMode.value = mode
    }

    fun setShowThumbnails(enabled: Boolean) {
        if (_showThumbnails.value == enabled) return
        prefs.edit().putBoolean(KEY_SHOW_THUMBNAILS, enabled).apply()
        _showThumbnails.value = enabled
    }

    fun setOrientationMode(mode: OrientationMode) {
        prefs.edit().putString(KEY_ORIENTATION, mode.key).apply()
        _orientationMode.value = mode
    }

    private companion object {
        const val PREFS_NAME = "thermo_settings"
        const val DEFAULT_DOWNLOAD_THREADS = 8
        const val MAX_DOWNLOAD_THREADS = 32
        const val DEFAULT_LONG_PRESS_SPEED = 2f
        const val MIN_LONG_PRESS_SPEED = 1.5f
        const val MAX_LONG_PRESS_SPEED = 5f
        const val KEY_POWER_SAVE = "power_save_mode"
        const val KEY_FOLLOW_FRAME_RATE = "follow_video_frame_rate"
        const val KEY_SOFTWARE_FALLBACK = "allow_software_fallback"
        const val KEY_AUDIO_ONLY_BACKGROUND = "audio_only_in_background"
        const val KEY_BACKGROUND_PLAYBACK = "background_playback"
        const val KEY_DOWNLOAD_THREADS = "download_threads"
        const val KEY_LONG_PRESS_SPEED = "long_press_speed"
        const val KEY_DISPLAY_MODE = "home_display_mode"
        const val KEY_SHOW_THUMBNAILS = "home_show_thumbnails"
        const val KEY_ORIENTATION = "screen_orientation"
    }
}