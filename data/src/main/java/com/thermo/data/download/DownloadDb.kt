package com.thermo.data.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 下载任务表的持久化。
 *
 * 和 PlaybackPositionStore 一样刻意不用 Room：一张表、四个查询，
 * 引一套注解处理器换不来任何东西。区别是这张表**不需要 WAL** ——
 * 它的写入频率是"每个任务每 3 秒一次"，比播放进度还低。
 *
 * 为什么下载列表必须持久化：用户下了一个 2GB 的片子，中途接电话被系统杀进程，
 * 重新打开时列表空了、文件还在，那不叫"下载器"，那叫"垃圾制造机"。
 */
internal class DownloadDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "thermo_downloads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE downloads (
                id             TEXT PRIMARY KEY,
                url            TEXT NOT NULL,
                title          TEXT NOT NULL,
                total_bytes    INTEGER NOT NULL,
                downloaded     INTEGER NOT NULL,
                status         TEXT NOT NULL,
                threads        INTEGER NOT NULL,
                file_path      TEXT NOT NULL,
                supports_range INTEGER NOT NULL,
                created_at     INTEGER NOT NULL,
                error          TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_downloads_created ON downloads(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只有一版。真要加列时在这里写 ALTER TABLE，不要 DROP —— 用户下载到一半的文件还在。
    }

    fun loadAll(): List<DownloadTask> {
        val out = ArrayList<DownloadTask>()
        runCatching {
            readableDatabase.query(
                "downloads", null, null, null, null, null, "created_at DESC",
            ).use { c ->
                while (c.moveToNext()) {
                    out += DownloadTask(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        totalBytes = c.getLong(c.getColumnIndexOrThrow("total_bytes")),
                        downloadedBytes = c.getLong(c.getColumnIndexOrThrow("downloaded")),
                        status = runCatching {
                            DownloadStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status")))
                        }.getOrDefault(DownloadStatus.PAUSED),
                        threads = c.getInt(c.getColumnIndexOrThrow("threads")),
                        filePath = c.getString(c.getColumnIndexOrThrow("file_path")),
                        supportsRange = c.getInt(c.getColumnIndexOrThrow("supports_range")) != 0,
                        createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        error = c.getString(c.getColumnIndexOrThrow("error")),
                    )
                }
            }
        }
        return out
    }

    fun upsert(task: DownloadTask) {
        runCatching {
            val v = ContentValues().apply {
                put("id", task.id)
                put("url", task.url)
                put("title", task.title)
                put("total_bytes", task.totalBytes)
                put("downloaded", task.downloadedBytes)
                put("status", task.status.name)
                put("threads", task.threads)
                put("file_path", task.filePath)
                put("supports_range", if (task.supportsRange) 1 else 0)
                put("created_at", task.createdAtMs)
                put("error", task.error)
            }
            writableDatabase.insertWithOnConflict(
                "downloads", null, v, SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun delete(id: String) {
        runCatching { writableDatabase.delete("downloads", "id = ?", arrayOf(id)) }
    }
}