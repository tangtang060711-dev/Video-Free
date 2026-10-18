package com.thermo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermo.player.PlayerSnapshot
import com.thermo.player.QualityProfile
import com.thermo.thermal.ThermalLevel
import com.thermo.ui.icon.IconFullscreen
import com.thermo.ui.icon.IconFullscreenExit
import com.thermo.ui.icon.IconLock
import com.thermo.ui.icon.IconPause
import com.thermo.ui.icon.IconPlay
import com.thermo.ui.icon.IconQuality
import com.thermo.ui.icon.IconSpeed
import com.thermo.ui.icon.IconSubtitles
import com.thermo.ui.icon.IconTracks
import com.thermo.ui.util.rememberBatteryPercent
import com.thermo.ui.util.toClock
import com.thermo.ui.util.toSpeedLabel

/**
 * 控件层。
 *
 * 关键约束：**这个 Composable 每 250ms 会被 [PlayerSnapshot] 重组一次**，
 * 所以它内部所有东西都必须便宜：
 *  - 不放 image/vector 资源，不 new Brush 之外的对象；
 *  - 进度条用 Canvas 画（一次 drawRect），不用 M3 Slider
 *    （Slider 内部有 ripple/交互态/动画，一次重组要分配十几个对象）；
 *  - 时间文字用 [toClock]（无 Formatter 分配）。
 */
@Composable
fun PlayerControlsOverlay(
    visible: Boolean,
    locked: Boolean,
    snapshot: PlayerSnapshot,
    title: String?,
    thermalLevel: ThermalLevel,
    powerSave: Boolean,
    quality: QualityProfile,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenQuality: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTogglePowerSave: () -> Unit,
    isFullscreen: Boolean,
    /** 当前方向档位的短标签，例如「跟随·横屏」「横屏」。 */
    orientationLabel: String,
    /** 点一下在可用方向之间轮换。 */
    onCycleOrientation: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 上下各一条渐变压暗，保证白色字幕/按钮在任何画面上都看得清。
                // 用 Brush.verticalGradient 一次画完，比两个半透明 Surface 少一个图层。
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.62f),
                        0.28f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        ) {
            // ---------------- 顶栏 ----------------
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // 优化 7：edge-to-edge 下顶栏会压到状态栏上。
                    // statusBarsPadding() 让出状态栏高度（约 24~30dp ≈ 72~90px），
                    // 再叠加原来的 10dp —— 比"下调 25px"更彻底，而且随机型自适应。
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallIconButton(onClick = onBack) { tint -> IconBackLike(tint) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                ) {
                    Text(
                        text = title ?: "未命名",
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(snapshot.videoSize.let { if (it.width > 0) "${it.width}x${it.height}" else "音频" })
                            append("  ·  ")
                            append(snapshot.lastSampleMimeLabel())
                            if (snapshot.isSoftwareDecoding) {
                                append("  ·  软解")
                            }
                        },
                        color = if (snapshot.isSoftwareDecoding) Color(0xFFFFC46B) else Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                // 方向：点一下在 竖屏 / 横屏 / 横屏(反) 之间轮换。
                Spacer(Modifier.width(6.dp))
                ThermalChip(level = thermalLevel, powerSave = powerSave)
                Spacer(Modifier.width(6.dp))
                SmallIconButton(onClick = onTogglePowerSave) { tint ->
                    IconLeaf2(tint, powerSave)
                }
                SmallIconButton(onClick = onToggleLock) { tint -> IconLock(tint = tint) }
            }

            // ---------------- 中央播放/暂停 ----------------
            if (!locked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { onTogglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (snapshot.isPlaying) {
                        IconPause(modifier = Modifier.size(28.dp), tint = Color.White)
                    } else {
                        IconPlay(modifier = Modifier.size(28.dp), tint = Color.White)
                    }
                }
            }

            // ---------------- 底栏 ----------------
            if (!locked) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    VideoProgressBar(
                        positionMs = snapshot.positionMs,
                        bufferedMs = snapshot.bufferedPositionMs,
                        durationMs = snapshot.durationMs,
                        onSeekTo = onSeekTo,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = snapshot.positionMs.toClock(),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = " / " + (if (snapshot.hasKnownDuration) snapshot.durationMs.toClock() else "--:--"),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButtonCompact(text = snapshot.playbackSpeed.toSpeedLabel(), onClick = onOpenSpeed)
                        Spacer(Modifier.width(4.dp))
                        SmallIconButton(onClick = onOpenTracks) { tint -> IconTracks(modifier = Modifier.size(20.dp), tint = tint) }
                        SmallIconButton(onClick = onOpenQuality) { tint ->
                            IconQuality(modifier = Modifier.size(20.dp), tint = if (quality == QualityProfile.UNRESTRICTED) tint else MaterialTheme.colorScheme.primary)
                        }
                        SmallIconButton(onClick = onOpenSubtitles) { tint -> IconSubtitles(modifier = Modifier.size(20.dp), tint = tint) }
                        SmallIconButton(onClick = onToggleFullscreen) { tint ->
                            if (isFullscreen) {
                                IconFullscreenExit(modifier = Modifier.size(20.dp), tint = tint)
                            } else {
                                IconFullscreen(modifier = Modifier.size(20.dp), tint = tint)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "单击 = 菜单  ·  双击 = 暂停/播放  ·  左右拖 = 快进退  ·  左半上下拖 = 亮度  ·  右半 = 音量",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // 锁定态：只留一个左上角的小锁，避免误触
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { onToggleLock() },
                    contentAlignment = Alignment.Center,
                ) {
                    IconLock(modifier = Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** 播放进度条。用 Canvas 画三段：已播 / 已缓冲 / 未播。 */
@Composable
private fun VideoProgressBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    // 拖动中先用本地值，松手才 seek —— 与手势同一套原则：不回 seek 打爆解码器。
    var dragging by remember { mutableFloatStateOf(-1f) }
    val safeDuration = if (durationMs > 0L) durationMs else 0L
    val playedFraction = if (safeDuration > 0L) {
        ((if (dragging >= 0f) dragging else positionMs.toFloat() / safeDuration).coerceIn(0f, 1f))
    } else {
        0f
    }
    val bufferedFraction = if (safeDuration > 0L) {
        (bufferedMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)   // 触摸热区比视觉高度大，手指才点得准
            .pointerInput(safeDuration) {
                if (safeDuration <= 0L) return@pointerInput
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeekTo((f * safeDuration).toLong())
                }
            }
            .pointerInput(safeDuration) {
                if (safeDuration <= 0L) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        if (dragging >= 0f) onSeekTo((dragging * safeDuration).toLong())
                        dragging = -1f
                    },
                    onDragCancel = { dragging = -1f },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            val cy = size.height / 2f
            val trackH = 3.dp.toPx()
            val radius = CornerRadius(trackH / 2f)
            // 未播
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            // 已缓冲
            drawRoundRect(
                color = Color.White.copy(alpha = 0.45f),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width * bufferedFraction, trackH),
                cornerRadius = radius,
            )
            // 已播
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width * playedFraction, trackH),
                cornerRadius = radius,
            )
            // 滑块
            val knobR = if (dragging >= 0f) 9.dp.toPx() else 6.dp.toPx()
            drawCircle(Color.White, knobR, Offset(size.width * playedFraction, cy))
        }
    }
}

