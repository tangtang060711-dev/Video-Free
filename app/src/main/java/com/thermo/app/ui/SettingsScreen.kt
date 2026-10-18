package com.thermo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermo.data.settings.DisplayMode
import com.thermo.data.settings.OrientationMode
import com.thermo.ui.PlayerViewModel

/**
 * 设置页。
 *
 * 每一项的作用**不再铺在标题下面**，而是收进标题右侧那个带圈问号里（优化 3）。
 * 理由：这些说明动辄三四行，全部铺开之后一屏只能放两项，用户要滚半天才能找到
 * 想改的那个开关；而真正想看说明的人是少数。收进弹窗后，"扫一眼找开关"和
 * "认真读说明"变成了两条互不干扰的路径。
 *
 * 这个页面同时是给后续功能预留的位置：新增设置项只要往 [Section] 里加一行。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    /** 当前方向策略（存在 :data 的 ThermoSettings 里）。 */
    orientationMode: OrientationMode,
    onOrientationMode: (OrientationMode) -> Unit,
    displayMode: DisplayMode,
    showThumbnails: Boolean,
    onDisplayMode: (DisplayMode) -> Unit,
    onShowThumbnails: (Boolean) -> Unit,
    vm: PlayerViewModel = hiltViewModel(),
) {
    val powerSave by vm.powerSaveMode.collectAsStateWithLifecycle()
    val followFrameRate by vm.followFrameRate.collectAsStateWithLifecycle()
    val allowSoftware by vm.allowSoftwareFallback.collectAsStateWithLifecycle()
    val backgroundPlayback by vm.backgroundPlayback.collectAsStateWithLifecycle()
    val audioOnlyBg by vm.audioOnlyInBackground.collectAsStateWithLifecycle()
    val threads by vm.downloadThreads.collectAsStateWithLifecycle()
    val longPressSpeed by vm.longPressSpeed.collectAsStateWithLifecycle()

    // 当前打开的是哪一条说明；null = 没开弹窗。
    var help by remember { mutableStateOf<Help?>(null) }
    // 方向选择弹窗。
    var orientationPicker by remember { mutableStateOf(false) }
    // 显示方式选择弹窗。
    var displayPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Section(title = "播放") {
            SwitchRow(
                title = "视频后台播放",
                help = Help.BACKGROUND,
                checked = backgroundPlayback,
                onCheckedChange = { vm.setBackgroundPlayback(it) },
                onHelp = { help = Help.BACKGROUND },
            )
            SwitchRow(
                title = "后台仅音频",
                help = Help.AUDIO_ONLY,
                checked = audioOnlyBg,
                onCheckedChange = { vm.setAudioOnlyInBackground(it) },
                // 上级开关没开时这一项没有意义，置灰但不隐藏：
                // 隐藏会让人以为这个功能不存在，置灰才看得出"它依赖上一项"。
                enabled = backgroundPlayback,
                onHelp = { help = Help.AUDIO_ONLY },
            )
            FloatStepperRow(
                title = "长按加速倍速",
                help = Help.BOOST_SPEED,
                value = longPressSpeed,
                min = 1.5f,
                max = 5f,
                step = 0.5f,
                onValueChange = { vm.setLongPressSpeed(it) },
                onHelp = { help = Help.BOOST_SPEED },
            )
        }

        Section(title = "方向") {
            ChoiceRow(
                title = "屏幕方向",
                help = Help.ORIENTATION,
                value = orientationMode.label,
                onHelp = { help = Help.ORIENTATION },
                onClick = { orientationPicker = true },
            )
        }

        Section(title = "省电") {
            SwitchRow(
                title = "省电模式",
                help = Help.POWER_SAVE,
                checked = powerSave,
                onCheckedChange = { vm.setPowerSaveMode(it) },
                onHelp = { help = Help.POWER_SAVE },
            )
        }

        Section(title = "显示") {
            SwitchRow(
                title = "跟随视频帧率",
                help = Help.FRAME_RATE,
                checked = followFrameRate,
                onCheckedChange = { vm.applyFrameRatePreference(it) },
                onHelp = { help = Help.FRAME_RATE },
            )
            ChoiceRow(
                title = "显示方式",
                help = Help.DISPLAY_MODE,
                value = displayMode.label,
                onHelp = { help = Help.DISPLAY_MODE },
                onClick = { displayPicker = true },
            )
            SwitchRow(
                title = "显示缩略图",
                help = Help.THUMBNAILS,
                checked = showThumbnails,
                onCheckedChange = onShowThumbnails,
                onHelp = { help = Help.THUMBNAILS },
            )
        }

        Section(title = "解码") {
            SwitchRow(
                title = "允许软解兜底",
                help = Help.SOFTWARE,
                checked = allowSoftware,
                onCheckedChange = { vm.setAllowSoftwareFallback(it) },
                onHelp = { help = Help.SOFTWARE },
            )
        }

        Section(title = "下载") {
            StepperRow(
                title = "下载线程数",
                help = Help.THREADS,
                value = threads,
                min = 1,
                max = 32,
                onValueChange = { vm.setDownloadThreads(it) },
                onHelp = { help = Help.THREADS },
            )
            ActionRow(
                title = "下载管理",
                subtitle = "查看进行中与已完成的离线任务",
                onClick = onOpenDownloads,
            )
        }

        Section(title = "关于") {
            InfoRow("播放内核", "Jetpack Media3 1.9.4")
            InfoRow("软解兜底", "FFmpeg 7.1（只含老编解码器与音频）")
            InfoRow("低发热策略", "SurfaceView 直渲 · 硬解优先 · 按场景分级缓冲 · 三级温度降级")
            Spacer(Modifier.height(24.dp))
        }
    }

    val shown = help
    if (shown != null) {
        HelpDialog(help = shown, onDismiss = { help = null })
    }

    if (orientationPicker) {
        OptionDialog(
            title = "屏幕方向",
            selected = orientationMode,
            onPick = {
                onOrientationMode(it)
                orientationPicker = false
            },
            onDismiss = { orientationPicker = false },
        )
    }

    if (displayPicker) {
        DisplayDialog(
            title = "显示方式",
            selected = displayMode,
            onPick = {
                onDisplayMode(it)
                displayPicker = false
            },
            onDismiss = { displayPicker = false },
        )
    }
}
/**
 * 每一项设置的作用说明。
 *
 * 单独抽成枚举而不是写成 [SwitchRow] 的一个 String 参数，是为了让
 * "文案"和"界面结构"分开：以后要加多语言、或者要把这些文字搬到别处
 * （比如首次启动的引导页），改这一个地方就够。
 */
