package com.thermo.ui.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 极简图标集 —— 用 Canvas 画，不引 material-icons-extended。
 *
 * 理由有两个，都是实打实的：
 *  1. material-icons-extended 会往 APK 里塞进上万个矢量图标（R8 能裁，
 *     但显著拖慢编译并留下资源碎片）；本播放器一共只需要十来个图标。
 *  2. 需要的 Pause / 全屏 / 亮度 / 字幕 / 倍速 这几个图标**不在** icons-core 里，
 *     而 icons-core 里的一堆图标我们一个都用不上。
 *
 * 约定：所有图标按 24x24 设计，`w = 实际像素 / 24` 是缩放系数；
 * 颜色一律由调用方给的 [tint] 决定（未指定时取 ContentColor）。
 */

/** 统一的绘制入口。`block` 拿到解析后的颜色 `c` 与缩放系数 `w`。 */
@Composable
private fun DrawIcon(
    modifier: Modifier,
    tint: Color,
    block: DrawScope.(c: Color, w: Float) -> Unit,
) {
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    Canvas(modifier = modifier.size(24.dp)) {
        block(color, size.minDimension / 24f)
    }
}

/** 播放：实心三角。 */
@Composable
fun IconPlay(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val p = Path().apply {
            moveTo(7.5f * w, 4.5f * w)
            lineTo(19f * w, 12f * w)
            lineTo(7.5f * w, 19.5f * w)
            close()
        }
        drawPath(p, c)
    }

/** 暂停：两根圆角竖条。 */
@Composable
fun IconPause(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        drawRoundRect(c, Offset(7f * w, 5f * w), Size(3.8f * w, 14f * w), CornerRadius(1.3f * w))
        drawRoundRect(c, Offset(13.2f * w, 5f * w), Size(3.8f * w, 14f * w), CornerRadius(1.3f * w))
    }

/** 全屏：四角折线指向外侧。 */
@Composable
fun IconFullscreen(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val sw = 2f * w
        fun L(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), sw, StrokeCap.Round)
        L(4f, 9.5f, 4f, 4f); L(4f, 4f, 9.5f, 4f)
        L(14.5f, 4f, 20f, 4f); L(20f, 4f, 20f, 9.5f)
        L(20f, 14.5f, 20f, 20f); L(20f, 20f, 14.5f, 20f)
        L(9.5f, 20f, 4f, 20f); L(4f, 20f, 4f, 14.5f)
    }

/** 退出全屏：四角折线指向内侧。 */
@Composable
fun IconFullscreenExit(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val sw = 2f * w
        fun L(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), sw, StrokeCap.Round)
        L(9.5f, 4f, 9.5f, 9.5f); L(9.5f, 9.5f, 4f, 9.5f)
        L(14.5f, 9.5f, 20f, 9.5f); L(14.5f, 4f, 14.5f, 9.5f)
        L(14.5f, 20f, 14.5f, 14.5f); L(14.5f, 14.5f, 20f, 14.5f)
        L(9.5f, 14.5f, 4f, 14.5f); L(9.5f, 14.5f, 9.5f, 20f)
    }

/** 音量：喇叭 + 两道弧。 */
@Composable
fun IconVolume(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val p = Path().apply {
            moveTo(3.5f * w, 9f * w); lineTo(7.5f * w, 9f * w)
            lineTo(12f * w, 5f * w); lineTo(12f * w, 19f * w)
            lineTo(7.5f * w, 15f * w); lineTo(3.5f * w, 15f * w)
            close()
        }
        drawPath(p, c)
        drawArc(c, -55f, 110f, false, Offset(6f * w, 5f * w), Size(12f * w, 14f * w), style = Stroke(1.8f * w, cap = StrokeCap.Round))
        drawArc(c, -55f, 110f, false, Offset(4f * w, 2f * w), Size(18f * w, 20f * w), style = Stroke(1.8f * w, cap = StrokeCap.Round))
    }

/** 亮度：太阳（圆 + 八条光芒）。 */
@Composable
fun IconBrightness(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        drawCircle(c, 4.2f * w, Offset(12f * w, 12f * w))
        val sw = 1.9f * w
        fun R(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), sw, StrokeCap.Round)
        R(12f, 1.2f, 12f, 3.6f); R(12f, 20.4f, 12f, 22.8f)
        R(1.2f, 12f, 3.6f, 12f); R(20.4f, 12f, 22.8f, 12f)
        R(4.5f, 4.5f, 6.2f, 6.2f); R(17.8f, 17.8f, 19.5f, 19.5f)
        R(19.5f, 4.5f, 17.8f, 6.2f); R(6.2f, 17.8f, 4.5f, 19.5f)
    }

