package com.thermo.app.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermo.data.vault.VaultEntry

/**
 * 加密空间。
 *
 * ## 上锁时机
 * 只有两个：用户按"上锁"，或从这一页返回（返回列表页）。
 * **不**在离开 composition 时上锁 —— 点开视频会切到播放页，
 * VaultScreen 被移出组合，但这时数据源正在读加密文件，
 * 一上锁播放立刻断，用户看到的是"加密里的视频放不了"。
 * 也不在进程退到后台时上锁：后台继续听音频是明确支持的场景。
 *
 * 代价是：锁屏后进程还活着的话，加密空间仍处于解锁状态。
 * 这是刻意的取舍 —— 换来的是"息屏放在耳边听加密音频"这条路径可用。
 * 真要更严，把 ThermoApp 里的 ProcessLifecycleOwner 回调改成
 * "后台且没在播放就 lock()"即可，代码结构不用动。
 */
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpen: (Uri, String) -> Unit,
    vm: VaultViewModel = hiltViewModel(),
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val unlock = rememberVaultUnlock { ok -> if (ok) vm.onUnlocked() }

    // 不能用 ActivityResultContracts.OpenDocument()：那个合约只申请**读**权限，
    // 导入后没法删源文件。这里手动构造 Intent 把 WRITE 和 PERSISTABLE 一起带上，
    // 这样 VaultViewModel.import 才能先 delete、删不掉再 renameDocument。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uris = buildList {
            // 多选时 URI 挂在 clipData 里；单选时才在 data.data。
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { add(it) }
            }
        }
        vm.importAll(uris)
    }

    // 消息 4 秒后自动消失，与播放页的行为保持一致
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(4_000L)
            vm.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "加密空间",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (unlocked) "AES-256-CTR 加密存储 · ${entries.size} 个视频"
                else "用指纹或锁屏密码解锁",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!unlocked) {
                    Button(onClick = { unlock() }) { Text("解锁") }
                } else {
                    Button(
                        onClick = {
                            importLauncher.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "video/*"
                                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/octet-stream"))
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    addFlags(
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                                    )
                                },
                            )
                        },
                        enabled = !importing,
                    ) { Text(if (importing) "加密中…" else "导入视频") }
                    TextButton(onClick = { vm.lock() }) { Text("上锁") }
                }
            }
        }

        when {
            !unlocked -> VaultHint(
                title = "加密空间是锁着的",
                body = "解锁后才能看到里面的视频。解锁用的是系统凭据，" +
                    "本应用不保存你的密码，也无法在你没解锁时打开任何文件。",
            )

            entries.isEmpty() -> VaultHint(
                title = "还没有加密的视频",
                body = "点「导入视频」从文件或相册里选一个。导入是复制 + 加密，" +
                    "成功后源文件会被删除（没有删除权限时自动改名为「原名.1」），主界面不再显示。",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    VaultRow(
                        entry = entry,
                        onClick = { onOpen(vm.uriFor(entry), entry.name) },
                        onDelete = { vm.delete(entry.id) },
                    )
                }
            }
        }
    }

    if (message != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun VaultHint(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VaultRow(entry: VaultEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = humanSizeV(entry.sizeBytes) + " · 已加密",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

private fun humanSizeV(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}