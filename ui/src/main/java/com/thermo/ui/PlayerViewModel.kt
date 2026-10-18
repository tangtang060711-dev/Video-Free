package com.thermo.ui

import android.util.Log
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.thermo.data.progress.PlaybackPositionStore
import com.thermo.data.settings.DisplayMode
import com.thermo.data.settings.ThermoSettings
import com.thermo.decoder.PreflightDecision
import com.thermo.decoder.RoutePlanner
import com.thermo.player.PlaybackScenario
import com.thermo.player.PlayerSnapshot
import com.thermo.player.QualityProfile
import com.thermo.player.ThermoPlayer
import com.thermo.thermal.ThermalLevel
import com.thermo.thermal.ThermalMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放页唯一的 ViewModel。
 *
 * 它**不做任何降级决策** —— 那是 :app 的 ThermalPolicyCoordinator 的事。
 * 这里只做三件事：
 *  1. 把 [ThermoPlayer] 的状态流搬给 Compose（不做任何转换，避免多一层重组）；
 *  2. 把用户操作翻译成 [ThermoPlayer] 的调用；
 *  3. 打开前跑一次容器预检，让"这条片子根本播不了"在点开之前就说清楚。
 */
/**
 * 播放列表里的一条。
 *
 * 刻意不复用 :data 的 MediaEntry：加密空间里的条目没有 MediaStore 的 id，
 * 而网络地址根本不在 MediaStore 里。播放列表只需要"地址 + 显示名 + 存档键"三样。
 */
