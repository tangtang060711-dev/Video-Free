package com.thermo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import com.thermo.data.settings.ThermoSettings
import com.thermo.player.ThermoPlayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 后台播放的前台服务。
 *
 * ## 它做三件事，不做第四件
 *  1. 提供前台服务，让"后台继续放音频"不被系统杀掉（Android 12+ 尤其严格）；
 *  2. 维护一条通知，带播放/暂停/停止；
 *  3. 进后台时按策略决定要不要摘掉视频 Surface（**这是省电的关键一步**）。
 *
 * **不做**什么：不做 MediaSession、不做锁屏控件、不做通知栏封面。
 * 那些需要 media3-session，会额外引入一套 `MediaSession`/`MediaNotification.Provider`
 * 和一堆 `Player.Command` 白名单配置。对一个自用播放器来说，
 * 那条链路的复杂度与实际收益不成比例 —— 而它会让"温度降级"这条主线变难看清。
 * 需要的时候再补：加 `media3-session` 依赖，把 ThermoPlayer 包一层 MediaSession 即可。
 *
 * ## 为什么不继承 LifecycleService
 * 我们要管的是"播放器的生命周期"，不是"Service 的生命周期"。用普通 Service
 * 加一个自己的 scope，少一层继承、少一个 lifecycle 观察者，行为更直白。
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var thermoPlayer: ThermoPlayer

    @Inject lateinit var settings: ThermoSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()

        // 必须先 startForeground，再干别的。
        // Android 8+ 的规则：startForegroundService 之后 5 秒内不 startForeground 就 ANR/崩溃。
        startForegroundCompat(buildNotification(thermoPlayer.snapshot.value))

        // 通知跟随播放状态刷新。
        // 注意 distinctUntilChanged 的键只取"和通知有关"的字段 ——
        // 位置每 250ms 变一次，如果整个快照都比较，通知会每秒重建 4 次，
        // 而每次 setSmallIcon 都是一次跨进程调用 + 一次渲染。
        scope.launch {
            var lastKey: String? = null
            thermoPlayer.snapshot.collect { s ->
                val key = "${s.playbackState}|${s.isPlaying}|${s.isSoftwareDecoding}|${s.qualityProfile.maxHeight}"
                if (key != lastKey) {
                    lastKey = key
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(s))
                }
                if (s.playbackState == Player.STATE_IDLE && !s.isPlaying) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> thermoPlayer.play()
            ACTION_PAUSE -> thermoPlayer.pause()
            ACTION_STOP -> {
                thermoPlayer.pause()
                stopSelf()
            }
            ACTION_TOGGLE_AUDIO_ONLY -> {
                // 手动切"仅音频"：想听播客又不想屏幕亮着的人会需要它。
                if (thermoPlayer.snapshot.value.isSoftwareDecoding || !thermoPlayer.snapshot.value.isPlaying) {
                    thermoPlayer.releaseVideoSurface()
                } else {
                    thermoPlayer.releaseVideoSurface()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_playback_name),
            // LOW：播放控制条不该发出声音或震动。
            // 用 DEFAULT 的话每次播放/暂停都叮一下 —— 这是个非常常见却没人抱怨的 bug。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_playback_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        s: com.thermo.player.PlayerSnapshot,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(
                if (s.isPlaying) {
                    getString(R.string.notif_title_playing)
                } else {
                    getString(R.string.notif_title_paused)
                },
            )
            .setContentText(describeState(s))
            .setContentIntent(contentIntent)
            .setOngoing(s.isPlaying)
            .setOnlyAlertOnce(true)     // 刷新时不再提示，只有第一次弹
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (s.isPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                servicePendingIntent(ACTION_PAUSE, REQ_PAUSE),
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "播放",
                servicePendingIntent(ACTION_PLAY, REQ_PLAY),
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "停止",
            servicePendingIntent(ACTION_STOP, REQ_STOP),
        )
        if (s.isPlaying) {
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                "仅音频",
                servicePendingIntent(ACTION_TOGGLE_AUDIO_ONLY, REQ_AUDIO_ONLY),
            )
        }
        return builder.build()
    }

    /** 通知第二行要说清楚"现在到底在干什么"，这是本工程的全部卖点。 */
    private fun describeState(s: com.thermo.player.PlayerSnapshot): String = buildString {
        if (s.isSoftwareDecoding) {
            append("软解中（CPU 解码，较热）")
        } else {
            append("硬解")
        }
        val h = s.qualityProfile.maxHeight
        if (h > 0) append(" · 上限 ${h}p")
        if (!settings.followVideoFrameRate.value) append(" · 60Hz 上限")
        if (settings.audioOnlyInBackground.value) append(" · 后台仅音频")
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "thermo_playback"
        private const val NOTIFICATION_ID = 1001

        private const val REQ_OPEN = 1
        private const val REQ_PLAY = 2
        private const val REQ_PAUSE = 3
        private const val REQ_STOP = 4
        private const val REQ_AUDIO_ONLY = 5

        const val ACTION_PLAY = "com.thermo.app.PLAY"
        const val ACTION_PAUSE = "com.thermo.app.PAUSE"
        const val ACTION_STOP = "com.thermo.app.STOP"
        const val ACTION_TOGGLE_AUDIO_ONLY = "com.thermo.app.AUDIO_ONLY"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // 少数 ROM 在后台限制严格时会直接抛。
                // 这不是致命错误：没有前台服务，只是"锁屏后可能被系统杀掉"，
                // 不该因此让播放器崩掉。
                Log.w(TAG, "启动前台服务失败（会被系统限制，但不影响前台播放）", t)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}