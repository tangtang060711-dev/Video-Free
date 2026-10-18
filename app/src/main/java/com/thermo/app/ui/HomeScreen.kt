package com.thermo.app.ui

import android.app.Activity
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermo.data.local.MediaEntry
import com.thermo.data.settings.DisplayMode
import java.io.File

/**
 * 首页：本地视频列表 + 打开链接 + 加密空间 + 设置。
 *
 * ## 为什么不直接拿系统文件选择器当主入口
 * 因为要多一步、多一层权限（SAF 的 `content://` URI 还要 `takePersistableUriPermission`）。
 * 自用播放器直接读 MediaStore 最快 —— 代价是首次要申请 `READ_MEDIA_VIDEO`。
 * 两个入口都留：列表点击走 MediaStore，「打开链接」里的选文件走 SAF。
 *
 * ## 多选
 * 长按任意一条进入多选，再点其它条加选/减选；顶部出现「已选 N 项」。
 * 多选可以**连播**，也可以**批量删除**（API 30+ 走系统删除确认框）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenLocal: (MediaEntry) -> Unit,
    onPlayAll: (List<MediaEntry>) -> Unit,
    onOpenUri: (Uri) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    displayMode: DisplayMode,
    showThumbnails: Boolean,
    onDisplayMode: (DisplayMode) -> Unit,
    onShowThumbnails: (Boolean) -> Unit,
    vm: LocalMediaViewModel = hiltViewModel(),
) {
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        vm.onPermissionResult(result.values.any { it })
    }

    // 「从文件管理器选」必须用 launcher 接结果。
    // 旧版直接 startActivity(ACTION_OPEN_DOCUMENT) —— 选择器弹出来了，
    // 但没有任何人接 onActivityResult，用户选完文件回到 App 什么都不发生。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onOpenUri(uri) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 多选删除：API 30+ 弹系统确认框（MediaStore.createDeleteRequest），
    // 用户点了「允许」才真的删；低版本直接尝试 contentResolver.delete。
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) selected = emptySet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        if (selected.isEmpty()) {
            Header(
                count = videos.size,
                hasPermission = hasPermission,
                onRequestPermission = { permissionLauncher.launch(vm.requiredPermissions) },
                onOpenUrl = { showUrlDialog = true },
                onOpenVault = onOpenVault,
                onOpenSettings = onOpenSettings,
                onOpenDownloads = onOpenDownloads,
            )
        } else {
            SelectionBar(
                count = selected.size,
                total = videos.size,
                onPlay = {
                    // 按列表顺序而不是点击顺序连播：用户看到的顺序就是播放顺序，
                    // 否则"先点第 5 个再点第 2 个"会莫名其妙从第 5 个开始。
                    onPlayAll(videos.filter { it.id in selected })
                    selected = emptySet()
                },
                onSelectAll = { selected = videos.map { it.id }.toSet() },
                onClear = { selected = emptySet() },
                onDelete = {
                    val targets = videos.filter { it.id in selected }
                    if (targets.isNotEmpty()) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val pi = MediaStore.createDeleteRequest(
                                context.contentResolver,
                                targets.map { it.uri },
                            )
                            runCatching {
                                deleteLauncher.launch(IntentSenderRequest.Builder(pi).build())
                            }
                        } else {
                            targets.forEach { entry ->
                                runCatching {
                                    context.contentResolver.delete(entry.uri, null, null)
                                }
                            }
                            selected = emptySet()
                        }
                    }
                },
            )
        }

        when {
            !hasPermission -> EmptyHint(
                title = "还没有读取媒体的权限",
                body = "本地视频列表需要它。也可以直接用下面的「打开链接」播网络地址，不需要任何权限。",
            )
            videos.isEmpty() -> EmptyHint(
                title = "本机没扫到视频",
                body = "可能是真的没有，也可能是系统媒体库还没索引完成。可以从文件管理器直接「用 ThermoPlayer 打开」。",
            )
            else -> if (displayMode == DisplayMode.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(videos, key = { it.id }) { entry ->
                        val checked = entry.id in selected
                        MediaCard(
                            entry = entry,
                            showThumbnail = showThumbnails,
                            selectionMode = selected.isNotEmpty(),
                            checked = checked,
                            onClick = {
                                if (selected.isEmpty()) {
                                    onOpenLocal(entry)
                                } else {
                                    selected = if (checked) selected - entry.id else selected + entry.id
                                }
                            },
                            onLongClick = { selected = setOf(entry.id) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(videos, key = { it.id }) { entry ->
                        val checked = entry.id in selected
                        MediaRow(
                            entry = entry,
                            showThumbnail = showThumbnails,
                            selectionMode = selected.isNotEmpty(),
                            checked = checked,
                            onClick = {
                                if (selected.isEmpty()) {
                                    onOpenLocal(entry)
                                } else {
                                    selected = if (checked) selected - entry.id else selected + entry.id
                                }
                            },
                            onLongClick = {
                                // 长按进入多选。已经在多选里时长按 = 只留这一条。
                                selected = setOf(entry.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        UrlDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = {
                showUrlDialog = false
                onOpenUrl(it)
            },
            onPickFile = {
                showUrlDialog = false
                runCatching { filePicker.launch(arrayOf("video/*", "audio/*")) }
            },
        )
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(
    count: Int,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenUrl: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ThermoPlayer",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (hasPermission) "本地 $count 个视频 · 硬解优先 · 发热更低" else "硬解优先 · 发热更低",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 右上角的设置入口。现在里面是四个开关，
            // 但按钮先放在这里，后续加功能不用再动布局。
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .size(40.dp)
                    .combinedClickable(onClick = onOpenSettings),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    IconGear(tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasPermission) {
                Button(onClick = onRequestPermission) { Text("授权读取") }
            }
            Button(onClick = onOpenUrl) { Text("打开链接") }
            Button(onClick = onOpenVault) { Text("加密空间") }
            Button(onClick = onOpenDownloads) { Text("下载") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "长按任意一条可多选连播",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 多选模式下的顶栏。
 *
 * 刻意把它和 [Header] 分开而不是加个 if：多选时"标题 + 三个按钮"全都没有意义，
 * 留着只会让用户误点。顶栏一换，用户立刻知道"进入另一个模式了"。
 */
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onPlay: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "已选 $count 项",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, enabled = count > 0) { Text("播放") }
            if (count < total) {
                Button(onClick = onSelectAll) { Text("全选") }
            }
            Button(onClick = onDelete, enabled = count > 0) { Text("删除") }
            TextButton(onClick = onClear) { Text("取消") }
        }
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    entry: MediaEntry,
    showThumbnail: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                CheckDot(checked = checked)
                Spacer(Modifier.width(10.dp))
            }
            if (showThumbnail) {
                Thumbnail(entry, modifier = Modifier.width(56.dp).height(40.dp))
            } else {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.resolutionLabel ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.durationLabel + " · " + humanSize(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 列表行 / 平铺卡片共用的缩略图。
 *
 * 用 [MediaMetadataRetriever] 抽第一帧：MediaStore 的缩略图列在部分 ROM 上是空的，
 * 而框架自带 API 不引任何依赖。代价是"读一帧就释放"，几百条视频时关掉缩略图
 * 可以让低端机进列表明显更快 —— 见设置的「显示缩略图」开关。
 */
@Composable
private fun Thumbnail(entry: MediaEntry, modifier: Modifier = Modifier) {
    var bitmap by remember(entry.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.id) {
        val retriever = MediaMetadataRetriever()
        bitmap = runCatching {
            retriever.setDataSource(entry.uri.toString())
            // 抽时长 10% 处的一帧（至少 1 秒），避免片头黑帧/过场帧；
            // API 27+ 直接缩到 320x180，几百条视频也不会把内存顶爆。
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val atUs = ((durationMs / 10).coerceAtLeast(1_000L)) * 1_000L
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    atUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    320,
                    180,
                )
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull()
        runCatching { retriever.release() }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = entry.displayName,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.resolutionLabel ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 平铺模式的一格卡片：大缩略图 + 文件名 + 时长/大小。
 * 多选时右上角画 [CheckDot]，选中态用主题色打底。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    entry: MediaEntry,
    showThumbnail: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                if (showThumbnail) {
                    Thumbnail(entry, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = entry.resolutionLabel ?: "—",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (selectionMode) {
                    CheckDot(
                        checked = checked,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.durationLabel + " · " + humanSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
/** 多选用的圆点。刻意自己画而不是用 Checkbox：一个 20dp 的圆比 M3 的复选框少两层 Box。 */
@Composable
private fun CheckDot(checked: Boolean, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(20.dp)) {
        val r = size.minDimension / 2f
        if (checked) {
            drawCircle(color = on, radius = r)
            // 对勾
            val w = size.minDimension / 20f
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(4.5f * w, 10f * w)
                lineTo(8.5f * w, 14f * w)
                lineTo(15.5f * w, 6.5f * w)
            }
            drawPath(p, Color.Black, style = Stroke(width = 2.2f * w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        } else {
            drawCircle(color = off, radius = r - 1f, style = Stroke(width = 1.6f))
        }
    }
}

/** 右上角的齿轮。自绘：:ui 的图标集里没有齿轮，为一个按钮加一个依赖不划算。 */
@Composable
private fun IconGear(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.minDimension / 24f
        val c = Offset(size.width / 2f, size.height / 2f)
        repeat(8) { i ->
            rotate(degrees = i * 45f, pivot = c) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(c.x - 1.6f * w, c.y - 10.6f * w),
                    size = Size(3.2f * w, 4.4f * w),
                    cornerRadius = CornerRadius(1.5f * w),
                )
            }
        }
        drawCircle(color = tint, radius = 6.6f * w, center = c, style = Stroke(width = 2.2f * w))
        drawCircle(color = tint, radius = 1.7f * w, center = c)
    }
}
@Composable
private fun UrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onPickFile: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开链接") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("https://... 或 /sdcard/Movies/a.mkv") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持 http/https/HLS/DASH，也支持 file:// 与本地路径。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onPickFile) {
                    Text("从文件管理器选")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalize(text)) },
                enabled = text.isNotBlank(),
            ) { Text("播放") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 把用户输入变成 URI 字符串。
 * 三条规则，按优先级：已是 scheme 的原样用；看起来像绝对路径的补 `file://`；其余当 http。
 */
private fun normalize(input: String): String {
    val t = input.trim()
    if (t.contains("://")) return t
    if (t.startsWith("/") || t.matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
        return Uri.fromFile(File(t)).toString()
    }
    return "https://$t"
}

private fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    bytes < 1024L * 1024L * 1024L -> (bytes / (1024L * 1024L)).toString() + " MB"
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
