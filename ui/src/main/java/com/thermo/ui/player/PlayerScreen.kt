package com.thermo.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import com.thermo.thermal.ThermalLevel
import com.thermo.ui.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * 播放页。
 *
 * 这个函数只做"装配"：视频面 + 手势 + 控件 + 面板 + 三条提示带。
 * 所有业务决策都在 [PlayerViewModel] 与 :app 的 ThermalPolicyCoordinator 里，
 * 这里不写任何 `if (温度高) 降码率` 之类的逻辑 —— 那是策略层的事，
 * UI 只负责把"现在是什么状态"如实显示出来。
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    /** 当前方向档位的短标签。由 :app 的 OrientationController 提供。 */
    orientationLabel: String,
    /** 点一下在可用方向之间轮换。 */
    onCycleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findHostActivity() }
    val window = activity?.window
    val configuration = LocalConfiguration.current
    // 全屏图标要反映"现在到底是不是横的"，而不是只看按钮自己的开关状态。
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val player by viewModel.player.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val thermalLevel by viewModel.thermalLevel.collectAsStateWithLifecycle()
    val powerSave by viewModel.powerSaveMode.collectAsStateWithLifecycle()
    val followFrameRate by viewModel.followFrameRate.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val disabledTypes by viewModel.disabledTrackTypes.collectAsStateWithLifecycle()
    val preflight by viewModel.preflight.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val gestureState = remember { GestureUiState() }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetTab?>(null) }
    var bannerDismissed by remember { mutableStateOf(false) }

    // ---- 生命周期：退出前台 ----
    // 有视频轨就暂停（除非用户开了"后台只听音频"）。这一步比任何降码率都省电：
    // 屏幕关了却还在解 1080p60，是后台耗电的头号来源。
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.onPauseForBackground()
    }

    // ---- Window 绑定：帧率匹配 ----
    // 只在这里绑一次。DisplayRefreshRateAdapter 会去改 preferredDisplayModeId，
    // 那是个"窗口级"属性，页面销毁必须还原，否则退出播放器后桌面还卡在 24Hz。
    DisposableEffect(window) {
        viewModel.bindWindow(window)
        onDispose { viewModel.bindWindow(null) }
    }
    LaunchedEffect(followFrameRate) {
        viewModel.applyFrameRatePreference(followFrameRate)
    }

    // ---- 全屏 ----
    LaunchedEffect(fullscreen, window, view) {
        val w = window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(w, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- 屏幕方向 ----
    // 不在这里碰 requestedOrientation。之前这段会在播放页把方向声明成 FULL_SENSOR，
    // 传感器一抖画面就在 0°/180° 之间倒过来，还绕过了 :app 的 OrientationController
    // —— 方向只能由它一个地方决定。现在默认锁竖屏，只有右下角那个横屏按钮
    // （onToggleFullscreen -> onCycleOrientation）才切横屏。

    // ---- 播放中保持屏幕常亮 ----
    // 用 Window flag 而不是 Compose 的 keepScreenOn modifier：后者会触发一次
    // 视图树的重新布局，而这里只需要改一个窗口标志位。
    DisposableEffect(window, snapshot.isPlaying) {
        val w = window
        if (w != null) {
            if (snapshot.isPlaying) {
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }    // ---- 控件自动隐藏 ----
    // 三个前置条件缺一不可：
    //   1) 控件已经是显示状态（否则这个协程没必要跑）；
    //   2) 没有手势正在进行（用户正在调音量，不能把提示吞掉）；
    //   3) 正在播放（暂停时控件应该常驻，否则用户不知道点哪恢复）。
    LaunchedEffect(controlsVisible, gestureState.mode, snapshot.isPlaying) {
        if (!controlsVisible) return@LaunchedEffect
        if (gestureState.mode != GestureMode.NONE) return@LaunchedEffect
        if (!snapshot.isPlaying) return@LaunchedEffect
        delay(3_200L)
        controlsVisible = false
    }

    // ---- 提示条自动消失 ----
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_200L)
            viewModel.consumeMessage()
        }
    }

    // ---- 温度恢复正常后，允许下一次再提示 ----
    LaunchedEffect(thermalLevel) {
        if (thermalLevel == ThermalLevel.NORMAL) bannerDismissed = false
    }

    // 暂停时把控件亮出来（用户按了暂停，下一步多半是拖进度）
    LaunchedEffect(snapshot.isPlaying) {
        if (!snapshot.isPlaying) controlsVisible = true
    }

    val showThermalBanner = !bannerDismissed &&
        (thermalLevel == ThermalLevel.HOT || thermalLevel == ThermalLevel.CRITICAL)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---------- 1. 视频面 + 手势 ----------
        VideoSurface(
            player = player,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            modifier = Modifier
                .fillMaxSize()
                .playerGestures(
                    state = gestureState,
                    durationMs = snapshot.durationMs,
                    positionMs = snapshot.positionMs,
                    locked = locked,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onSeekTo = viewModel::seekTo,
                    onDoubleTap = viewModel::togglePlayPause,
                    // 长按全屏 = 临时倍速（设置页可调）。提示框靠 gestureState.mode 驱动，
                    // 所以这里手动把 mode 切到 BOOST / 复位，而不走 onSpeedBoostEnd 的默认值。
                    onSpeedBoostStart = {
                        gestureState.mode = GestureMode.BOOST
                        viewModel.onSpeedBoostStart()
                    },
                    onSpeedBoostEnd = {
                        viewModel.onSpeedBoostEnd()
                        gestureState.reset()
                    },
                ),
        )

        // ---------- 2. 手势提示（居中，随手指消失） ----------
        GestureHint(
            state = gestureState,
            modifier = Modifier.align(Alignment.Center),
        )

        // ---------- 3. 顶部提示带 ----------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 56.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showThermalBanner) {
                ThermalBanner(
                    level = thermalLevel,
                    hint = thermalHint(thermalLevel, quality, powerSave),
                    visible = true,
                    onDismiss = { bannerDismissed = true },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { bannerDismissed = true },
                )
            }
            val p = preflight
            if (p != null && p.playable && p.willRunOnCpu) {
                PreflightBanner(note = p.note)
            }
        }

        // ---------- 4. 控件 ----------
        PlayerControlsOverlay(
            visible = controlsVisible || !snapshot.isPlaying,
            locked = locked,
            snapshot = snapshot,
            title = title,
            thermalLevel = thermalLevel,
            powerSave = powerSave,
            quality = quality,
            onBack = onBack,
            onTogglePlay = viewModel::togglePlayPause,
            onSeekBy = viewModel::seekBy,
            onSeekTo = viewModel::seekTo,
            onOpenSpeed = { sheet = SheetTab.SPEED },
            onOpenTracks = { sheet = SheetTab.TRACKS },
            onOpenSubtitles = { sheet = SheetTab.SUBTITLES },
            onOpenQuality = { sheet = SheetTab.QUALITY },
            onToggleLock = { locked = !locked },
            onToggleFullscreen = {
                fullscreen = !fullscreen
                onCycleOrientation()
            },
            onTogglePowerSave = { viewModel.setPowerSaveMode(!powerSave) },
            isFullscreen = fullscreen || isLandscape,
            orientationLabel = orientationLabel,
            onCycleOrientation = onCycleOrientation,
        )

        // ---------- 5. 底部面板 ----------
        PlayerSheet(
            tab = sheet,
            tracks = tracks,
            disabledTrackTypes = disabledTypes,
            quality = quality,
            speed = snapshot.playbackSpeed,
            subtitleEnabled = !disabledTypes.contains(C.TRACK_TYPE_TEXT),
            onSelectTrack = { group, index ->
                viewModel.selectTrack(group, index)
                sheet = null
            },
            onClearOverride = viewModel::clearOverride,
            onSetTrackTypeDisabled = viewModel::setTrackTypeDisabled,
            onPickQuality = { tier ->
                viewModel.setQualityTier(tier)
                sheet = null
            },
            onPickSpeed = { speed ->
                viewModel.setSpeed(speed)
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        // ---------- 6. 一次性消息（错误/预检结论） ----------
        val msg = message
        if (msg != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 96.dp),
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 需要跑在 CPU 上的提示。
 *
 * 为什么不在这种情况下拦下来：用户可能就是想看这条 AVI。
 * 我们要做的是"提前告诉他这会让手机烫"，而不是替他做决定。
 * 真正该拦住的只有"根本解不出来"（见 [com.thermo.decoder.RoutePlanner] 的 playable=false）。
 */
@Composable
private fun PreflightBanner(note: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "这条媒体需要软解（CPU 解码），会明显更热",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/** 温度提示条上的第二行小字：说清楚"我们已经做了什么"。 */
private fun thermalHint(
    level: ThermalLevel,
    quality: com.thermo.player.QualityProfile,
    powerSave: Boolean,
): String = buildString {
    if (powerSave) append("省电模式已开 · ")
    when (level) {
        ThermalLevel.HOT -> {
            append("已缩短预加载、限制最高码率")
            if (quality != com.thermo.player.QualityProfile.UNRESTRICTED) append("、已降画质")
        }
        ThermalLevel.CRITICAL -> {
            append("已切到最省电档")
            append("（480p / 1.2Mbps / 30fps / 仅音频优先）")
        }
        else -> Unit
    }
}

/** Activity 查找：Compose 里拿 Window 只能这样绕。 */
private fun Context.findHostActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