@Composable
private fun SmallIconButton(
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(Color.White)
    }
}

@Composable
private fun TextButtonCompact(text: String, onClick: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 中央手势提示：音量/亮度/快进退。 */
@Composable
fun GestureHint(state: GestureUiState, modifier: Modifier = Modifier) {
    val mode = state.mode
    if (mode == GestureMode.NONE) return
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (mode) {
                GestureMode.SEEK -> {
                    Text(text = state.seekTargetMs.toClock(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    val gap = state.seekDeltaMs
                    Text(
                        text = (if (gap >= 0) "+" else "-") + (if (gap < 0) -gap else gap).toClock(),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                }
                GestureMode.VOLUME -> Text(text = "音量 ${state.fraction.toPercent()}%", color = Color.White, fontSize = 15.sp)
                GestureMode.BRIGHTNESS -> Text(text = "亮度 ${state.fraction.toPercent()}%", color = Color.White, fontSize = 15.sp)
                GestureMode.BOOST -> Text(text = "2 倍速", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                GestureMode.NONE -> Unit
            }
        }
    }
}

// 下面几个是"占位小图标"：为了不 import 一堆 icons-core 里的图标，
// 直接复用我们已经画好的形状，避免多引一个依赖。
@Composable
private fun IconBackLike(tint: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.minDimension / 24f
        val c = tint
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(15f * w, 5f * w)
            lineTo(8f * w, 12f * w)
            lineTo(15f * w, 19f * w)
        }
        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun IconLeaf2(tint: Color, active: Boolean) {
    if (active) {
        com.thermo.ui.icon.IconLeaf(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    } else {
        com.thermo.ui.icon.IconLeaf(modifier = Modifier.size(20.dp), tint = tint.copy(alpha = 0.55f))
    }
}

/** 顶栏第二行的编码标签。 */
private fun PlayerSnapshot.lastSampleMimeLabel(): String {
    val s = videoDecoderName
    val codec = if (s != null) s else null
    return codec ?: "—"
}


/**
 * 电量（优化 8）。横屏竖屏都能看到。
 *
 * 只放一个文字而不画电池图标：这个顶栏每 250ms 跟着 PlayerSnapshot 重组一次，
 * 多一个 Canvas 就多一次绘制指令；而电量本身只在系统发广播时才变。
 * 三档配色（正常 / 偏低 / 告急）已经够用了。
 */
@Composable
private fun BatteryChip() {
    val percent = rememberBatteryPercent()
    if (percent < 0) return
    val color = when {
        percent <= 15 -> Color(0xFFFF5A5A)
        percent <= 30 -> Color(0xFFFFC46B)
        else -> Color.White.copy(alpha = 0.85f)
    }
    Text(text = "$percent%", color = color, fontSize = 11.sp)
    Spacer(Modifier.width(6.dp))
}
