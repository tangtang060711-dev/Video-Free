package com.thermo.data.progress

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一条播放历史。 */
data class RecentPlayback(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/**
 * 播放进度持久化。
 *
 * ## 为什么不用 Room
 * 这张表只有 4 个字段、3 种查询（按 id 读一条、按时间倒序读 N 条、删一条）。
 * 引 Room 要付出：注解处理器（KSP 多跑一轮）、schema 导出配置、
 * 以及 **一个几十 MB 的编译期依赖链**。为了 4 个字段不值得。
 * 直接用框架自带的 SQLiteOpenHelper，零依赖、零代码生成。
 *
 * ## 为什么所有方法都是 suspend + Dispatchers.IO
 * SQLite 的写是会碰磁盘的（虽然走 WAL，仍会有 fsync）。
 * 在播放页里，"每 5 秒存一次进度"如果发生在主线程，
 * 就是每 5 秒给 UI 线程塞一次可能几毫秒的阻塞 —— 表现为周期性掉帧。
 * 全部挪到 IO 线程，调用方只需要 `launch`。
 */
@Singleton
class PlaybackPositionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val helper = ProgressDbHelper(context.applicationContext)

    /**
     * 写入/更新进度。
     * 用 INSERT OR REPLACE 而不是先查后写：这里只需要最终值，
     * 两次调用之间的窗口期读到旧值是完全可以接受的。
     */
    suspend fun save(mediaId: String, positionMs: Long, durationMs: Long) {
        if (mediaId.isEmpty()) return
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(COL_MEDIA_ID, mediaId)
                put(COL_POSITION, positionMs.coerceAtLeast(0L))
                put(COL_DURATION, durationMs.coerceAtLeast(0L))
                put(COL_UPDATED_AT, System.currentTimeMillis())
            }
            runCatching {
                helper.writableDatabase.insertWithOnConflict(
                    TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    /** 读取上次位置；没有记录返回 0。 */
    suspend fun load(mediaId: String): Long = withContext(Dispatchers.IO) {
        if (mediaId.isEmpty()) return@withContext 0L
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT $COL_POSITION FROM $TABLE_NAME WHERE $COL_MEDIA_ID = ? LIMIT 1",
                arrayOf(mediaId),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.getOrDefault(0L)
    }

    /** 最近播放，用于首页"继续观看"。 */
    suspend fun recent(limit: Int = 20): List<RecentPlayback> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList<RecentPlayback>()
        runCatching {
            val out = ArrayList<RecentPlayback>(limit)
            helper.readableDatabase.rawQuery(
                "SELECT $COL_MEDIA_ID, $COL_POSITION, $COL_DURATION, $COL_UPDATED_AT " +
                    "FROM $TABLE_NAME ORDER BY $COL_UPDATED_AT DESC LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out += RecentPlayback(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 看完一个视频就把它从"继续观看"里移除。 */
    suspend fun remove(mediaId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                helper.writableDatabase.delete(TABLE_NAME, "$COL_MEDIA_ID = ?", arrayOf(mediaId))
            }
        }
    }

    /**
     * 进程退出时关库。
     * 不关的后果不是崩溃（进程死了 fd 也回收了），而是
     * "调试时用 StrictMode 会看到 leaked SQLiteConnection" 这类噪音。
     */
    fun close() {
        runCatching { helper.close() }
    }

    private class ProgressDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                    $COL_MEDIA_ID  TEXT PRIMARY KEY NOT NULL,
                    $COL_POSITION  INTEGER NOT NULL DEFAULT 0,
                    $COL_DURATION  INTEGER NOT NULL DEFAULT 0,
                    $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // 按更新时间倒序取"最近播放"是唯一的排序查询，给它一个索引。
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_updated_at ON $TABLE_NAME ($COL_UPDATED_AT DESC)",
            )
        }

        /**
         * 目前只有 v1。这里把升级路径写出来是为了将来加字段时
         * 不用重新推演"上一版是什么样"。
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // v2 预留：db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN xxx INTEGER")
            }
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // WAL：读写不互斥，播放页高频写进度时不会卡住 UI 的读取。
            db.enableWriteAheadLogging()
        }
    }

    companion object {
        private const val DB_NAME = "thermo_playback.db"
        private const val DB_VERSION = 1
        private const val TABLE_NAME = "playback_progress"
        private const val COL_MEDIA_ID = "media_id"
        private const val COL_POSITION = "position_ms"
        private const val COL_DURATION = "duration_ms"
        private const val COL_UPDATED_AT = "updated_at"
    }
}