private enum class Help(val title: String, val body: String) {
    BACKGROUND(
        "视频后台播放",
        "关闭（默认）：退到桌面或按 Home 键时立即暂停播放，回到应用也不会自动续播。\n\n" +
            "打开：退到桌面后继续播放。此时若「后台仅音频」也开着，画面会被摘掉，" +
            "只留声音 —— 适合听播客、听网课。\n\n" +
            "注意它和「从播放页返回列表页」是两件事：后者是用户主动结束这一轮播放，" +
            "无论这个开关怎么设，返回时都会停止播放。",
    ),
    AUDIO_ONLY(
        "后台仅音频",
        "只在「视频后台播放」打开时才有意义，默认关。\n\n" +
            "打开后，退到后台会把视频 Surface 摘掉、释放视频解码器，只保留音频解码器，" +
            "功耗从 1.5~2.5W 降到 0.1~0.3W。\n\n" +
            "这是本应用里单点收益最大的省电策略。代价是回到前台时会有一瞬间重新挂载画面。",
    ),
    POWER_SAVE(
        "省电模式",
        "立即把画质上限压到 1080p、关掉 90/120Hz 高刷，并缩小预加载缓冲。\n\n" +
            "它和温度自动降级是取交集，而不是互相覆盖 —— 关掉它，手机烫了照样会降级；" +
            "打开它，也不会因为温度降下来就自动解除。",
    ),
    FRAME_RATE(
        "跟随视频帧率",
        "把屏幕刷新率切到与视频帧率成整数倍的那一档。\n\n" +
            "关掉不是「不切换刷新率」，而是「不许挑 60Hz 以上的档」：" +
            "24fps 的电影钉在 120Hz 上，屏幕每秒要多刷 96 次，那些刷新什么也没显示。\n\n" +
            "部分机型的厂商显示管线有自己的高刷白名单，可能覆盖本设置。",
    ),
    SOFTWARE(
        "允许软解兜底",
        "硬解不支持这个编码时，才退回 CPU 解码。\n\n" +
            "软解功耗是硬解的数倍，只建议在硬解确实失败、而你又必须看这一条的时候打开。\n\n" +
            "关掉它的含义是「宁可看不了，也别把手机烫着」—— 硬解起不来时会直接报错。" +
            "注意：H.264 / H.265 / VP9 / AV1 的软解码器根本没有编进 FFmpeg 库，" +
            "所以这四种编码关掉这个开关后就是真的播不了。",
    ),
    ORIENTATION(
        "屏幕方向",
        "跟随系统（默认）：系统那个「自动旋转」开着才自动转，关着就跟系统的锁定方向走。" +
            "无论哪种情况都不会出现上下颠倒的画面。\n\n" +
            "竖屏 / 横屏：手动钉死在某个方向，适合把手机架起来看的时候。\n\n" +
            "竖屏·倒转 / 横屏·倒转：给躺床上看片准备的。手机接近平放时，" +
            "重力在屏幕平面内的分量很小，传感器分不出你朝哪边躺 —— " +
            "这是物理上的信息缺失，不是算法能补的。此时用这两个选项人工把画面转过来。\n\n" +
            "播放页右上角的旋转按钮可以在这些档位之间快速切换，" +
            "离开播放页就会回到这里设的值，不会被临时改动带跑。",
    ),
    THREADS(
        "下载线程数",
        "把一个文件切成多少个分片并行下载，范围 1~32，默认 8。\n\n" +
            "服务器必须支持 Range 请求（HTTP 206）才会真正并行；" +
            "不支持时会自动退化成单连接，此时改这个数字没有效果。\n\n" +
            "线程越多越快，但并发写入 flash 的次数也越多 —— 下载本身也是发热源，" +
            "32 线程并不总是比 16 快。",
    ),
    BOOST_SPEED(
        "长按加速倍速",
        "播放时按住屏幕任意位置，会临时切到这个倍速，松手还原。\n\n" +
            "范围 1.5~5.0，默认 2.0。倍速越高解码器越费电，软解时尤其明显。" +
            "3 倍以上基本只有硬解能扛住。",
    ),
    DISPLAY_MODE(
        "显示方式",
        "列表：一行一个视频，信息密度最高，适合按名字找。\n\n" +
            "平铺：两列卡片，以缩略图为视觉主体，适合快速翻找。\n\n" +
            "改动立刻生效，不需要重启。",
    ),
    THUMBNAILS(
        "显示缩略图",
        "列表和平铺都会在视频旁显示第一帧缩略图。\n\n" +
            "关掉后改成分辨率占位块。缩略图抽帧是「读一帧就释放」的轻操作，" +
            "但视频一多，每次进列表都要抽一轮，低端机上能感到加载 —— 那时可以关掉它。",
    ),
}

