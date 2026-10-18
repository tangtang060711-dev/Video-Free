package com.thermo.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.Window
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.thermo.player.internal.CodecNaming
import com.thermo.player.internal.DisplayRefreshRateAdapter
import com.thermo.player.internal.LoadControlFactory
import com.thermo.player.internal.TrackSelectionFactory
import com.thermo.player.internal.ThermoRenderersFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ThermoPlayer] 的 Media3 实现。
 *
 * ================================ 装配全貌 ================================
 * ```
 *  ExoPlayer
 *   |- RenderersFactory   ThermoRenderersFactory   -- 硬解优先 + FFmpeg 兜底 + 关拼接/预热
 *   |- LoadControl        LoadControlFactory       -- 按场景压缓冲（省电大头）
 *   |- TrackSelector      DefaultTrackSelector     -- 画质约束（三级降级的执行点）
 *   \- MediaSourceFactory DefaultMediaSourceFactory
 *                          \- DataSource.Factory   -- 由 :data 注入（OkHttp + SimpleCache）
 * ```
 *
 * ================================ 三个关键实现决定 ================================
 *
 * **1) ExoPlayer 懒建 + 按场景重建。**
 * `LoadControl` 的缓冲参数只能在构造期传进去（Media3 没有 setter），
 * 所以"换缓冲策略"等价于"换 ExoPlayer"。因此 [player] 是 StateFlow 而不是常量，
 * 绑 PlayerView 的一方必须监听它并在实例变化时重新 `view.player = it`。
 *
 * **2) 位置更新走"按需 ticker"。**
 * [snapshot] 用 `SharingStarted.WhileSubscribed` 汇聚"监听器推的变化 + 250ms 轮询的位置"。
 * 没有订阅者时轮询根本不存在，后台不动时不会每秒唤醒主线程若干次。
 *
 * **3) 视频 Surface 可被主动摘掉。**
 * [releaseVideoSurface] 调 `setVideoSurface(null)`，Media3 触发 `onVideoDisabled`，
 * 视频渲染器连同 MediaCodec 一起销毁。8 Elite 上视频硬解通路约 200~400mW，
 * 摘掉后后台听歌只剩音频那条几十 mW 的通路。
 * =======================================================================
 */
