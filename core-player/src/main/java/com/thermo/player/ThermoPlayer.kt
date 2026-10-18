package com.thermo.player

import android.net.Uri
import android.view.Window
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器门面。
 *
 * ## 为什么还要留一个 `player` 口子直接暴露 Media3 的 [Player]
 * 因为 media3-ui 的 `PlayerView` 只认 `androidx.media3.common.Player`。
 * 如果我们在这里造一个 100% 屏蔽 Media3 的接口，就得自己重写一个 SurfaceView 渲染器 +
 * 字幕渲染 + 时间轴控件 —— 那是几千行代码，而且一定会比 PlayerView 做得好吗？不会。
 * 所以这里采取的是一种务实的分层：
 *   - **控制流走 ThermoPlayer**（prepare / play / pause / seek / quality / lifecycle），
 *     所有带策略含义的操作都必须经过这一层；
 *   - **显示流走 PlayerView + player**，它只读不写，拿到的就是一个普通 Player。
 * 这样既拿到了封装的好处，又没有付出重写渲染栈的代价。
 */
interface ThermoPlayer {

    /**
     * 底层 Media3 播放器实例。
     *
     * **注意它是 StateFlow 而不是常量**：因为 LoadControl 的缓冲参数只能在构造期传入，
     * 切换播放场景（本地 <-> 网络 <-> 直播）时必须重建 ExoPlayer。
     * 绑定 PlayerView 的一方要监听这个 Flow，实例变了就重新 `view.player = it`。
     */
    val player: StateFlow<Player?>

    /** 播放状态快照，UI 直接 collect。 */
    val snapshot: StateFlow<PlayerSnapshot>

    /** 一次性事件流。用 SharedFlow 而不是 Channel：允许多个消费者（UI + 诊断 + 发热策略）同时收。 */
    val events: SharedFlow<ThermoPlayerEvent>

    /**
     * 装载媒体。
     *
     * @param uri      支持 http(s) / file / content / asset
     * @param scenario 播放场景，决定缓冲策略。**它会触发（必要时）重建 ExoPlayer**，
     *                 所以不要在播放中途反复用不同场景调它。
     * @param mediaId  业务侧的唯一 id，用于打开缓存键。可空。
     * @param mimeHint 已知容器类型时给出可跳过探测，如 "video/mp4"。可空。
     */
    fun prepare(
        uri: Uri,
        scenario: PlaybackScenario,
        mediaId: String? = null,
        mimeHint: String? = null,
    )

    /** 直接塞一个已经组装好的 MediaItem（:decoder 走软解兜底时会用它替换源）。 */
    fun prepare(item: MediaItem, scenario: PlaybackScenario)
    /**
     * 装载一个播放列表（多选连播）。
     *
     * 为什么不用循环 addMediaItem：`setMediaItems(List)` 只触发一次时间轴重建
     * 与一次预加载，而逐条 add 会每加一条都通知一次监听器、重算一次窗口。
     * 播 20 个文件时那是 20 次通知换 1 次。
     *
     * [scenario] 只有一个：本地与网络混选时请传更保守的那档（网络）。
     */
    fun prepareAll(items: List<MediaItem>, scenario: PlaybackScenario)

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)

    /**
     * 下发画质约束。这是三级温度降级里第 2、3 级的执行入口。
     * 实现必须保证"只收紧不放宽"由调用方决定 —— 这里不做单调性检查，
     * 因为用户手动选高画质时是允许放宽的。
     */
    /**
     * 用户手选的画质。
     *
     * 注意它不是最终生效值 —— 实际下发的是它与温度上限的交集
     * （见 [setThermalQualityCap]）。这样温度降级不会因为用户随手改一次画质而被撤销。
     */
    fun setQualityProfile(profile: QualityProfile)

    /**
     * 温度策略给出的画质上限。
     *
     * 为什么和 [setQualityProfile] 分成两个方法：
     * "用户想多清晰"和"机器受不受得了"是两个独立关注点。
     * 如果一个方法包办，温度降级就只能靠"后调谁谁生效"，
     * 用户在 HOT 档手选一次 1080p 就把降级撤掉了，而手机还在烫。
     * 分开之后两边各有一个写入者，最终值由实现取交集。
     */
    fun setThermalQualityCap(cap: QualityProfile)

    /**
     * 是否允许使用高刷新率（90/120Hz）。
     * 省电模式 / 温度降级时置 false，显示会钉到 60Hz 附近的模式上。
     */
    /**
     * 预加载预算缩放（1.0 = 正常，0.25 = 最小）。
     *
     * 温度降级第 1 级就落在这里。**注意它不是立即生效的**：
     * ExoPlayer 的 LoadControl 在 build 时固定，改不了；
     * 这里只是把值存下来，下一次重建播放器（切片/切场景）时才应用。
     * 调用方（:app 的 ThermalPolicyCoordinator）必须同时做一件立即生效的事
     * （限码率或降刷新率），否则用户要等到下一个视频才看到效果。
     */
    fun setPreloadScale(scale: Float)

    fun setHighRefreshAllowed(allowed: Boolean)

    /** 绑定窗口，用于刷新率适配。传 null 表示解绑并还原刷新率。 */
    fun bindWindow(window: Window?)

    /**
     * 释放视频 Surface，只保留音频解码。
     *
     * 这是需求里"后台用前台 Service 且仅音频时释放视频 Surface"的落点。
     * 释放后视频解码器会被销毁，硬件视频解码通路（8 Elite 上大约 200~400mW）
     * 完全断掉，只剩音频那条低功耗通路。
     */
    fun releaseVideoSurface()

    /** 现场重新取回视频 Surface（回到前台时调用）。 */
    fun restoreVideoSurface()

    /** 彻底销毁。调用后对象不可再用。 */
    fun release()
}
