package com.thermo.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thermo.data.local.LocalMediaScanner
import com.thermo.data.local.MediaEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 本地媒体列表。
 *
 * 刻意和 PlayerViewModel 分开：列表页和播放页的生命周期完全不同 ——
 * 播放页要"退到后台还在跑"，列表页要"离开就停扫描"。
 * 混在一个 ViewModel 里，扫描的 ContentObserver 会一直被播放页拖着不释放。
 */
@HiltViewModel
class LocalMediaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: LocalMediaScanner,
) : ViewModel() {

    /** 是否已经拿到读取媒体的权限。UI 用它决定是显示列表还是显示"请求权限"按钮。 */
    private val _hasPermission = MutableStateFlow(scanner.hasMediaPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    /**
     * 本地视频列表。
     *
     * `WhileSubscribed(5_000)` 而不是 `Eagerly`：没有界面在看的时候，
     * ContentObserver 必须被解注册。MediaStore 的 ContentObserver 在部分 ROM 上
     * 会随查询产生额外的 WM 唤醒 —— 列表页退出后还挂着，是纯粹的白耗电。
     */
    val videos: StateFlow<List<MediaEntry>> = scanner.videos()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** 本机没有视频时的提示要用它把"权限没给"和"真的没有视频"区分开。 */
    val requiredPermissions: Array<String> get() = scanner.requiredPermissions()

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted && scanner.hasMediaPermission()
    }

    fun refreshPermission() {
        _hasPermission.value = scanner.hasMediaPermission()
    }

    fun onResume() {
        _hasPermission.value = scanner.hasMediaPermission()
        if (!_hasPermission.value) return
        // 权限是刚从设置页回来的话，MediaStore 已经有内容了，重新触发一次查询。
        // 这里不用刻意做「刷新」：videos 是 callbackFlow，订阅后立刻发第一帧。
        viewModelScope.launch { }
    }
}