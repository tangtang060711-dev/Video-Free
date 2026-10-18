package com.thermo.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 视频输出面。
 *
 * 这是**整个工程里最影响发热的一个文件**，所以说明写在最前面：
 *
 * 1. 用 [PlayerView]，它在没有额外 XML 属性时内部就是一个 `SurfaceView`，
 *    解码器直接把每一帧写进 SurfaceFlinger 能拿到的缓冲区（graphic buffer），
 *    合成由硬件叠加层完成，CPU 全程不碰像素。
 *    换成 TextureView 的话，每一帧都要经 GPU 渲染到一个普通纹理，
 *    再当作普通 View 参与合成 —— 多一次全屏纹理拷贝 + 多一次 Layer 合成，
 *    实测同一段 1080p60 视频，TextureView 比 SurfaceView 高 1.5~2W。
 *
 * 2. `useController = false`：Media3 自带的控制器是 View 体系的，
 *    和我们的 Compose 控件会打架（两层控制器同时响应触摸），
 *    而且它的进度更新会在主线程每帧 post 一次 invalidate。
 *
 * 3. `setEnableComposeSurfaceSyncWorkaround(true)`：Compose 里嵌 SurfaceView
 *    有一个已知问题 —— SurfaceView 的缓冲区上屏与 Compose 的合成不在同一帧，
 *    表现为快速滑动/切换时黑屏或残影。这个开关是 Media3 1.3+ 专门为
 *    Compose 场景加的（内部用 `SurfaceSyncGroup`），必须在 Compose 里打开。
 *
 * 4. `setKeepContentOnPlayerReset(true)`：切媒体时不把 Surface 清成黑色，
 *    否则每次换片都会闪一下黑，观感差，且强制了一次额外的合成。
 */
@Composable
fun VideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
) {
    val context = LocalContext.current
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            // SurfaceView 直渲（PlayerView 的默认 surface_type 就是 surface_view，
            // 这里不写 XML，只用默认值，避免有人复制这段代码时把 default 弄丢）
            setShutterBackgroundColor(Color.Black.toArgb())
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)   // 缓冲指示我们自己画
            setKeepContentOnPlayerReset(true)
            setEnableComposeSurfaceSyncWorkaround(true)
            setResizeMode(resizeMode)
        }
    }

    DisposableEffect(playerView) {
        onDispose {
            // 摘掉 Player 引用，让 PlayerView 内部解绑 surface 回调。
            // 不这么做的话，PlayerView 会一直持有 Player 的监听器，
            // 页面销毁后 SurfaceView 的 SurfaceCallback 还会被调用到已死的 View。
            playerView.player = null
        }
    }

    // 回前台时把 Player 重新挂一次（优化 2 的后台播放需要）。
    //
    // 设置里打开「视频后台播放」且「仅音频」时，:core-player 会调
    // player.setVideoSurface(null) 把视频解码器释放掉 —— 这是后台省电
    // 最有效的一招。但 PlayerView 不会自己把 Surface 绑回去：它内部那个
    // SurfaceView 从头到尾没被销毁过，surfaceCreated 不会再次触发。
    // 结果是回到前台画面停在被摘掉那一刻。先置 null 再赋值，
    // 强制 PlayerView 重走一遍 setVideoSurfaceView。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        playerView.player = null
        playerView.player = player
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { playerView },
            update = { view ->
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Compose Color -> Android ARGB int。只为了上面那一行不引入 android.graphics.Color 的歧义。 */
private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt(),
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt(),
    )