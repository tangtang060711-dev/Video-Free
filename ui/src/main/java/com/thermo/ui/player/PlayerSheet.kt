package com.thermo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.thermo.player.QualityProfile
import com.thermo.ui.util.frameRateLabel
import com.thermo.ui.util.resolutionLabel
import com.thermo.ui.util.toBitrateLabel
import com.thermo.ui.util.toCodecLabel
import com.thermo.ui.util.toSpeedLabel

/** 底部面板的四个页签。 */
enum class SheetTab(val title: String) {
    TRACKS("音轨"),
    SUBTITLES("字幕"),
    QUALITY("画质"),
    SPEED("倍速"),
}

/**
 * 自绘底部面板。
 *
 * 为什么不用 M3 的 [androidx.compose.material3.ModalBottomSheet]：
 * 它内部有一套自己的 scrim / 手势收起 / `SwipeableV2` 状态机，
 * 在**播放页**这种全屏沉浸场景里会和播放器手势抢事件（想上滑收起面板，
 * 结果被外层的音量手势吃掉）。自己画 60 行，行为完全可控，
 * 而且少一次 `rememberModalBottomSheetState` 的动画帧调度。
 */
@Composable
fun PlayerSheet(
    tab: SheetTab?,
    tracks: Tracks,
    disabledTrackTypes: Set<Int>,
    quality: QualityProfile,
    speed: Float,
    subtitleEnabled: Boolean,
    onSelectTrack: (Tracks.Group, Int) -> Unit,
    onClearOverride: (Int) -> Unit,
    onSetTrackTypeDisabled: (Int, Boolean) -> Unit,
    onPickQuality: (Int) -> Unit,
    onPickSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = tab != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onDismiss),
        ) {
            AnimatedVisibility(
                visible = tab != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 面板最多占屏幕 70%，剩下的地方仍然能看见画面（不容易误以为卡了）
                        .heightIn(max = 560.dp)
                        // 吃掉点击，避免穿透到下面的 scrim 把面板关掉
                        .clickable(enabled = false) {},
                ) {
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        SheetHeader(tab ?: SheetTab.TRACKS, onDismiss)
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                        ) {
                            when (tab) {
                                SheetTab.TRACKS -> TrackList(
                                    tracks = tracks,
                                    types = listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                                    disabled = disabledTrackTypes,
                                    onSelect = onSelectTrack,
                                    onClearOverride = onClearOverride,
                                    onSetDisabled = onSetTrackTypeDisabled,
                                )
                                SheetTab.SUBTITLES -> SubtitlePage(
                                    tracks = tracks,
                                    disabled = disabledTrackTypes,
                                    enabled = subtitleEnabled,
                                    onSelect = onSelectTrack,
                                    onClearOverride = onClearOverride,
                                    onSetDisabled = onSetTrackTypeDisabled,
                                )
                                SheetTab.QUALITY -> QualityList(current = quality, onPick = onPickQuality)
                                SheetTab.SPEED -> SpeedList(current = speed, onPick = onPickSpeed)
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(tab: SheetTab, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tab.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "关闭",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowItem(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 24.dp),
    )
}

// ---------------------------------------------------------------- 轨道

@Composable
private fun TrackList(
    tracks: Tracks,
    types: List<Int>,
    disabled: Set<Int>,
    onSelect: (Tracks.Group, Int) -> Unit,
    onClearOverride: (Int) -> Unit,
    onSetDisabled: (Int, Boolean) -> Unit,
) {
    val groups = tracks.getGroups()
    var printedAny = false
    types.forEach { type ->
        val ofType = groups.filter { it.getType() == type }
        if (ofType.isEmpty()) return@forEach
        printedAny = true
        val typeName = when (type) {
            C.TRACK_TYPE_AUDIO -> "音轨"
            C.TRACK_TYPE_VIDEO -> "视频轨"
            else -> "轨道"
        }
        val typeDisabled = disabled.contains(type)
        SectionTitle("$typeName（${ofType.sumOf { it.length }}）")
        // "自动"：清掉手动 override，交回给 TrackSelector 按能力/带宽自己挑。
        // 对低发热来说，视频轨尤其应该保持自动 —— 手动锁一条高码率会让 ABR 失效。
        RowItem(
            title = "自动选择",
            subtitle = if (type == C.TRACK_TYPE_VIDEO) "按设备硬解能力与带宽自选" else null,
            selected = ofType.none { it.isSelected() },
            trailing = {
                Switch(
                    checked = !typeDisabled,
                    onCheckedChange = { onSetDisabled(type, !it) },
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                )
            },
            onClick = { onClearOverride(type) },
        )
        ofType.forEach { group ->
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                RowItem(
                    title = formatTitle(f, i),
                    subtitle = formatSubtitle(f),
                    selected = group.isTrackSelected(i),
                    enabled = !typeDisabled,
                    onClick = { onSelect(group, i) },
                )
            }
        }
    }
    if (!printedAny) EmptyHint("这条媒体没有可切换的轨道。")
}

@Composable
private fun SubtitlePage(
    tracks: Tracks,
    disabled: Set<Int>,
    enabled: Boolean,
    onSelect: (Tracks.Group, Int) -> Unit,
    onClearOverride: (Int) -> Unit,
    onSetDisabled: (Int, Boolean) -> Unit,
) {
    val typeDisabled = disabled.contains(C.TRACK_TYPE_TEXT)
    SectionTitle("字幕")
    RowItem(
        title = "关闭字幕",
        subtitle = if (typeDisabled) null else "字幕轨默认不自动选择（见 TrackSelectionFactory）",
        selected = typeDisabled,
        trailing = {
            Switch(
                checked = !typeDisabled,
                onCheckedChange = { onSetDisabled(C.TRACK_TYPE_TEXT, !it) },
                modifier = Modifier.size(width = 44.dp, height = 28.dp),
            )
        },
    )
    Text(
        text = "提示：本工程只渲染**内封字幕**（MKV/MP4 的 text 轨）。外部 .srt/.ass 文件、" +
            "M3U8 的 WebVTT、以及 ASS 的复杂排版需要额外的字幕解析与渲染，" +
            "Media3 的 PlayerView.getSubtitleView() 能直接吃 text/3gpp，其余格式要自己转 Cue。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
    )
    if (!enabled) {
        // 这里只是提示，不做任何自动打开；避免用户没点就莫名其妙出字幕
    }
    SectionTitle("字幕轨")
    TrackList(
        tracks = tracks,
        types = listOf(C.TRACK_TYPE_TEXT),
        disabled = disabled,
        onSelect = onSelect,
        onClearOverride = onClearOverride,
        onSetDisabled = onSetDisabled,
    )
}

private fun formatTitle(f: Format, index: Int): String =
    f.label?.takeIf { it.isNotBlank() }
        ?: f.language?.takeIf { it.isNotBlank() }
        ?: f.sampleMimeType.toCodecLabel()
        ?: "轨道 ${index + 1}"

private fun formatSubtitle(f: Format): String? {
    val parts = mutableListOf<String>()
    f.sampleMimeType.toCodecLabel().takeIf { it.isNotBlank() }?.let { parts += it }
    resolutionLabel(f.width, f.height)?.let { parts += it }
    frameRateLabel(f.frameRate)?.let { parts += it }
    if (f.channelCount > 0) parts += "${f.channelCount}ch"
    if (f.sampleRate > 0) parts += "${f.sampleRate}Hz"
    if (f.bitrate > 0) parts += f.bitrate.toBitrateLabel()
    f.language?.takeIf { it.isNotBlank() && f.label.isNullOrBlank() }?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

// ---------------------------------------------------------------- 画质

@Composable
private fun QualityList(current: QualityProfile, onPick: (Int) -> Unit) {
    val tiers = listOf(
        0 to ("原画 / 不限" to "不限制分辨率与码率；网络流仍由 ABR 自选"),
        1 to ("1080p" to "限制到 1920x1080 · 8Mbps"),
        2 to ("720p" to "限制到 1280x720 · 3Mbps · 30fps（明显省电档）"),
        3 to ("480p / 最省电" to "限制到 854x480 · 1.2Mbps · 30fps，并禁止非无缝切换"),
    )
    val currentTier = when (current) {
        QualityProfile.UNRESTRICTED -> 0
        QualityProfile.CAP_1080P -> 1
        QualityProfile.CAP_720P -> 2
        QualityProfile.LOWEST -> 3
        else -> -1
    }
    SectionTitle("画质上限")
    tiers.forEach { (tier, pair) ->
        RowItem(
            title = pair.first,
            subtitle = pair.second,
            selected = tier == currentTier,
            onClick = { onPick(tier) },
        )
    }
    Text(
        text = "画质约束走的是 Media3 的 TrackSelectionParameters：它只在**多码率自适应流**" +
            "（HLS/DASH）上真正改变码率；单文件 MP4 没有可选项，这条约束只会影响" +
            "轨道筛选条件。想真正降功耗，请配合省电模式与省电档一起用。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------- 倍速

@Composable
private fun SpeedList(current: Float, onPick: (Float) -> Unit) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    SectionTitle("播放速度")
    speeds.forEach { s ->
        RowItem(
            title = s.toSpeedLabel() + if (s == 1.0f) "（正常）" else "",
            subtitle = if (s > 1.0f) "加速播放会提高音频输出采样率转换开销" else null,
            selected = kotlin.math.abs(s - current) < 0.01f,
            onClick = { onPick(s) },
        )
    }
    Spacer(Modifier.height(8.dp))
}