/** 字幕：圆角矩形 + 四段线。 */
@Composable
fun IconSubtitles(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val sw = 1.8f * w
        drawRoundRect(
            color = c,
            topLeft = Offset(3f * w, 5f * w),
            size = Size(18f * w, 14f * w),
            cornerRadius = CornerRadius(2.2f * w),
            style = Stroke(sw),
        )
        drawLine(c, Offset(6.5f * w, 11.5f * w), Offset(12f * w, 11.5f * w), sw, StrokeCap.Round)
        drawLine(c, Offset(14f * w, 11.5f * w), Offset(17.5f * w, 11.5f * w), sw, StrokeCap.Round)
        drawLine(c, Offset(6.5f * w, 15f * w), Offset(9.5f * w, 15f * w), sw, StrokeCap.Round)
        drawLine(c, Offset(11.5f * w, 15f * w), Offset(17.5f * w, 15f * w), sw, StrokeCap.Round)
    }

/** 倍速：时钟。 */
@Composable
fun IconSpeed(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        drawCircle(c, 8.2f * w, Offset(12f * w, 12f * w), style = Stroke(1.9f * w))
        drawLine(c, Offset(12f * w, 12f * w), Offset(12f * w, 7f * w), 1.9f * w, StrokeCap.Round)
        drawLine(c, Offset(12f * w, 12f * w), Offset(16f * w, 13.6f * w), 1.9f * w, StrokeCap.Round)
    }

/** 音轨/字幕轨：三行列表。 */
@Composable
fun IconTracks(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val sw = 1.9f * w
        listOf(6.5f, 12f, 17.5f).forEach { y ->
            drawLine(c, Offset(4f * w, y * w), Offset(20f * w, y * w), sw, StrokeCap.Round)
        }
    }

/** 画质：三条递增竖条。 */
@Composable
fun IconQuality(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        drawRoundRect(c, Offset(4f * w, 14f * w), Size(4f * w, 6f * w), CornerRadius(1f * w))
        drawRoundRect(c, Offset(10f * w, 9f * w), Size(4f * w, 11f * w), CornerRadius(1f * w))
        drawRoundRect(c, Offset(16f * w, 4f * w), Size(4f * w, 16f * w), CornerRadius(1f * w))
    }

/** 手势锁。 */
@Composable
fun IconLock(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        drawRoundRect(c, Offset(5f * w, 10.5f * w), Size(14f * w, 9.5f * w), CornerRadius(1.8f * w))
        drawArc(
            color = c,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(7.5f * w, 4.5f * w),
            size = Size(9f * w, 9f * w),
            style = Stroke(1.9f * w, cap = StrokeCap.Round),
        )
    }

/** 温度计。 */
@Composable
fun IconThermo(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        drawLine(c, Offset(11f * w, 4f * w), Offset(11f * w, 15f * w), 2.6f * w, StrokeCap.Round)
        drawCircle(c, 3.4f * w, Offset(11f * w, 18f * w))
        drawLine(c, Offset(15f * w, 7f * w), Offset(19f * w, 7f * w), 1.7f * w, StrokeCap.Round)
        drawLine(c, Offset(15f * w, 11f * w), Offset(19f * w, 11f * w), 1.7f * w, StrokeCap.Round)
    }

/** 电池/省电模式。 */
@Composable
fun IconLeaf(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        val p = Path().apply {
            moveTo(19f * w, 5f * w)
            cubicTo(11f * w, 5f * w, 5f * w, 9.5f * w, 5f * w, 18f * w)
            cubicTo(13f * w, 18f * w, 19f * w, 13.5f * w, 19f * w, 5f * w)
            close()
        }
        drawPath(p, c, style = Stroke(1.9f * w))
        drawLine(c, Offset(6f * w, 18f * w), Offset(16f * w, 8f * w), 1.7f * w, StrokeCap.Round)
    }
/**
 * 旋转/方向：一段开口的圆弧 + 一个箭头。
 *
 * 画成"圈上开了个口 + 箭头"而不是完整的圆，是为了让人一眼看出
 * 这是"可以转"而不是"正在加载"。
 */
@Composable
fun IconRotate(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) =
    DrawIcon(modifier, tint) { c, w ->
        // 右上角留 70° 的缺口，箭头就压在这个缺口里。
        drawArc(
            color = c,
            startAngle = -70f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(4.2f * w, 4.2f * w),
            size = Size(15.6f * w, 15.6f * w),
            style = Stroke(2f * w, cap = StrokeCap.Round),
        )
        val head = Path().apply {
            moveTo(19.8f * w, 2.4f * w)
            lineTo(19.8f * w, 7.6f * w)
            lineTo(15.2f * w, 5.0f * w)
            close()
        }
        drawPath(head, c)
    }
