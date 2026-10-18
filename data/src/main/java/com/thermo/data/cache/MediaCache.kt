package com.thermo.data.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁盘缓存的持有者。
 *
 * ## 为什么必须是 @Singleton
 * SimpleCache 内部维护一个 SQLite 索引库（media3 的 `StandaloneDatabaseProvider`
 * 建在 `<cacheDir>/media_cache/` 下）。**同一个目录在同一进程里只能有一个
 * SimpleCache 实例**，否则两个实例会各自持有一份内存索引，写回时相互覆盖，
 * 表现为"缓存命中率永远是 0"甚至索引损坏。
 * media3 自己也知道这点：`SimpleCache.isCacheFolderLocked(File)` 就是用来检测
 * "这个目录已经被别的 SimpleCache 占了"的。
 * 这里的 `@Singleton` 不是"省内存"的优化，是正确性要求。
 *
 * ## 为什么是 256MB 而不是 1GB
 * 缓存的目的不是"离线看"，是"回看时不用重新下载"。1080p 8Mbps 的片子，
 * 256MB 约等于 4 分钟。更大的缓存在手机上的真实收益趋近于零，代价却是：
 *   - 后台写 flash 的时间更长（写 flash 是实打实的发热与耗电）
 *   - 存储紧张时容易被系统整体清掉
 * 而且 :app 的温度策略在偏热时会先把"写缓存"关掉
 * （见 ThermoDataSourceFactory.cacheMode），所以这里给的是一个
 * "正常状态下够用、过热时可被牺牲"的量。
 *
 * ## media3 1.9.x 的 API 变化
 * `SimpleCache` 的构造函数**不再接受外部传入的 Lock**（1.5.x 及更早的版本需要）。
 * 现在只有三种重载：(File, CacheEvictor) / (File, CacheEvictor, DatabaseProvider)
 * / (File, CacheEvictor, DatabaseProvider, byte[], boolean, boolean)。
 * 内部自己管锁，外部不需要也不应该再插手。
 */
@Singleton
class MediaCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    val cacheDir: File = File(appContext.cacheDir, CACHE_DIR_NAME)

    @Volatile
    private var released = false

    val simpleCache: SimpleCache by lazy {
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(appContext),
        )
    }

    /**
     * 释放缓存。只应该在**进程即将退出**时调用 —— 一旦释放，SimpleCache 就不能
     * 再被使用（内部索引已关闭），而 Hilt 的 @Singleton 不会再给一个新实例。
     * :app 的 Application 会在最后调它。
     *
     * 用 `released` 自己记状态而不是读 media3 的字段：media3 1.9.x 的
     * SimpleCache **没有** `isReleased()` 这样的查询接口，二次 release 会抛
     * IllegalStateException。
     */
    fun release() {
        if (released) return
        released = true
        runCatching { simpleCache.release() }
    }

    private companion object {
        const val CACHE_DIR_NAME = "media_cache"

        /** 256 MiB。见类注释里的取舍说明。 */
        const val MAX_BYTES = 256L * 1024L * 1024L
    }
}