package com.thermo.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 手势的可见状态。UI 用它来画中间那个「音量 40% / 亮度 70% / +00:32」的提示。
 * 手势结束（手指抬起）后由手势层自行清空。
 */
class GestureUiState {
    var mode by mutableStateOf(GestureMode.NONE)
        internal set

    /** 0f~1f。SEEK 模式下是拖动过程中的目标位置占全片比例。 */
    var fraction by mutableFloatStateOf(0f)
        internal set

    /** SEEK 模式下相对当前位置的位移（毫秒，可正可负）。 */
    var seekDeltaMs by mutableLongStateOf(0L)
        internal set

    /** SEEK 模式下拖动目标位置的绝对值，用于显示 "12:34"。 */
    var seekTargetMs by mutableLongStateOf(0L)
        internal set

    internal fun reset() {
        mode = GestureMode.NONE
        fraction = 0f
        seekDeltaMs = 0L
        seekTargetMs = 0L
    }
}

enum class GestureMode { NONE, SEEK, VOLUME, BRIGHTNESS, BOOST }

/**
 * 播放器手势。刻意只做四件事，不做"上滑锁屏""三指截屏"之类的花活。
 *
 * 布局约定（和所有主流播放器一致，用户不需要学）：
 *  - 左半边上下拖 -> 亮度
 *  - 右半边上下拖 -> 音量
 *  - 任意位置左右拖 -> 快进/快退（**松手才 seek**）
 *  - 单击 -> 显示/隐藏控件
 *  - 双击 -> 暂停/播放
 *  - 长按任意位置 -> 临时倍速（设置页可调），松手还原
 *
 * 两个刻意的设计决定：
 *
 * (1) **拖动过程中只更新 UI，不回 seek。**
 *     这是低发热的关键：`Player.seekTo` 会让 ExoPlayer 丢缓冲、重新从关键帧解起，
 *     每帧调一次等于让解码器持续重启。主流播放器都是抬手才真正 seek。
 *
 * (2) **全部手势放在一个检测协程里，而不是四个 detect* 检测器并行。**
 *     四个检测器会互相抢事件；更重要的是：`positionMs` 每 250ms 更新一次，
 *     如果把它当 pointerInput 的 key，手势协程会跟着每 250ms 被取消重启，
 *     单击/拖动做到一半就被打断 —— 这就是「控件唤不出、拖不动」的根因。
 *     这里 key 永远只有 Unit，运行期需要的最新值走 rememberUpdatedState。
 */
