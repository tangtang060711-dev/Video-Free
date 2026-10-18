package com.thermo.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地媒体库扫描。
 *
 * ## 设计要点
 * 1) **不申请权限**。权限是 UI 的事（要弹系统对话框、要处理"拒绝后不再询问"）。
 *    这里只负责"有权限就查，没权限就返回空"，把权限状态当成一个普通的前置条件。
 *    SecurityException 一律吞掉返回空列表 —— 一个扫描器不该让 App 崩。
 *
 * 2) **返回 Flow 而不是 List**。用户在系统相册里删掉一个视频，我们的列表要跟着变。
 *    靠 ContentObserver 监听 MediaStore，变化时重新查询并发射。
 *
 * 3) **查询绝不在主线程做**。ContentObserver 的回调固定跑在主线程（我们只能选
 *    哪个 Looper，不能选哪个线程），所以在回调里不直接查，而是 `launch` 出去，
 *    flow 的上下文是 Dispatchers.IO。低端机上 MediaStore 查询能到几十毫秒，
 *    在回调里同步查会明显掉帧。
 *
 * 4) **conflate()**。批量导入视频时 MediaStore 会连续发几十次 onChange，
 *    每次都完整查一遍纯属浪费。conflate 保证"正在查的时候新来的通知被合并成一次"。
 */
@Singleton
class LocalMediaScanner @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** 当前是否具备读取本地媒体的权限。UI 用它在列表为空时决定提示什么。 */
    fun hasMediaPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 按 Android 版本返回实际需要申请的权限。API 33 起细分成 VIDEO / AUDIO。 */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 视频列表，按加入时间倒序。 */
    fun videos(): Flow<List<MediaEntry>> = observe(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) {
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
    }

    /** 音频列表，按加入时间倒序。 */
    fun audios(): Flow<List<MediaEntry>> = observe(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) {
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, isVideo = false)
    }

    private fun observe(
        uri: Uri,
        query: () -> List<MediaEntry>,
    ): Flow<List<MediaEntry>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // 见类注释第 3、4 点。
                launch { send(query()) }
            }
        }
        // 先发一帧当前快照，避免 UI 在第一次 onChange 之前一直是空列表。
        send(query())
        resolver.registerContentObserver(uri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().flowOn(Dispatchers.IO)

    @Suppress("TooGenericExceptionCaught")
    private fun query(collection: Uri, isVideo: Boolean): List<MediaEntry> = try {
        // 用字符串字面量而不是 MediaStore 常量：DURATION / WIDTH / HEIGHT 这三个
        // 常量是 API 29 才加入 MediaStore.Video.VideoColumns 的，但底层列名
        // （"duration"/"width"/"height"）从 API 1 就存在，用字面量可以在
        // API 24~28 上正常工作而不触发 NewApi/编译期错误。
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "duration",
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            "width",
            "height",
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val out = ArrayList<MediaEntry>(64)
        resolver.query(collection, projection, null, null, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durCol = c.getColumnIndex("duration")
            val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val wCol = c.getColumnIndex("width")
            val hCol = c.getColumnIndex("height")
            val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += MediaEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol).orEmpty(),
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                    sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                    mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
                    width = if (wCol >= 0) c.getInt(wCol) else 0,
                    height = if (hCol >= 0) c.getInt(hCol) else 0,
                    dateAddedSec = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                    isVideo = isVideo,
                )
            }
        }
        out
    } catch (_: SecurityException) {
        // 没有权限（或被系统收回）。返回空列表，UI 会显示"授权后可扫描本地视频"。
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}