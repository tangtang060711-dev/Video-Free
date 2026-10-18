package com.thermo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thermo.thermal.ThermalLevel
import com.thermo.ui.icon.IconThermo
import com.thermo.ui.theme.ThermalColors

/**
 * 顶栏右上角的温度小徽标。
 *
 * 设计原则：**只在真的需要用户注意时才出现颜色**。
 * NORMAL 时是一个透明底、50% 透明度的灰字，用户几乎看不见；
 * WARM 以上才换成实心色块。理由是播放器顶栏上永远挂着一个亮色图标，
 * 在暗环境里反而更消耗注意力（也更费电，OLED 上亮像素就是电流）。
 */
@Composable
fun ThermalChip(
    level: ThermalLevel,
    powerSave: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        powerSave -> ThermalColors.normal
        level == ThermalLevel.NORMAL -> Color.White.copy(alpha = 0.5f)
        level == ThermalLevel.WARM -> ThermalColors.warm
        level == ThermalLevel.HOT -> ThermalColors.hot
        else -> ThermalColors.critical
    }
    val label = when {
        powerSave -> "省电"
        level == ThermalLevel.NORMAL -> "正常"
        level == ThermalLevel.WARM -> "偏温"
        level == ThermalLevel.HOT -> "发烫"
        else -> "过热"
    }
    Surface(
        modifier = modifier,
        color = if (level == ThermalLevel.NORMAL && !powerSave) Color.Transparent else tint.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconThermo(modifier = Modifier.size(13.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
            Text(text = label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 温度降级提示条。
 *
 * 只在 HOT 及以上出现，且**用户可关闭**（点了就记在 remember 里，本次播放不再弹）。
 * 为什么不做成 Toast：Toast 会过去，用户回头看到画面糊了会以为播放器坏了。
 * 为什么不做成弹窗：正看在兴头上被弹窗打断，是比发热更糟的体验。
 */
@Composable
fun ThermalBanner(
    level: ThermalLevel,
    hint: String?,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when (level) {
                        ThermalLevel.CRITICAL -> ThermalColors.critical.copy(alpha = 0.22f)
                        else -> ThermalColors.hot.copy(alpha = 0.20f)
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            IconThermo(
                modifier = Modifier.size(18.dp),
                tint = if (level == ThermalLevel.CRITICAL) ThermalColors.critical else ThermalColors.hot,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (level) {
                        ThermalLevel.CRITICAL -> "机身过热，已切到最省电档"
                        else -> "机身偏烫，已自动降低负载"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (!hint.isNullOrBlank()) {
                    Text(text = hint, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "知道了",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(0.dp),
                )
            }
        }
    }
}