fun Modifier.playerGestures(
    state: GestureUiState,
    durationMs: Long,
    positionMs: Long,
    locked: Boolean,
    onToggleControls: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDoubleTap: () -> Unit,
    /** 长按任意位置成立（超过长按阈值）：临时切到设置里的倍速。 */
    onSpeedBoostStart: () -> Unit,
    /** 手指抬起：还原到长按之前的倍速。 */
    onSpeedBoostEnd: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val density = LocalDensity.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val activity = remember(context) { context.findActivity() }

    // 这两个值每 250ms 变一次，绝不能进 pointerInput 的 key（见上面注释）。
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentLocked by rememberUpdatedState(locked)

    this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis.toLong()
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis

        var lastTapAt = 0L

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            // 锁定态：把这一整轮手势吃掉，等手指抬起。不消费 down，
            // 免得锁图标那 40dp 的点击区收不到自己的事件。
            if (currentLocked) {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                }
                return@awaitEachGesture
            }

            val downX = down.position.x
            val downY = down.position.y

            // ---- 阶段 1：等长按超时 / 手指抬起 / 开始拖动 ----
            // withTimeoutOrNull 是 AwaitPointerEventScope 的成员：超时返回 null。
            // 1 = 手指抬起（未拖未长按）; 2 = 移动超过 touch slop（开始拖动）。
            val early = withTimeoutOrNull(longPressTimeout) {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed) return@withTimeoutOrNull 1
                    val dx = c.position.x - downX
                    val dy = c.position.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) return@withTimeoutOrNull 2
                }
                @Suppress("UNREACHABLE_CODE")
                1
            }

            when (early) {
                // 手指在长按阈值内抬起：单击 / 双击
                1 -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapAt <= doubleTapTimeout) {
                        lastTapAt = 0L
                        onDoubleTap()
                    } else {
                        lastTapAt = now
                        onToggleControls()
                    }
                }

                // 长按成立（手指一直没动、也没抬）
                null -> {
                    // 全屏都是加速区：长按任意位置成立，用户不需要找右半边。
                    var fired = true
                    state.mode = GestureMode.BOOST
                    onSpeedBoostStart()
                    // 继续等抬起；期间不消费事件。
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                    }
                    if (fired) {
                        onSpeedBoostEnd()
                        state.reset()
                    }
                }

                // 开始拖动
                else -> {
                    var isSeek = false
                    var isLeft = false
                    var startVolume = 0
                    var startBrightness = 0f
                    var started = false

                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break

                        val dx = c.position.x - downX
                        val dy = c.position.y - downY

                        if (!started) {
                            started = true
                            if (abs(dx) > abs(dy)) {
                                isSeek = true
                                state.mode = GestureMode.SEEK
                                state.seekTargetMs = currentPositionMs
                                state.fraction = if (currentDurationMs > 0L) {
                                    currentPositionMs.toFloat() / currentDurationMs
                                } else {
                                    0f
                                }
                            } else {
                                isLeft = downX < size.width / 2f
                                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                startBrightness = currentBrightness(context)
                                val m = if (isLeft) GestureMode.BRIGHTNESS else GestureMode.VOLUME
                                state.mode = m
                                state.fraction = if (isLeft) startBrightness else startVolume.toFloat() / maxVolume
                            }
                        }

                        if (isSeek) {
                            c.consume()
                            val perPx = (currentDurationMs.toFloat() / size.width)
                                .coerceAtMost(1000f / density.density)
                            val delta = (dx * perPx).toLong().coerceIn(-300_000L, 300_000L)
                            val target = (currentPositionMs + delta).coerceIn(0L, currentDurationMs)
                            state.seekDeltaMs = target - currentPositionMs
                            state.seekTargetMs = target
                            state.fraction = target.toFloat() / currentDurationMs
                        } else {
                            c.consume()
                            val ratio = -dy / size.height.toFloat()
                            if (isLeft) {
                                val target = (startBrightness + ratio * 1.6f).coerceIn(0.02f, 1f)
                                setBrightness(activity, target)
                                state.fraction = target
                            } else {
                                val target = (startVolume + ratio * maxVolume * 1.6f).roundToInt()
                                    .coerceIn(0, maxVolume)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                state.fraction = target.toFloat() / maxVolume
                            }
                        }
                    }

                    when (state.mode) {
                        GestureMode.SEEK -> {
                            // 抬手才真正 seek
                            onSeekTo(state.seekTargetMs)
                            state.reset()
                        }
                        GestureMode.BRIGHTNESS, GestureMode.VOLUME -> state.reset()
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * 当前窗口亮度。返回 0.02~1 的绝对值：
 * 当 `screenBrightness` 是 `BRIGHTNESS_OVERRIDE_NONE`（-1）时说明用户没调过，
 * 此时系统用的是**自动亮度**，我们拿不到那个值，只能拿系统的
 * `Settings.System.SCREEN_BRIGHTNESS` 做起点 —— 它是 0~255 的整数。
 */
private fun currentBrightness(context: Context): Float {
    val attrs = (context.findActivity())?.window?.attributes
    val v = attrs?.screenBrightness ?: -1f
    if (v >= 0f) return v.coerceIn(0.02f, 1f)
    val sys = android.provider.Settings.System.getInt(
        context.contentResolver,
        android.provider.Settings.System.SCREEN_BRIGHTNESS,
        128,
    )
    return (sys / 255f).coerceIn(0.02f, 1f)
}

private fun setBrightness(activity: Activity?, value: Float) {
    val a = activity ?: return
    a.runOnUiThread {
        val attrs = a.window.attributes
        attrs.screenBrightness = value.coerceIn(0.02f, 1f)
        a.window.attributes = attrs
    }
}

/** 从任意 Context 找 Activity。Compose 的 LocalContext 在 AndroidView 里可能是 ContextWrapper。 */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** 供 UI 显示的百分比整数。 */
internal fun Float.toPercent(): Int = (this.coerceIn(0f, 1f) * 100f).roundToInt()