data class PlaylistItem(
    val uri: Uri,
    val title: String? = null,
    val mediaId: String? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val thermoPlayer: ThermoPlayer,
    private val thermalMonitor: ThermalMonitor,
    private val settings: ThermoSettings,
    private val positionStore: PlaybackPositionStore,
    private val routePlanner: RoutePlanner,
) : ViewModel() {

    val snapshot: StateFlow<PlayerSnapshot> = thermoPlayer.snapshot
    val thermalLevel: StateFlow<ThermalLevel> = thermalMonitor.level

    /**
     * ExoPlayer 实例本身，给 VideoSurface 绑定。
     *
     * 必须是流而不是常量：切场景（本地 <-> 直播）会重建 ExoPlayer 实例，
     * 界面若拿着旧实例去绑 Surface，表现是"有声音、没画面"，而且不报任何错。
     */
    val player: StateFlow<Player?> = thermoPlayer.player

    /**
     * 当前轨道选择参数（含 disabledTrackTypes 与手动 override）。
     *
     * 和 [tracks] 一样做成独立流：它也是几十秒才变一次，
     * 没必要混进每 250ms 刷新的 [snapshot] 里让 Compose 反复比对。
     */
    val trackParams: StateFlow<TrackSelectionParameters> = thermoPlayer.player
        .flatMapLatest { p ->
            if (p == null) {
                flowOf(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT)
            } else {
                paramsFlow(p)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(2_000L),
            TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT,
        )

    /** 被整类关掉的轨道类型（典型：用户关了字幕）。 */
    val disabledTrackTypes: StateFlow<Set<Int>> = trackParams
        .map { it.disabledTrackTypes.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000L), emptySet())

    val powerSaveMode: StateFlow<Boolean> = settings.powerSaveMode
    val followFrameRate: StateFlow<Boolean> = settings.followVideoFrameRate
    val allowSoftwareFallback: StateFlow<Boolean> = settings.allowSoftwareFallback

    /** 视频后台播放，**默认关**。见 ThermoSettings.backgroundPlayback 的注释。 */
    val backgroundPlayback: StateFlow<Boolean> = settings.backgroundPlayback

    /** 后台仅音频。只在 backgroundPlayback 打开时才有意义。 */
    val audioOnlyInBackground: StateFlow<Boolean> = settings.audioOnlyInBackground

    /** 多线程下载的分片数（1~32），默认 8。 */
    val downloadThreads: StateFlow<Int> = settings.downloadThreads

    /** 长按加速倍速（1.5~5.0，默认 2.0）。设置页里可调。 */
    val longPressSpeed: StateFlow<Float> = settings.longPressSpeed

    /** 主界面显示方式：列表 / 平铺。 */
    val displayMode: StateFlow<DisplayMode> = settings.displayMode

    /** 主界面是否显示缩略图。 */
    val showThumbnails: StateFlow<Boolean> = settings.showThumbnails

    /** 正在播放什么（标题栏用）。 */
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    /** 预检结论。null = 还没跑（比如从相册直接进来没经过列表页）。 */
    private val _preflight = MutableStateFlow<PreflightDecision?>(null)
    val preflight: StateFlow<PreflightDecision?> = _preflight.asStateFlow()

    /** 当前生效的画质约束，UI 上要显示"已降到 720p"。 */
    private val _quality = MutableStateFlow(QualityProfile.UNRESTRICTED)
    val quality: StateFlow<QualityProfile> = _quality.asStateFlow()

    /**
     * 轨道列表。
     *
     * 为什么用 callbackFlow 重新挂监听，而不是从 [snapshot] 里带出来：
     * [PlayerSnapshot] 每 250ms 更新一次，把 Tracks 塞进去会让 Compose
     * 每 250ms 比较一次轨道列表（Tracks 内部是 ImmutableList，equals 要遍历），
     * 而轨道其实几十秒才变一次。分开成独立的流，两边都省。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tracks: StateFlow<Tracks> = thermoPlayer.player
        .flatMapLatest { p ->
            if (p == null) flowOf(Tracks.EMPTY) else tracksFlow(p)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000L), Tracks.EMPTY)

    /** 播放错误提示（一次性），UI 弹 Snackbar 后调 [consumeMessage]。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private var currentMediaId: String? = null

    /** 长按右屏的临时倍速是否生效中（用于保证"只还原一次"）。 */
    private var speedBoosted = false

    /** 长按之前的倍速，抬起时还原到它。 */
    private var speedBeforeBoost = 1f

    init {
        // 进度落盘：**5 秒一次**，不是每帧一次。
        // PlaybackPositionStore 走的是 SQLite + WAL，每次写都会 fsync；
        // 250ms 写一次等于每秒 4 次磁盘同步，这本身就是个发热源。
        viewModelScope.launch {
            var lastSaved = -1L
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                val s = snapshot.value
                val id = currentMediaId ?: continue
                if (!s.isPlaying) continue
                val pos = s.positionMs
                if (pos <= 0L || pos == lastSaved) continue
                lastSaved = pos
                runCatching { positionStore.save(id, pos, s.durationMs) }
            }
        }
        // 播放错误 -> 提示
        viewModelScope.launch {
            snapshot.collect { s ->
                val e = s.error ?: return@collect
                _message.value = e.errorCodeName + ": " + (e.cause?.message ?: e.message)
            }
        }
    }

    private fun tracksFlow(p: Player) = callbackFlow {
        trySend(p.currentTracks)
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                trySend(tracks)
            }
        }
        p.addListener(listener)
        awaitClose { p.removeListener(listener) }
    }.distinctUntilChanged()

    /** 与 [tracksFlow] 同理：轨道选择参数也只挂监听、不做轮询。 */
    private fun paramsFlow(p: Player) = callbackFlow {
        trySend(p.trackSelectionParameters)
        val listener = object : Player.Listener {
            override fun onTrackSelectionParametersChanged(params: TrackSelectionParameters) {
                trySend(params)
            }
        }
        p.addListener(listener)
        awaitClose { p.removeListener(listener) }
    }.distinctUntilChanged()
    /**
     * 打开一条媒体。
     *
     * @param uri        视频/音频地址，本地 content:// 或 http(s) 都行
     * @param scenario   由调用方（列表页/详情页）指明是本地、点播还是直播；
     *                   不传就按 URL 猜
     * @param mediaId    进度存档用的稳定 ID（本地文件用 path，网络用 URL）
     * @param autoPlay   预检通过后是否自动起播
     */
    fun open(
        uri: Uri,
        scenario: PlaybackScenario = PlaybackScenario.guessFrom(uri.toString()),
        mediaId: String? = null,
        mimeHint: String? = null,
        title: String? = null,
        autoPlay: Boolean = true,
    ) {
        _title.value = title ?: uri.lastPathSegment ?: uri.toString()
        currentMediaId = mediaId ?: uri.toString()
        _preflight.value = null

        viewModelScope.launch {
            // 预检：只读文件头 4KB，本地文件几毫秒、网络一次 Range 请求。
            // 代价极小，但能在"点开才发现播不了"和"点开就知道要提醒烫"之间做出选择。
            val decision = runCatching { routePlanner.preflight(uri) }.getOrNull()
            _preflight.value = decision

            if (decision != null && !decision.playable) {
                // 播不了就不播 —— 把 URI 丢给 ExoPlayer 只会得到一个
                // 几秒后弹出的英文错误框，用户还是不知道发生了什么。
                _message.value = decision.note
                return@launch
            }

            val item = MediaItem.Builder()
                .setUri(uri)
                .apply { if (mimeHint != null) setMimeType(mimeHint) }
                .build()

            // 开播路径整段兜底。播放器是最容易"只在某型号真机上炸"的地方，
            // 一个未捕获异常会把进程直接带走 —— 用户看到的就是"一播放就闪退"。
            // 这里宁可降级成一条提示也不要崩，同时把完整堆栈打进 logcat 便于定位。
            runCatching {
                thermoPlayer.prepare(item, scenario)
                thermoPlayer.setQualityProfile(_quality.value)

                // 续播：只在有存档且不是直播时跳
                if (scenario != PlaybackScenario.LIVE) {
                    val saved = runCatching { positionStore.load(currentMediaId!!) }.getOrDefault(0L)
                    val dur = snapshot.value.durationMs
                    // 差 10 秒以内视为"看完了"，从头开始；否则续播
                    if (saved > 5_000L && (dur <= 0L || dur - saved > 10_000L)) {
                        thermoPlayer.seekTo(saved)
                    }
                }
                if (autoPlay) thermoPlayer.play()
            }.onFailure { t ->
                Log.e("PlayerViewModel", "开播失败 uri=$uri", t)
                _message.value = "无法播放：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 多选连播。
     *
     * 只预检第 1 条：预检要读 4KB 文件头，选 30 集时就是 30 次读盘 + 30 次
     * 主线程等待，用户会以为点下去没反应。后面几条真出问题，:decoder 的
     * RoutePlanner.onPlaybackError 会在播到它的时候给出可读提示。
     */
    fun openAll(
        entries: List<PlaylistItem>,
        scenario: PlaybackScenario? = null,
        autoPlay: Boolean = true,
    ) {
        if (entries.isEmpty()) return
        val first = entries.first()
        _title.value = first.title ?: first.uri.lastPathSegment ?: first.uri.toString()
        currentMediaId = first.mediaId ?: first.uri.toString()
        _preflight.value = null
        val sc = scenario ?: PlaybackScenario.guessFrom(first.uri.toString())

        viewModelScope.launch {
            val decision = runCatching { routePlanner.preflight(first.uri) }.getOrNull()
            _preflight.value = decision
            if (decision != null && !decision.playable) {
                _message.value = "第 1 条就播不了：" + decision.note
                return@launch
            }
            val items = entries.map { e ->
                MediaItem.Builder()
                    .setUri(e.uri)
                    .setMediaId(e.mediaId ?: e.uri.toString())
                    .build()
            }
            runCatching {
                thermoPlayer.prepareAll(items, sc)
                thermoPlayer.setQualityProfile(_quality.value)
                if (sc != PlaybackScenario.LIVE) {
                    val saved = runCatching { positionStore.load(currentMediaId!!) }.getOrDefault(0L)
                    if (saved > 5_000L) thermoPlayer.seekTo(saved)
                }
                if (autoPlay) thermoPlayer.play()
            }.onFailure { t ->
                Log.e("PlayerViewModel", "连播开播失败 size=" + entries.size, t)
                _message.value = "无法播放：" + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun togglePlayPause() {
        if (snapshot.value.isPlaying) thermoPlayer.pause() else thermoPlayer.play()
    }

    /**
     * 离开播放页时调用：**把这一轮播放彻底收掉**。
     *
     * 以前这里故意只暂停不停止（"返回列表页不停播放"），但本工程刻意没做小窗、
     * 也没做 MediaSession 通知栏控件 —— 回到列表页之后用户既看不到画面，
     * 也找不到任何一个能按的暂停键，只剩声音在响。那不是"后台播放"这个功能，
     * 那是没关掉的播放器。
     *
     * 用 stop() 而不是 pause()：stop() 会把 playbackState 一起收到 IDLE，
     * :app 的前台服务因此也跟着退掉（它的去重条件是 playbackState != STATE_IDLE），
     * 否则会留下一条"已暂停"的常驻通知。位置不会丢 —— 下面先存一次，
     * 而且 ExoPlayer.stop() 之后 prepare() 仍从当前位置恢复。
     *
     * 收之前先把进度落一次盘：周期落盘是 5 秒一次，
     * 只看了 4 秒就退出的话，那 4 秒会白看。
     */
    fun stopPlayback() {
        val s = snapshot.value
        val id = currentMediaId
        val pos = s.positionMs
        if (id != null && pos > 0L) {
            viewModelScope.launch { runCatching { positionStore.save(id, pos, s.durationMs) } }
        }
        // 长按倍速如果还生效着，一并作废：下次进来不该是 2 倍速。
        speedBoosted = false
        thermoPlayer.stop()
    }

    fun seekTo(positionMs: Long) = thermoPlayer.seekTo(positionMs)

    fun seekBy(deltaMs: Long) =
        thermoPlayer.seekTo((snapshot.value.positionMs + deltaMs).coerceAtLeast(0L))

    fun setSpeed(speed: Float) {
        // 用户在倍速面板里明确选了速度：临时倍速作废，否则抬起手指那一下
        // 会把用户刚选的值覆盖回长按前的旧值。
        speedBoosted = false
        thermoPlayer.setPlaybackSpeed(speed)
    }

    /**
     * 长按全屏：临时切到设置里配的倍速。
     *
     * 为什么要"临时"而不是直接切过去：长按是个**持续**手势，
     * 用户松手时的预期是"回到原来的速度"。做成切换的话，用户松手后发现
     * 还在倍速，得再点开面板改回来 —— 那是个 bug 而不是功能。
     */
    fun onSpeedBoostStart() {
        if (speedBoosted) return
        val boost = settings.longPressSpeed.value
        val current = snapshot.value.playbackSpeed
        // 用户本来就选了 ≥ 目标倍速：不折腾，直接不动。
        if (current >= boost - 0.01f) return
        speedBeforeBoost = current
        speedBoosted = true
        thermoPlayer.setPlaybackSpeed(boost)
    }

    /** 手指抬起：还原。幂等 —— 手势被打断时也会被调一次。 */
    fun onSpeedBoostEnd() {
        if (!speedBoosted) return
        speedBoosted = false
        thermoPlayer.setPlaybackSpeed(speedBeforeBoost)
    }

    fun setQuality(profile: QualityProfile) {
        _quality.value = profile
        thermoPlayer.setQualityProfile(profile)
    }

    /** 0 = 不限。UI 上的"原画/1080p/720p/480p"四档。 */
    fun setQualityTier(tier: Int) {
        setQuality(
            when (tier) {
                1 -> QualityProfile.CAP_1080P
                2 -> QualityProfile.CAP_720P
                3 -> QualityProfile.LOWEST
                else -> QualityProfile.UNRESTRICTED
            },
        )
    }

    // ---------------- 轨道选择 ----------------

    /**
     * 选中某条具体轨道。
     *
     * 注意 `setOverrideForType` 会**锁定**这一路不再做自适应切换：
     * 网络流一旦手动选了某条码率，ABR 就不再帮你了。这是用户明确的意图，
     * 但对低发热是有利的 —— 手动锁一条低码率，播放器就不会在网速波动时
     * 偷偷切到高码率去解码。
     */
    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        val p = thermoPlayer.player.value ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    /** 取消某一类轨道的锁定，交回给自动选择。 */
    fun clearOverride(type: Int) {
        val p = thermoPlayer.player.value ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .build()
    }

    /** 整类轨道关掉（典型用法：关字幕）。 */
    fun setTrackTypeDisabled(type: Int, disabled: Boolean) {
        val p = thermoPlayer.player.value ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, disabled)
            .build()
    }

    fun isTypeDisabled(type: Int): Boolean =
        thermoPlayer.player.value?.trackSelectionParameters?.disabledTrackTypes?.contains(type) == true

    /**
     * 绑定承载画面的 Window。
     *
     * [ThermoPlayer] 会用 `Window.setPreferredDisplayModeId` 把屏幕刷新率
     * 切到与视频帧率成整数倍的那一档。这是"省电"里最容易漏掉的一环：
     * 24fps 的电影跑在 120Hz 屏幕上，如果不开 LTPO 自适应，
     * 屏幕每秒要多刷 96 次 —— 屏幕本身就是整机最大的耗电件。
     *
     * @param window 页面销毁时必须传 null，否则退出播放器后窗口还留着
     *               上一部片子的刷新率（表现为桌面滑动发涩）。
     */
    fun bindWindow(window: android.view.Window?) {
        thermoPlayer.bindWindow(window)
    }

    /**
     * 把"跟随视频帧率"设置同步给播放器。
     *
     * 注意语义：关掉它**不是**"不切刷新率"，而是"不许挑 60Hz 以上的档"。
     * 用户关这个开关的动机通常是"别给我强上 120Hz"，
     * 那么仍然挑一个与视频帧率整除的 60Hz 档才对 —— 直接不切反而更费电
     * （60Hz 屏上放 24fps，靠 3:2 pulldown 反而更抖更费算力）。
     */
    fun applyFrameRatePreference(follow: Boolean) {
        thermoPlayer.setHighRefreshAllowed(follow)
    }

    /**
     * 用户手动开关省电模式。
     *
     * 它的优先级**高于**温度自动降级：用户说了算。
     * 但两者不是覆盖关系而是取交集 —— 用户关了省电模式，
     * 手机烫了照样会被温度策略降级（这一点在 :app 的 ThermalPolicyCoordinator 里实现）。
     */

    /**
     * 是否允许软解兜底。
     *
     * 关掉之后，硬解起不来的片子会直接报错而不是转 CPU 解码 ——
     * 这是"宁可看不了，也别把手机烫着"的那种取舍，所以交给用户决定。
     */
    fun setAllowSoftwareFallback(enabled: Boolean) {
        settings.setAllowSoftwareFallback(enabled)
    }
    fun setPowerSaveMode(enabled: Boolean) {
        settings.setPowerSaveMode(enabled)
    }

    /**
     * 视频后台播放开关。**默认关**。
     *
     * 关掉时：退到桌面（或按 Home）立刻暂停，回来不自动续播。
     * 打开时：退到桌面继续放；若「后台仅音频」也开着，则摘掉视频 Surface，
     * 只留音频解码器（功耗 1.5~2.5W -> 0.1~0.3W）。
     *
     * 注意它和「从播放页返回列表页」是两件事：后者是用户主动结束这一轮播放，
     * 无论这个开关怎么设都会 stop（见 stopPlayback）。
     */
    fun setBackgroundPlayback(enabled: Boolean) {
        settings.setBackgroundPlayback(enabled)
    }

    fun setAudioOnlyInBackground(enabled: Boolean) {
        settings.setAudioOnlyInBackground(enabled)
    }

    /** 分片数在 1~32 之间夹紧；下载引擎在下次启动任务时读取。 */
    fun setDownloadThreads(count: Int) {
        settings.setDownloadThreads(count)
    }

    fun setLongPressSpeed(speed: Float) {
        settings.setLongPressSpeed(speed)
    }

    fun setDisplayMode(mode: DisplayMode) {
        settings.setDisplayMode(mode)
    }

    fun setShowThumbnails(show: Boolean) {
        settings.setShowThumbnails(show)
    }
    fun onPauseForBackground() {
        // 后台不一定停 —— 只听音频时继续播是有意为之（见 :app 的 PlaybackService）。
        // 这里只负责把解码器省下来：有视频轨的话，退出前台就暂停。
        val p = thermoPlayer.player.value ?: return
        val hasVideo = p.currentTracks.containsType(C.TRACK_TYPE_VIDEO)
        if (hasVideo && settings.audioOnlyInBackground.value.not()) {
            p.pause()
        }
    }

    override fun onCleared() {
        // 不调 thermoPlayer.release()：它是 @Singleton，
        // 旋转屏幕/返回列表页都会走 onCleared，一 release 就得重新建 ExoPlayer，
        // 而重建 ExoPlayer 意味着重新申请 MediaCodec、重新协商 AudioTrack。
        // 真正的释放由 PlaybackService 在"没有任何界面在用它"时做。
        super.onCleared()
    }


}
