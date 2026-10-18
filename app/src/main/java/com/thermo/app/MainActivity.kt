package com.thermo.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.thermo.app.route.PlaybackRouteState
import com.thermo.app.download.DownloadScreen
import com.thermo.app.route.AppScreen
import com.thermo.app.vault.VaultScreen
import com.thermo.app.vault.VaultViewModel
import com.thermo.app.ui.HomeScreen
import com.thermo.app.ui.SettingsScreen
import com.thermo.data.settings.OrientationMode
import com.thermo.data.settings.ThermoSettings
import com.thermo.player.ThermoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import com.thermo.ui.PlayerViewModel
import com.thermo.ui.player.PlayerScreen
import com.thermo.ui.PlaylistItem
import com.thermo.ui.theme.ThermoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.thermo.data.download.DownloadManager

/**
 * 唯一的 Activity。
 *
 * 为什么不做多 Activity：播放页和列表页共享同一个 ExoPlayer（@Singleton），
 * 多 Activity 时"返回列表页"会销毁播放页，Surface 会被摘掉再挂上，
 * 中间那一下黑屏是自己给自己制造的。单 Activity + Compose 内部换页没有这个问题。
 *
 * 也不用 navigation-compose：只有两个页面，一个单例状态就够。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var thermoPlayer: ThermoPlayer

    @Inject lateinit var route: PlaybackRouteState

    @Inject lateinit var downloads: DownloadManager

    @Inject lateinit var settings: ThermoSettings

    /**
     * 外部（文件管理器/浏览器）带进来的 URI。
     *
     * 用 State 而不是普通字段：onNewIntent 之后要能触发重组，
     * 否则"在文件管理器里再点一个视频"不会切过去。
     */
    private val externalUri = mutableStateOf<Uri?>(null)

    /** 屏幕方向控制。Manifest 上不再声明方向，全部由它决定。 */
    private lateinit var orientation: OrientationController

    /**
     * 当前生效的方向名，播放页角落用它显示现在是哪一档。
     *
     * 用 StateFlow 而不是普通字段：控制器在 onCreate 里才构造，
     * 而 Composable 需要一个可观察的源，否则换方向之后标签不会刷新。
     */
    private val orientationLabel = MutableStateFlow("跟随系统")

    /** 设置页里选的策略。控制器负责执行，这里只是为了让设置页能回显当前值。 */
    private val orientationSetting = MutableStateFlow(OrientationMode.AUTO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // savedInstanceState != null 说明这次是重建（旋转、被系统回收后恢复）。
        // 此时 intent 里还是启动时那个 URI，再取一次会把"用户已经返回列表页"
        // 这个状态又顶回播放页 —— 也就是优化 3 里看到的"重新进来又回到播放页"。
        if (savedInstanceState == null) {
            externalUri.value = extractUri(intent)
            handleDeepLink(intent)
        }

        // 前台服务的启停跟着"播放器是不是还活着"走。
        // 挂在 Activity 上而不是 Application 上：从没打开过 App 就不该有通知。
        //
        // 本地记一个上一次的值而不是让 collect 每次都比：
        // snapshot 每 250ms 发一次，直接比较会每秒调 4 次 startService/stopService，
        // 而 startService 是跨进程调用 —— 那本身就是一个不该有的耗电点。
        lifecycleScope.launch {
            var serviceWanted: Boolean? = null
            thermoPlayer.snapshot.collect { s ->
                val want = s.playbackState != Player.STATE_IDLE
                if (want != serviceWanted) {
                    serviceWanted = want
                    if (want) PlaybackService.start(this@MainActivity) else PlaybackService.stop(this@MainActivity)
                }
            }
        }

        // 屏幕方向。控制器不自己持有设置，设置从 ThermoSettings 的 Flow 里拿，
        // 这样设置页改完立刻生效，不用重启 Activity。
        orientation = OrientationController(this) { label -> orientationLabel.value = label }
        lifecycleScope.launch {
            settings.orientationMode.collect {
                // 两件事：让控制器按新策略立刻生效；把值同步给设置页做回显。
                orientation.setSetting(it)
                orientationSetting.value = it
            }
        }

        setContent {
            // 强制深色：OLED 白底比黑底多耗几倍电，这是播放器最廉价的省电手段。
            ThermoTheme(darkTheme = true) {
                val label by orientationLabel.collectAsStateWithLifecycle()
                AppRoot(
                    initialUri = externalUri.value,
                    route = route,
                    orientationLabel = label,
                    onCycleOrientation = {
                        val target = if (orientation.isLandscape()) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE
                        orientation.setSessionOverride(target)
                    },
                    onResetOrientation = { orientation.setSessionOverride(null) },
                    orientationMode = orientationSetting.collectAsStateWithLifecycle().value,
                    onOrientationMode = { settings.setOrientationMode(it) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.i("ThermoApp", "ACT start")
        // 进前台才开始听传感器与系统锁定开关：后台不需要，也就没必要让它耗电。
        orientation.start()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.i("ThermoApp", "ACT resume")
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.i("ThermoApp", "ACT pause")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.i("ThermoApp", "ACT stop")
        orientation.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("ThermoApp", "ACT focus=" + hasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalUri.value = extractUri(intent)
        handleDeepLink(intent)
    }

    private fun extractUri(intent: Intent?): Uri? = when {
        intent == null -> null
        intent.action == Intent.ACTION_VIEW && intent.data?.scheme != DEEP_LINK_SCHEME -> intent.data
        else -> null
    }

    /**
     * 应用内深链：thermo://download?url=...&title=...
     *
     * 为什么要有这个入口：下载器需要一条"从外部喂地址进来"的路径。
     * 在真机上验证时尤其关键 —— Android 17 已经把 adb shell input 封掉了，
     * 点不了屏幕，自动化验证必须自己留一个可编程入口。
     * 顺带它也是浏览器"用其他应用打开"的现成实现。
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != DEEP_LINK_SCHEME) return
        when (data.host) {
            "download" -> {
                val url = data.getQueryParameter("url")?.trim().orEmpty()
                route.openDownloads()
                if (url.isEmpty()) return
                val title = data.getQueryParameter("title")
                runCatching { downloads.enqueue(url, title) }
                    .onFailure { android.util.Log.e("ThermoApp", "深链下载失败", it) }
            }

            // 方向策略的验证入口：thermo://orient?mode=auto|portrait|landscape|reverse_portrait|reverse_landscape
            // 红米 K80 Pro 上 adb shell input 与 settings put system 都被系统拒了，
            // 改不了系统旋转锁，所以只能让应用自己改策略，再从外面 dumpsys 看结果。
            "orient" -> {
                val mode = OrientationMode.fromKey(data.getQueryParameter("mode"))
                settings.setOrientationMode(mode)
                android.util.Log.i("ThermoApp", "深链设置方向 = " + mode.key)
            }
        }
    }
    private companion object {
        /** 深链 scheme，见 handleDeepLink。 */
        const val DEEP_LINK_SCHEME = "thermo"
    }


}

/**
 * 两个页面之间的路由。
 *
 * null = 列表页，非 null = 播放页。这个状态存在 [PlaybackRouteState] 里而不是
 * remember 里，理由见那个类的注释（优化 1 / 优化 3）。
 */
@Composable
private fun AppRoot(
    initialUri: Uri?,
    route: PlaybackRouteState,
    orientationLabel: String,
    onCycleOrientation: () -> Unit,
    onResetOrientation: () -> Unit,
    orientationMode: OrientationMode,
    onOrientationMode: (OrientationMode) -> Unit,
) {
    val activity = LocalContext.current.findActivity()

    // 单 Activity 下 hiltViewModel() 拿到的就是同一个实例，
    // 显式取出并传递，是为了让"播放页和列表页共用同一个 ViewModel"这件事写在代码里。
    val viewModel: PlayerViewModel = hiltViewModel()

    // 当前页面。它是单例状态而不是 remember：Activity 一重建就掉回列表页，
    // 正是优化 1 与优化 3 那个"画面蒙了一层黑"的根因。
    val screen by route.screen.collectAsStateWithLifecycle()

    // 加密空间的 VM 在这里取一次并往下传，确保返回时能拿到同一个实例去上锁。
    val vaultVm: VaultViewModel = hiltViewModel()

    // 显示方式 / 缩略图开关：存在 :data 的 ThermoSettings 里，这里取出来给两个页面用。
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val showThumbnails by viewModel.showThumbnails.collectAsStateWithLifecycle()

    // 离开播放页 = 这一轮播放结束。两个入口（返回手势 / 播放器左上角按钮）
    // 共用同一个动作，免得日后只改了一处。
    val leavePlayer: () -> Unit = {
        viewModel.stopPlayback()
        // 离开播放页 = 这一轮手动选的方向也结束。刻意不写进设置：
        // 免得"为了躺床上看一集"改的方向，第二天还在。
        onResetOrientation()
        route.backToList()
    }

    // 返回键 / 侧滑手势：**逐级退回**，回到主界面再按才是退出应用。
    //
    // 之前这里一律调 activity.finish()，结果在播放页侧滑就直接回桌面，
    // 而不是退回视频列表 —— "退出应用"和"退回上一级"是两种语义，
    // 用户对返回手势的预期永远是后者。要退到桌面请从主界面再按一次。
    BackHandler(enabled = true) {
        when (screen) {
            // 播放页 -> 列表页，**不停播放**：按返回多半是想看别的，不是想停下来。
            AppScreen.PLAYER -> leavePlayer()
            // 加密空间 -> 列表页，顺手把密钥放下：这一页的会话结束了。
            AppScreen.VAULT -> {
                vaultVm.lock()
                route.closeVault()
            }
            AppScreen.SETTINGS -> route.closeSettings()
            AppScreen.DOWNLOADS -> route.closeDownloads()
            // 已经在主界面，再返回才是真正退出。
            AppScreen.HOME -> activity?.finish()
        }
    }

    // 外部传入的 URI：进页面就直接开播
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            route.open(initialUri)
            viewModel.open(
                uri = initialUri,
                mediaId = initialUri.toString(),
                title = initialUri.lastPathSegment,
            )
        }
    }

    when (screen) {
        AppScreen.PLAYER -> PlayerScreen(
            onBack = {
                // 返回列表页**不停播放**：用户按返回多半是想看别的，而不是想停下来。
                leavePlayer()
            },
            orientationLabel = orientationLabel,
            onCycleOrientation = onCycleOrientation,
        )

        AppScreen.VAULT -> VaultScreen(
            onBack = {
                // 退回列表时顺手把密钥放下：这一页的会话结束了。
                vaultVm.lock()
                route.closeVault()
            },
            onOpen = { uri, name ->
                // 拿到的是 vault://<id>，具体解密由 :data 的 SchemeRoutingDataSourceFactory 做。
                route.open(uri)
                viewModel.open(uri = uri, mediaId = uri.toString(), title = name)
            },
            vm = vaultVm,
        )
        AppScreen.SETTINGS -> SettingsScreen(
            onBack = { route.closeSettings() },
            onOpenDownloads = { route.openDownloads() },
            orientationMode = orientationMode,
            onOrientationMode = onOrientationMode,
            displayMode = displayMode,
            showThumbnails = showThumbnails,
            onDisplayMode = { viewModel.setDisplayMode(it) },
            onShowThumbnails = { viewModel.setShowThumbnails(it) },
        )

        AppScreen.DOWNLOADS -> DownloadScreen(
            onBack = { route.closeDownloads() },
            onPlay = { uri, name ->
                // 下载完成后的文件是 file:// 指向应用私有目录，
                // 直接就能播 -- 不需要任何存储权限。
                route.open(uri)
                viewModel.open(uri = uri, mediaId = uri.toString(), title = name)
            },
        )

        else -> HomeScreen(
            // 单击 = 播这一条
            onOpenLocal = { entry ->
                route.open(entry.uri)
                viewModel.open(
                    uri = entry.uri,
                    mediaId = entry.uri.toString(),
                    title = entry.displayName,
                )
            },
            // 长按多选 -> 连播。列表顺序就是播放顺序。
            // 刻意不在进入播放页之前对每一条都做预检：预检要读 4KB 文件头，
            // 选 30 集就是 30 次读盘，用户会以为点下去没反应。
            onPlayAll = { list ->
                val first = list.firstOrNull()
                if (first != null) {
                    route.open(first.uri)
                    viewModel.openAll(
                        entries = list.map {
                            PlaylistItem(it.uri, it.displayName, it.uri.toString())
                        },
                    )
                }
            },
            // 「从文件管理器选」拿回来的 content:// URI
            onOpenUri = { uri ->
                route.open(uri)
                viewModel.open(uri = uri, mediaId = uri.toString(), title = uri.lastPathSegment)
            },
            // 对话框里手输的链接或本地路径
            onOpenUrl = { text ->
                val parsed = Uri.parse(text)
                route.open(parsed)
                viewModel.open(uri = parsed, mediaId = text, title = text)
            },
            onOpenVault = { route.openVault() },
            onOpenSettings = { route.openSettings() },
            onOpenDownloads = { route.openDownloads() },
            displayMode = displayMode,
            showThumbnails = showThumbnails,
            onDisplayMode = { viewModel.setDisplayMode(it) },
            onShowThumbnails = { viewModel.setShowThumbnails(it) },
        )
    }
}

/** 从任意 Context 找 Activity（Compose 的 LocalContext 可能是 ContextWrapper）。 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
