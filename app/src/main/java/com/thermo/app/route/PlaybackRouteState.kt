package com.thermo.app.route

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 顶层页面。只有三个，不值得引一套导航库。 */
enum class AppScreen {
    /** 本地/网络视频列表 */
    HOME,

    /** 播放页 */
    PLAYER,

    /** 加密空间 */
    VAULT,

    /** 设置页 */
    SETTINGS,

    /** 下载管理页 */
    DOWNLOADS,
}

/**
 * "当前该显示哪个页面"这件事，必须活得比 Activity 久。
 *
 * 优化 1 与优化 3 的根因是同一个：页面状态以前放在 Compose 的
 * remember { mutableStateOf } 里，它只活在一次 composition 里。
 * 于是只要 Activity 被重建（从桌面重新进入、系统回收后恢复、旋转屏幕），
 * 页面就会掉回列表页 —— 用户看到的是"画面被蒙了一层黑色"，
 * 其实是列表页（深色背景）盖在了还在解码的 SurfaceView 上面。
 *
 * 放进 Hilt 单例：它和被 @Singleton 持有的 ExoPlayer 同生命周期，
 * 不会再出现"播放器还在放，UI 却不知道在放什么"的错位状态。
 */
@Singleton
class PlaybackRouteState @Inject constructor() {

    private val _screen = MutableStateFlow(AppScreen.HOME)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

    /** 从哪个页面进的播放页。返回时回到这里，而不是一律回 HOME。 */
    private var playerOrigin: AppScreen = AppScreen.HOME

    /** 进播放页。记住入口页面，返回时好回到原处。 */
    fun open(uri: Uri) {
        if (_screen.value != AppScreen.PLAYER) {
            playerOrigin = _screen.value
        }
        _currentUri.value = uri
        _screen.value = AppScreen.PLAYER
    }

    /**
     * 回列表页（回进入播放页之前的那个页面）。刻意不停播放 ——
     * 用户按这个按钮多半是想换一个片子，而不是想停下来。
     */
    fun backToList() {
        _currentUri.value = null
        val back = playerOrigin
        playerOrigin = AppScreen.HOME
        _screen.value = back
    }

    fun openVault() {
        _screen.value = AppScreen.VAULT
    }

    fun closeVault() {
        _screen.value = AppScreen.HOME
    }

    fun openSettings() {
        _screen.value = AppScreen.SETTINGS
    }

    fun closeSettings() {
        _screen.value = AppScreen.HOME
    }

    fun openDownloads() {
        _screen.value = AppScreen.DOWNLOADS
    }

    fun closeDownloads() {
        _screen.value = AppScreen.HOME
    }
}