/** 一个带标题的设置分组。新增设置项只要往里面加行。 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    help: Help,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowTitle(title = title, enabled = enabled, onHelp = onHelp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 标题 + 右侧带圈问号。
 *
 * 说明文字收进弹窗之后，标题行只剩下"名字"这一件事，
 * 所以问号必须紧贴名字 —— 放行尾会被右侧的开关挤走，让人看不出它属于哪一项。
 */
@Composable
private fun RowTitle(
    title: String,
    enabled: Boolean,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            },
        )
        Spacer(Modifier.width(6.dp))
        HelpDot(onClick = onHelp)
    }
}

/**
 * 带圈问号。
 *
 * 用一个 border 画出圆圈、中间放一个字号 9sp 的 "?"，而不是 Canvas 手绘问号：
 * 问号是字形不是几何图形，几段直线画不出可读的弧度，交给字体渲染最省事也最好看。
 * 外层 24dp 的可点区域是为了满足触摸目标下限，视觉上只有 16dp。
 */
@Composable
private fun HelpDot(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(width = 1.2.dp, color = tint, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "?", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 数字步进行（下载线程数）。刻意不用 Slider：1~32 的整数用拖动很难停在想要的数上。 */
@Composable
private fun StepperRow(
    title: String,
    help: Help,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTitle(title = title, enabled = true, onHelp = onHelp, modifier = Modifier.weight(1f))
        StepButton(text = "−", enabled = value > min) { onValueChange((value - 1).coerceAtLeast(min)) }
        Text(
            text = value.toString(),
            modifier = Modifier.width(34.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        StepButton(text = "+", enabled = value < max) { onValueChange((value + 1).coerceAtMost(max)) }
    }
}

/** 浮点步进行（长按加速倍速）。与 StepperRow 分开写：1.5 这种值不能简单 ±1。 */
@Composable
private fun FloatStepperRow(
    title: String,
    help: Help,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit,
    onHelp: () -> Unit,
) {
    val text = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTitle(title = title, enabled = true, onHelp = onHelp, modifier = Modifier.weight(1f))
        StepButton(text = "−", enabled = value > min + 0.001f) {
            onValueChange((value - step).coerceAtLeast(min))
        }
        Text(
            text = text + "x",
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        StepButton(text = "+", enabled = value < max - 0.001f) {
            onValueChange((value + step).coerceAtMost(max))
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** 可点击的跳转行（右侧一个 ›）。 */
@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = "›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpDialog(help: Help, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = help.title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(
                text = help.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
/**
 * 单选行：右侧显示当前值，点一下弹选择框。
 *
 * 刻意不用下拉菜单（DropdownMenu）：设置页本身在 verticalScroll 里，
 * 下拉菜单的锚点定位依赖窗口坐标，页面一滚就会错位；而它的滚动也是嵌套的。
 * 弹窗没有这两个问题，而且能把每个选项的解释一起放进去。
 */
@Composable
private fun ChoiceRow(
    title: String,
    help: Help,
    value: String,
    onHelp: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTitle(title = title, enabled = true, onHelp = onHelp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(2.dp))
        Text(text = "›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 屏幕方向选择框。
 *
 * 每一档都直接把 [OrientationMode.label] 列出来，因为这几个档位的区别
 * 不看文字是猜不出来的（尤其「竖屏·倒转」这种）。
 */
@Composable
private fun OptionDialog(
    title: String,
    selected: OrientationMode,
    onPick: (OrientationMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OrientationMode.ALL.forEach { mode ->
                    val picked = mode == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(mode) }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (picked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (picked) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 显示方式选择框（列表 / 平铺）。
 */
@Composable
private fun DisplayDialog(
    title: String,
    selected: DisplayMode,
    onPick: (DisplayMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                DisplayMode.ALL.forEach { mode ->
                    val picked = mode == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(mode) }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (picked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (picked) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
