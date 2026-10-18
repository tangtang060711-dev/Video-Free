package com.thermo.app.download

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermo.data.download.DownloadStatus
import com.thermo.data.download.DownloadTask
import kotlinx.coroutines.delay

/**
 * 下载页。
 *
 * 布局刻意跟首页保持一致（同一套圆角 Surface、同一套字号），
 * 它只是另一个列表，不该长得像另一个应用。
 *
 * 进度条自己用两个 Box 叠，不用 LinearProgressIndicator：
 * 后者每次都跑一遍动画调度器，而这个页面在有任务下载时每秒要重组十几次
 * （下载进度是每片一更新的）。画一条 3dp 的矩形比驱动一个动画便宜得多。
 */
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlay: (Uri, String) -> Unit,
    vm: DownloadViewModel = hiltViewModel(),
) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val threads by vm.threads.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    // 提示 4 秒自动消失。与播放页的消息一致。
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000L)
            vm.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1013))
            .statusBarsPadding(),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "返回",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "下载",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // 线程数只显示不在这里改：它是全局设置，改的地方在设置页。
            // 放在这儿是让用户对"这文件为什么这么快/这么慢"有个解释。
            Text(
                text = "${threads} 线程",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(12.dp))
        }

        // ---- 添加地址 ----
        UrlInputRow(
            value = input,
            onValueChange = { input = it },
            onAdd = {
                vm.enqueue(input)
                input = ""
            },
        )

        Spacer(Modifier.height(6.dp))

        // ---- 任务列表 ----
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有下载任务",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "粘贴一个 http/https 视频地址，或从播放页菜单里下载",
                        color = Color.White.copy(alpha = 0.42f),
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onPause = { vm.pause(task.id) },
                        onResume = { vm.resume(task.id) },
                        onCancel = { vm.cancel(task.id) },
                        onPlay = {
                            val uri = vm.uriFor(task.id)
                            if (uri == null) {
                                vm.reportMissingFile()
                            } else {
                                onPlay(uri, task.title)
                            }
                        },
                    )
                }
            }
        }
    }

    // ---- 底部一次性消息 ----
    val msg = message
    if (msg != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 40.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = Color(0xFF23262B),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
/**
 * 地址输入行。
 *
 * 用 BasicTextField 而不是 OutlinedTextField：后者自带 label 浮动动画、
 * 焦点边框动画、下划线动画三套状态机，这个输入框只在用户手输地址时活着，
 * 不值得为它多跑三个动画。
 */
@Composable
private fun UrlInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            color = Color(0xFF16191D),
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "粘贴视频地址（http/https）",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            color = if (value.isBlank()) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.primary
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.clickable(enabled = value.isNotBlank()) { onAdd() },
        ) {
            Text(
                text = "添加",
                color = Color(0xFF0E1013),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** 一条下载任务。 */
@Composable
private fun DownloadRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF16191D),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // ---- 标题 + 状态 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel(task),
                    color = statusColor(task),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---- 进度条 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                if (task.progress > 0f || task.isDone) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (task.isDone) 1f else task.progress)
                            .fillMaxHeight()
                            .background(
                                if (task.status == DownloadStatus.FAILED) {
                                    Color(0xFFFF5A5A)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---- 详情 + 操作 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sizeLabel(task),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                    val err = task.error
                    if (err != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = err,
                            color = Color(0xFFFFC46B),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (!task.supportsRange && task.status != DownloadStatus.DONE && task.totalBytes > 0L) {
                        // 这个提示值得占一行：不支持 Range 的服务器上，
                        // 断了就得从头下，用户有权知道。
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "服务器不支持断点续传",
                            color = Color.White.copy(alpha = 0.38f),
                            fontSize = 11.sp,
                        )
                    }
                }

                if (task.status == DownloadStatus.DONE) {
                    MiniButton(text = "播放", primary = true, onClick = onPlay)
                    Spacer(Modifier.width(8.dp))
                    MiniButton(text = "删除", primary = false, onClick = onCancel)
                } else if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.PAUSED) {
                    MiniButton(text = "继续", primary = true, onClick = onResume)
                    Spacer(Modifier.width(8.dp))
                    MiniButton(text = "取消", primary = false, onClick = onCancel)
                } else {
                    MiniButton(text = "暂停", primary = false, onClick = onPause)
                    Spacer(Modifier.width(8.dp))
                    MiniButton(text = "取消", primary = false, onClick = onCancel)
                }
            }
        }
    }
}

/** 小胶囊按钮。下载页里每行两个，用 Material 的 Button 会带一堆内边距。 */
@Composable
private fun MiniButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (primary) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            Color.White.copy(alpha = 0.08f)
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = text,
            color = if (primary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// ------------------------------------------------------------------ 文案

private fun statusLabel(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> "${(task.progress * 100f).toInt()}%"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.DONE -> "已完成"
    DownloadStatus.FAILED -> "失败"
}

private fun statusColor(task: DownloadTask): Color = when (task.status) {
    DownloadStatus.DONE -> Color(0xFF6FE3C4)
    DownloadStatus.FAILED -> Color(0xFFFF5A5A)
    DownloadStatus.RUNNING -> Color.White
    else -> Color.White.copy(alpha = 0.55f)
}

/**
 * "123.4 MB / 2.1 GB" 或 "123.4 MB"。
 *
 * 总长未知时只显示已下载 —— 显示 "0%" 会让人以为它没动。
 */
private fun sizeLabel(task: DownloadTask): String {
    val done = humanSize(task.downloadedBytes)
    return if (task.totalBytes > 0L) {
        "$done / ${humanSize(task.totalBytes)}"
    } else {
        done
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${(kb * 10).toInt() / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024.0) return "${(mb * 10).toInt() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 100).toInt() / 100.0} GB"
}