@Singleton
class Media3ThermoPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val renderersFactory: ThermoRenderersFactory,
    private val loadControlFactory: LoadControlFactory,
    private val trackSelectionFactory: TrackSelectionFactory,
    private val refreshRateAdapter: DisplayRefreshRateAdapter,
    /** 由 :data 提供（OkHttp + SimpleCache）。:core-player 只认这个抽象，不认 OkHttp。 */
    private val dataSourceFactory: DataSource.Factory,
) : ThermoPlayer {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    // ---------------------------------------------------------------- 可变内部状态

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    /** 当前 ExoPlayer 是按哪个场景建的。null = 还没建。 */
    private var builtScenario: PlaybackScenario? = null

    /** 当前生效的画质约束。重建播放器时要带回给新的 TrackSelector。 */
    /** 用户手选的画质（播放页"画质"面板选的）。 */
    private var userQuality: QualityProfile = QualityProfile.UNRESTRICTED

    /** 温度策略给的画质上限。与用户选择取交集后才生效。 */
    private var thermalCap: QualityProfile = QualityProfile.UNRESTRICTED

    /**
     * 真正下发给 TrackSelector 的画质约束 = 用户选择 ∩ 温度上限。
     *
     * 用两个字段而不是"谁后调谁生效"：播放页会随时改画质，温度策略也会随时改上限，
     * 两者是**独立的关注点**。用一个字段的话，用户在 HOT 时手选 1080p
     * 就会把温度上限覆盖掉 —— 手机会继续烫，而用户以为自己只是换了个清晰度。
     */
    private val effectiveQuality: QualityProfile
        get() = userQuality.intersect(thermalCap)

    private var highRefreshAllowed: Boolean = true
    private var boundWindow: Window? = null

    /** 视频 Surface 是否已被主动摘掉（后台仅音频态）。 */
    private var videoSurfaceReleased: Boolean = false

    /**
     * 预加载预算缩放。1.0 = 正常，0.25 = 最小（见 LoadControlFactory）。
     * ExoPlayer 的 LoadControl 在 build 之后改不了，所以这里只能"存起来下次重建用"。
     */
    private var preloadScale: Float = 1f

    /** 策略层改过预算，但当前播放器还是老配置 —— 下次重建时应用。 */
    private var preloadDirty: Boolean = false

    /** 待应用的缩放值（重建时搬到 preloadScale）。 */
    private var pendingPreloadScale: Float = 1f
    /**
     * 最近一次视频输入格式。
     * `onVideoDecoderInitialized` 只给解码器名字，mimeType 得从这里补。
     * 顺序上 `onVideoInputFormatChanged` 一定先到，所以这样取是可靠的。
     * （不去从 EventTime 里挖 Timeline —— 那条路要按 periodIndex/trackGroupIndex
     *  逐级下钻，又长又容易在切轨时取错。）
     */
    private var lastVideoFormat: Format? = null

    private val _player = MutableStateFlow<Player?>(null)
    private val _events = MutableSharedFlow<ThermoPlayerEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 由两个 listener + ticker 共同维护的"原始快照"。 */
    private val raw = MutableStateFlow(PlayerSnapshot())

    override val events: SharedFlow<ThermoPlayerEvent> = _events

    override val player: StateFlow<Player?> = _player

    /**
     * 位置轮询源。只在有人订阅 [snapshot] 时才真的跑（见 `WhileSubscribed`）。
     * 间隔分两档：正在播 250ms（进度条够顺滑），暂停/缓冲 1000ms（几乎不唤醒）。
     */
    private val positionTicker = flow {
        while (true) {
            emit(Unit)
            val p = exoPlayer
            delay(if (p != null && p.isPlaying) TICK_PLAYING_MS else TICK_IDLE_MS)
        }
    }

    override val snapshot: StateFlow<PlayerSnapshot> =
        combine(raw, positionTicker) { s, _ -> s.withLivePosition(exoPlayer) }
            .onStart { emit(raw.value) }
            .stateIn(scope, SharingStarted.WhileSubscribed(2_000L), PlayerSnapshot())
    // ---------------------------------------------------------------- Player.Listener

    private val playbackListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            raw.update {
                it.copy(
                    playbackState = playbackState,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                _events.tryEmit(ThermoPlayerEvent.PlaybackEnded)
                // 播完把刷新率还原，避免停在播放页时屏幕还被钉在 120Hz。
                boundWindow?.let { refreshRateAdapter.reset(it) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            raw.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            raw.update { it.copy(playbackSpeed = playbackParameters.speed) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            raw.update { it.copy(videoSize = videoSize) }
            _events.tryEmit(ThermoPlayerEvent.VideoSizeChanged(videoSize))
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "播放错误 code=${error.errorCodeName} msg=${error.message}", error)
            raw.update { it.copy(error = error) }
            _events.tryEmit(ThermoPlayerEvent.DecodeError(error))
        }
    }

    // ---------------------------------------------------------------- AnalyticsListener

    /**
     * 承担"低发热"里最重要的**观测**职责。
     * 只有知道现在跑的是硬解还是软解、有没有在丢帧，降级策略才有依据；
     * 而这些信息 Player.Listener 一条都不给。
     */
    private val analyticsListener = object : AnalyticsListener {

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val soft = CodecNaming.isSoftware(decoderName)
            val mime = lastVideoFormat?.sampleMimeType
            raw.update { it.copy(videoDecoderName = decoderName, isSoftwareDecoding = soft) }
            val kind = if (soft) "软解" else "硬解"
            Log.i(TAG, "视频解码器 = $decoderName（$kind，初始化 ${initializationDurationMs}ms）")
            _events.tryEmit(
                ThermoPlayerEvent.VideoDecoderSelected(
                    decoderName = decoderName,
                    mimeType = mime.orEmpty(),
                    software = soft,
                    initDurationMs = initializationDurationMs,
                ),
            )
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            lastVideoFormat = format
            raw.update { it.copy(videoFrameRate = format.frameRate) }
            _events.tryEmit(
                ThermoPlayerEvent.VideoFormatChanged(
                    width = format.width,
                    height = format.height,
                    frameRate = format.frameRate,
                    mimeType = format.sampleMimeType,
                ),
            )
            // 一解出帧率就把显示刷新率钉到合适的档位。
            // 这是需求里"帧率匹配"的落点：早钉一秒，就少一秒 120Hz 空转。
            applyDisplayRefreshRate()
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            // 丢帧 = 解码器跟不上当前负载，是"即将发烫"的先行指标，
            // 通常比温度传感器早 10~30 秒能看出来，策略层据此可以提前降档。
            if (droppedFrames > 0) {
                _events.tryEmit(ThermoPlayerEvent.FramesDropped(droppedFrames, elapsedMs))
            }
        }

        override fun onVideoDisabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters,
        ) {
            // 视频渲染器被销毁（主动摘 Surface / 轨道切换）。
            // 解码器没了，状态要清干净，否则诊断面板会一直显示一个已死的解码器名。
            raw.update { it.copy(videoDecoderName = null, isSoftwareDecoding = false) }
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            _events.tryEmit(ThermoPlayerEvent.FirstFrameRendered(renderTimeMs))
        }

        override fun onVideoCodecError(
            eventTime: AnalyticsListener.EventTime,
            videoCodecError: Exception,
        ) {
            // 解码器级错误（不是播放错误）。硬解驱动崩溃通常报在这里，
            // 这是 :decoder 判断"要不要强制走 FFmpeg"的主要信号。
            Log.w(TAG, "视频解码器异常：${videoCodecError.message}", videoCodecError)
        }
    }
    // ---------------------------------------------------------------- 播放器构建

    /**
     * 拿到符合 [scenario] 的 ExoPlayer；场景变了就重建。
     *
     * 重建看起来浪费，但实际上一路片源只对应一个场景，
     * 而 `LoadControl` 真的没有别的换法。与其留一个参数不匹配场景的播放器
     * 一直烧着 40 秒缓冲，不如干脆重建一次。
     */
    private fun ensurePlayer(scenario: PlaybackScenario): ExoPlayer {
        // playbackLooper 取的是"构造 Builder 时所在线程"的 Looper，所以这条不是洁癖：
        // 真在带 Looper 的子线程上构造，播放器会把所有回调都派发到那个线程，界面立刻不动。
        // 只告警不抛：降级路径上宁可继续放，也不要因为一个线程问题直接崩掉。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "ensurePlayer 未在主线程调用：" + Thread.currentThread().name + "（期望 main）")
        }
        val existing = exoPlayer
        // 多了一个 preloadDirty 条件：温度策略调过缓冲预算时必须重建，
        // 否则那个"下次生效"的承诺永远不会兑现。
        if (existing != null && builtScenario == scenario && !preloadDirty) return existing
        if (preloadDirty) {
            preloadScale = pendingPreloadScale
            preloadDirty = false
            Log.i(TAG, "预加载预算已应用 scale=$preloadScale")
        }
        existing?.let { destroyPlayer(it) }

        val selector = DefaultTrackSelector(
            context,
            trackSelectionFactory.build(effectiveQuality),
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            // 只加载被选中轨道。默认 Media3 会把候选轨道也解出来做切换准备，
            // 一个 4 音轨 + 3 字幕的 MKV 会因此多解好几路。
            // 代价：播放中途切音轨可能触发一次源重建（轻微卡顿）。
            // 对"降发热"这个目标，这个代价是划算的。
            .setLoadOnlySelectedTracks(true)
            // 直播目标延迟 20s："不卡"与"低延迟"的折中。
            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)

        val exo = ExoPlayer.Builder(
            context,
            renderersFactory.create(context),
            mediaSourceFactory,
        )
            .setTrackSelector(selector)
            .setLoadControl(loadControlFactory.create(scenario, preloadScale))
            // WAKE_MODE_LOCAL：屏幕亮着时靠屏幕本身持锁；锁屏纯音频时需要 CPU 醒着。
            // 不用 WAKE_MODE_NETWORK —— 那会让射频一直保持高功耗，
            // 而我们缓冲已经很浅，射频频繁进出休眠反而不划算。
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)          // 拔耳机自动暂停
            // SCALE_TO_FIT：不额外做几何变换，缩放交给 Surface 硬件。
            // 用 WITH_CROPPING 会引入一次额外的缩放/裁剪计算。
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            // 帧率匹配策略：只在能无缝改的时候改（API 30+），
            // 避免为一次帧率切换重配显示通路（那一下的功耗尖峰比省下来的还多）。
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            // 懒准备：prepare() 时不立刻建解码器，等 play() 时再建。
            // 用户点进详情页又退出的场景能省掉一整轮解码器启停。
            .setUseLazyPreparation(true)
            // Surface 摘除超时。默认 2 秒：退后台调 setVideoSurface(null) 时若解码器不响应，
            // 主线程会被卡最多 2 秒（ANR 风险 + 白耗电）。压到 500ms，超时即放弃，音频继续。
            .setDetachSurfaceTimeoutMs(500L)
            .setPauseAtEndOfMediaItems(false)
            // ⚠ 绝对不要在这里加 setPlaybackLooper()。真机实测过，它必崩：
            //   java.lang.IllegalStateException
            //     at ExoPlayer$Builder.setPlaybackLooper(ExoPlayer.java:1220)
            // 1.9.4 的 Builder 把字段从 playbackLooper 换成了 playbackLooperProvider，
            // 并且在私有构造里就用 Util.getCurrentOrMainLooper() 预置了它；
            // setPlaybackLooper() 内部是 checkState(playbackLooperProvider == null)，
            // 所以在已经构造好的 Builder 上再调一次，条件必然为 false。
            // 我们真正想要的"播放器绑主线程"由 Util.getCurrentOrMainLooper() 天然满足 ——
            // ensurePlayer() 全程跑在主线程（PlayerViewModel.open 在 viewModelScope = Main.immediate 上）。
            .build()

        exo.addListener(playbackListener)
        exo.addAnalyticsListener(analyticsListener)

        exoPlayer = exo
        trackSelector = selector          // 必须存下来，否则 setQualityProfile 永远打空
        builtScenario = scenario
        videoSurfaceReleased = false
        lastVideoFormat = null
        _player.value = exo
        Log.i(TAG, "ExoPlayer 重建完成 scenario=$scenario 缓冲上限=${scenarioBufferHint(scenario)}")
        return exo
    }

    private fun destroyPlayer(p: ExoPlayer) {
        runCatching {
            p.removeListener(playbackListener)
            p.removeAnalyticsListener(analyticsListener)
            p.release()
        }.onFailure { Log.w(TAG, "释放旧 ExoPlayer 异常", it) }
        if (exoPlayer === p) {
            exoPlayer = null
            trackSelector = null
            builtScenario = null
            _player.value = null
        }
    }
    // ---------------------------------------------------------------- ThermoPlayer 实现

    override fun prepare(uri: Uri, scenario: PlaybackScenario, mediaId: String?, mimeHint: String?) {
        val builder = MediaItem.Builder().setUri(uri)
        if (mediaId != null) builder.setMediaId(mediaId)
        if (mimeHint != null) builder.setMimeType(mimeHint)
        prepare(builder.build(), scenario)
    }

    override fun prepare(item: MediaItem, scenario: PlaybackScenario) {
        val p = ensurePlayer(scenario)
        // 清快照但保留画质约束，保证 UI 立刻回到"加载中"而不是显示上一条的状态。
        raw.value = PlayerSnapshot(
            playbackState = Player.STATE_IDLE,
            qualityProfile = effectiveQuality,
        )
        p.setMediaItem(item)
        p.prepare()
    }


    override fun prepareAll(items: List<MediaItem>, scenario: PlaybackScenario) {
        if (items.isEmpty()) return
        val p = ensurePlayer(scenario)
        raw.value = PlayerSnapshot(
            playbackState = Player.STATE_IDLE,
            qualityProfile = effectiveQuality,
        )
        // setMediaItems(List) 而不是逐条 addMediaItem：见 ThermoPlayer.prepareAll 的注释。
        // startIndex 不传，默认从第 0 条开始 —— 多选连播的语义就是"从我选的第一条开始"。
        p.setMediaItems(items)
        p.prepare()
    }
    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun stop() {
        exoPlayer?.stop()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        // 倍速播放在软解下会让 CPU 占用线性上升，是经典的"越用越烫"来源。
        // 这里只做范围钳制；是否真的允许倍速由 :app 的策略层决定。
        exoPlayer?.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
    }

    /**
     * 调整预加载预算。
     *
     * **刻意不立即生效**：ExoPlayer 的 LoadControl 在 build 时固定，
     * 想在播放中途把缓冲砍小，唯一办法是重建播放器 —— 那会丢掉已缓冲的数据、
     * 画面卡一下、重新协商 MediaCodec。用一次可见的卡顿换 200mW，
     * 用户会认为播放器坏了。
     *
     * 所以这里的语义是"下一次打开生效"。调用方必须同时做一件**立即**生效的事
     * （限码率 / 降刷新率 / 摘 Surface），否则用户要等到下一个视频才有感觉。
     */
    override fun setPreloadScale(scale: Float) {
        val v = scale.coerceIn(MIN_PRELOAD_SCALE, 1f)
        val current = if (preloadDirty) pendingPreloadScale else preloadScale
        if (kotlin.math.abs(v - current) < 0.01f) return
        pendingPreloadScale = v
        preloadDirty = true
        Log.i(TAG, "预加载预算调整请求 scale=$v（LoadControl 是 build 时固定的，下次重建生效）")
    }

    override fun setQualityProfile(profile: QualityProfile) {
        userQuality = profile
        applyEffectiveQuality()
    }

    /**
     * 温度策略下发的画质上限。
     *
     * 与 [setQualityProfile] 分开，是为了让"用户想多清晰"和"机器受不受得了"
     * 各自只有一个写入者。合在一起的话，任何一方改动都会覆盖另一方：
     * 温度一高、用户手选一次降级就被撤销了。
     */
    override fun setThermalQualityCap(cap: QualityProfile) {
        if (cap == thermalCap) return
        thermalCap = cap
        Log.i(TAG, "温度画质上限 -> ${cap.maxWidth}x${cap.maxHeight}")
        applyEffectiveQuality()
    }

    /** 把 用户选择 ∩ 温度上限 真正下发下去。两个 setter 都走这里，保证只有一个出口。 */
    private fun applyEffectiveQuality() {
        val eff = effectiveQuality
        raw.update { it.copy(qualityProfile = eff) }
        val selector = trackSelector
        // 播放器还没建时约束已存下，ensurePlayer 会用上。
        if (selector == null) return
        selector.setParameters(trackSelectionFactory.build(eff))
        Log.i(
            TAG,
            "画质约束下发 ${eff.maxWidth}x${eff.maxHeight} " +
                "@${eff.maxBitrate / 1000}kbps/${eff.maxFrameRate}fps " +
                "nonSeamless=${eff.allowNonSeamless}",
        )
    }

    override fun setHighRefreshAllowed(allowed: Boolean) {
        if (highRefreshAllowed == allowed) return
        highRefreshAllowed = allowed
        applyDisplayRefreshRate()
    }

    override fun bindWindow(window: Window?) {
        val previous = boundWindow
        boundWindow = window
        if (window == null) previous?.let { refreshRateAdapter.reset(it) } else applyDisplayRefreshRate()
    }

    override fun releaseVideoSurface() {
        val p = exoPlayer ?: return
        if (videoSurfaceReleased) return
        videoSurfaceReleased = true
        Log.i(TAG, "释放视频 Surface —— 进入仅音频模式")
        // 传 null 会让 Media3 走"移除输出"路径，触发 onVideoDisabled，
        // 视频 MediaCodec 随之被 release。
        runCatching { p.setVideoSurface(null) }
            .onFailure { Log.w(TAG, "摘除视频 Surface 失败", it) }
        raw.update {
            it.copy(
                videoDecoderName = null,
                isSoftwareDecoding = false,
                videoSize = VideoSize.UNKNOWN,
            )
        }
    }

    override fun restoreVideoSurface() {
        // 只清标志位。真正的 Surface 由 UI 重新绑上去 ——
        // :core-player 不持有 SurfaceView 引用（那是 UI 的资产），
        // 所以"恢复"必须由 UI 触发一次 PlayerView 的重新挂载，见 :ui 的 ThermoPlayerSurface。
        videoSurfaceReleased = false
        Log.i(TAG, "视频 Surface 标记为待恢复，等待 UI 重新挂载")
    }

    override fun release() {
        exoPlayer?.let { destroyPlayer(it) }
        boundWindow?.let { refreshRateAdapter.reset(it) }
        boundWindow = null
        scope.cancel()
    }

    // ---------------------------------------------------------------- 内部工具

    private fun applyDisplayRefreshRate() {
        val window = boundWindow ?: return
        // 仅音频模式（Surface 已摘）不需要刷刷新率，还原成系统默认反而更省。
        if (videoSurfaceReleased) {
            refreshRateAdapter.reset(window)
            return
        }
        refreshRateAdapter.apply(window, raw.value.videoFrameRate, highRefreshAllowed)
    }

    /** 把 ExoPlayer 的实时值刷进快照。只在 ticker 触发时调用。 */
    private fun PlayerSnapshot.withLivePosition(p: ExoPlayer?): PlayerSnapshot {
        if (p == null) return this
        return copy(
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration,
            bufferedPositionMs = p.bufferedPosition.coerceAtLeast(0L),
            totalBufferedMs = p.totalBufferedDuration.coerceAtLeast(0L),
            playbackState = p.playbackState,
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun scenarioBufferHint(scenario: PlaybackScenario): String = when (scenario) {
        PlaybackScenario.LOCAL -> "1~4s/16MB"
        PlaybackScenario.STREAM -> "15~40s/32MB"
        PlaybackScenario.LIVE -> "1~8s/8MB"
    }

    private inline fun MutableStateFlow<PlayerSnapshot>.update(
        block: (PlayerSnapshot) -> PlayerSnapshot,
    ) {
        value = block(value)
    }

    private companion object {
        const val TAG = "ThermoPlayer"

        /** 正在播放时的位置刷新间隔。250ms 对进度条足够，且一秒只唤醒主线程 4 次。 */
        const val TICK_PLAYING_MS = 250L

        /** 暂停/缓冲时的刷新间隔。基本等于不唤醒，只保证状态变化能被感知。 */
        const val TICK_IDLE_MS = 1_000L

        /** 直播目标延迟：20 秒。 */
        const val LIVE_TARGET_OFFSET_MS = 20_000L

        /** 预加载预算的绝对下限，与 LoadControlFactory.MIN_SCALE 语义一致。 */
        const val MIN_PRELOAD_SCALE = 0.25f
    }
}
