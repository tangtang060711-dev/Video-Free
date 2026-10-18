package com.thermo.app.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thermo.data.vault.VaultEntry
import com.thermo.data.vault.VaultStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 加密空间的 ViewModel。
 *
 * 刻意不放进 [com.thermo.ui.PlayerViewModel]：加密空间的开关、导入、删除
 * 与播放没有任何关系，混在一起会让播放页多背一堆它不需要的状态。
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: VaultStore,
) : ViewModel() {

    val entries: StateFlow<List<VaultEntry>> = store.entries
    val unlocked: StateFlow<Boolean> = store.unlocked

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 身份校验通过。 */
    fun onUnlocked() = store.unlock()

    /** 手动上锁（离开页面时也调，见 VaultScreen 的 DisposableEffect）。 */
    fun lock() = store.lock()

    /**
     * 把一个外部视频加密搬进空间。
     *
     * 导入成功后**不再保留源文件**：先试着用 SAF 的 write 权限直接把源文件删掉；
     * 删不掉（比如用户选的是只读来源）就退而求其次把它改名为 `<原名>.1`。
     * MediaStore 按扩展名识别视频，改成 .1 之后主界面列表就再也扫不到它，
     * 而文件本身还在 —— 用户要找回时把扩展名改回去即可。
     */
    fun import(uri: Uri) = importAll(listOf(uri))

    /** 多选导入：逐条加密搬入，最后汇总一条提示，避免几十条消息刷屏。 */
    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty() || _importing.value) return
        viewModelScope.launch {
            _importing.value = true
            var ok = 0
            var renamed = 0
            var kept = 0
            for (uri in uris) {
                val name = queryDisplayName(uri) ?: "未命名视频"
                val imported = runCatching { store.import(uri, name) }.getOrDefault(false)
                if (!imported) continue
                ok++
                when (hideSource(uri, name)) {
                    HideResult.DELETED -> Unit
                    HideResult.RENAMED -> renamed++
                    HideResult.KEPT -> kept++
                }
            }
            _importing.value = false
            _message.value = when {
                ok == 0 -> "没有导入任何文件"
                kept > 0 -> "已导入 $ok 个；其中 $kept 个源文件无法自动删除，请手动处理"
                renamed > 0 -> "已导入 $ok 个；源文件已改名隐藏（.1 后缀），主界面不再显示"
                else -> "已导入 $ok 个；源文件已删除，相册与主界面都不再显示"
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val ok = runCatching { store.delete(id) }.getOrDefault(false)
            _message.value = if (ok) "已删除" else "删除失败"
        }
    }

    /** 给播放器用的自定义 scheme 地址。 */
    fun uriFor(entry: VaultEntry): Uri = store.uriFor(entry.id)

    /**
     * 从 content URI 里抠出显示名。
     *
     * 不引第三方库取文件名：ContentResolver 的 DISPLAY_NAME 列从 API 1 就有，
     * 只是要记得 cursor 用完就关。
     */
    private suspend fun queryDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /**
     * 导入成功后让源文件从主界面消失。
     *
     * 两条路：
     * 1. `ContentResolver.delete`：要求选择器在 ACTION_OPEN_DOCUMENT 时带了
     *    FLAG_GRANT_WRITE_URI_PERMISSION。外部存储提供方（ExternalStorageProvider）
     *    支持删除，返回删除的行数。
     * 2. `DocumentsContract.renameDocument`：只改显示名不改内容，几乎不需要额外权限，
     *    是最通用的「让它消失」手段。
     */
    private suspend fun hideSource(uri: Uri, name: String): HideResult = withContext(Dispatchers.IO) {
        runCatching {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) HideResult.DELETED else null
        }.getOrNull() ?: runCatching {
            val renamed = DocumentsContract.renameDocument(context.contentResolver, uri, "$name.1")
            if (renamed != null) HideResult.RENAMED else null
        }.getOrNull() ?: HideResult.KEPT
    }

    private enum class HideResult { DELETED, RENAMED, KEPT }
}