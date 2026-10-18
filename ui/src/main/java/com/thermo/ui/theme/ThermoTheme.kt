package com.thermo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 播放器的调色板。
 *
 * 为什么是深色优先：视频播放页 90% 的像素由视频本身占据，
 * 剩下 10% 如果是白色背景，在暗环境里会强迫瞳孔收缩，
 * 观感上"屏幕很亮"也和"省电"背道而驰（OLED 上白底比黑底多耗几倍电）。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FE3C4),      // 青绿主色：进度条、选中态
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFF8DFFE0),
    secondary = Color(0xFFB0CCC3),
    background = Color(0xFF0E1013),   // 比纯黑略亮：纯黑在 OLED 上和视频黑边分不清
    onBackground = Color(0xFFE1E3E6),
    surface = Color(0xFF16191D),
    onSurface = Color(0xFFE1E3E6),
    surfaceVariant = Color(0xFF23272C),
    onSurfaceVariant = Color(0xFFBFC6CC),
    outline = Color(0xFF3A4046),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun ThermoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),   // 用 M3 默认字阶，不自造
        content = content,
    )
}

/** 温度等级用色。放在这里是为了让"温度 -> 颜色"只有一个真源。 */
object ThermalColors {
    val normal = Color(0xFF6FE3C4)
    val warm = Color(0xFFFFC46B)
    val hot = Color(0xFFFF9145)
    val critical = Color(0xFFFF5A5A)
}