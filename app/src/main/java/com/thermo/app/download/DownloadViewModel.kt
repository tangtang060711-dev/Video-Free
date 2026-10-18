package com.thermo.app.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.thermo.data.download.DownloadManager
import com.thermo.data.download.DownloadTask
import com.thermo.data.settings.ThermoSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 下载页的 ViewModel。
 *
 * 它自己不做任何下载逻辑 —— 下载引擎在 :data 的 DownloadManager 里，
 * 由一个进程级协程跑。ViewModel 只做三件事：把任务列表透出给 Compose、
 * 转发用户的暂停/继续/取消、把播放请求翻译成一个可播的 Uri。
 *
 * 为什么下载不放在 ViewModel 的 viewModelScope 里：用户从下载页切走
 * （甚至退到后台）下载必须继续。viewModelScope 随页面销毁而取消，
 * 放进去等于"离开页面就断"。
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val manager: DownloadManager,
    private val settings: ThermoSettings,
) : ViewModel() {

    /** 全部任务，新的在前。直接来自 DownloadManager 的 StateFlow。 */
    val tasks: StateFlow<List<DownloadTask>> = manager.tasks

    /** 当前设置的线程数（1~32）。 */
    val threads: StateFlow<Int> = settings.downloadThreads

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 添加一个下载。
     *
     * 地址归一化在这里做而不是在引擎里：引擎只认完整 URL，
     * 用户手输时十有八九只写 `example.com/a.mp4`。少了 scheme 时
     * 直接拼 https —— 现代站点基本都有，拼 http 反而会被挡回来。
     */
    fun enqueue(url: String, title: String? = null) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("http://", true) -> trimmed
            trimmed.startsWith("https://", true) -> trimmed
            // 已经带了别的 scheme（磁力、ftp）—— 不猜，直接拒绝并说明。
            trimmed.contains("://") -> {
                _message.value = "只支持 http/https 地址"
                return
            }
            else -> "https://$trimmed"
        }
        runCatching { manager.enqueue(normalized, title) }
            .onSuccess { _message.value = "已加入下载队列" }
            .onFailure { _message.value = "添加失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun pause(id: String) = manager.pause(id)

    fun resume(id: String) = manager.resume(id)

    /** 取消并删除半截文件。 */
    fun cancel(id: String) = manager.cancel(id)

    /**
     * 取已下载文件的播放地址。
     *
     * 返回 null 的情况是真实存在的：用户在系统文件管理器里把
     * `files/downloads` 下的文件删了，而数据库里的记录还在。
     * 这时候不该静默，要让用户知道。
     */
    fun uriFor(id: String): Uri? = manager.uriFor(id)

    /**
     * 文件不在了。
     *
     * 这不是"下载失败"，是用户（或某个清理软件）把 files/downloads 下的
     * 文件删掉了而数据库记录还在。措辞要能让人自己想到该做什么，
     * 而不是一句"操作失败"。
     */
    fun reportMissingFile() {
        _message.value = "文件已被删除，请先「删除」这条记录"
    }
    fun setThreads(count: Int) = settings.setDownloadThreads(count)

    fun consumeMessage() {
        _message.value = null
    }
}