package com.thermo.data.datasource

import android.content.Context
import android.os.Build
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import com.thermo.data.cache.MediaCache
import com.thermo.data.vault.SchemeRoutingDataSourceFactory
import com.thermo.data.vault.VaultStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁盘缓存的工作模式。由 :app 的温度策略在运行时切换。
 *
 * 分三档而不是开/关，是因为"缓存"这件事的功耗分两半：
 *   - **读缓存**：省电（省掉网络下载，网络是手机上仅次于屏幕的耗电大户）
 *   - **写缓存**：费电（flash 写入 + fsync，还要额外走一遍内存拷贝）
 * 过热时先砍掉"写"、保留"读"，比一刀切关掉整个缓存更划算。
 */
enum class CacheMode {
    /** 正常：读写都开。 */
    READ_WRITE,

    /** 偏热：还能从已有缓存读，但不再写新的。 */
    READ_ONLY,

    /** 很热：完全绕过缓存（也就不再开那个 SQLite 索引库）。 */
    DISABLED,
}

/**
 * 全工程唯一的网络数据源入口。
 *
 * ## 分层结构（从外到内）
 * ```
 * DefaultDataSource          <- 按 scheme 分发：file/content/asset 本地直读，
 *   |                            只有 http/https 才落到内层
 *   +-- CacheDataSource        <- 查磁盘缓存；未命中则穿透到 upstream 并回写
 *         +-- DefaultHttpDataSource  <- 真正的 HTTP 连接
 * ```
 * **把 DefaultDataSource 放在最外层是这里最关键的一行设计**：本地文件
 * （content:// 与 file://）会由它直接交给 ContentDataSource / FileDataSource，
 * 根本不会经过缓存层。否则每播一个本地视频都会往缓存目录里抄一份，
 * 白白写几十 GB flash —— 那是纯发热，没有任何收益。
 *
 * ## 连接超时为什么是这个值
 * connect 10s / read 20s。Media3 的默认值是 8s/8s，对首屏够用，但对
 * "拖动进度条后重新拉分片"这类场景偏紧：4G 弱信号下建立 TLS 就可能花 5s。
 * 调大了会不会更耗电？不会 —— 超时是**上限**不是**等待**，超时前拿到数据
 * 就立刻返回。它只影响"网络真的挂掉时多久报错"。
 */
@Singleton
class ThermoDataSourceFactory @Inject constructor(
    @ApplicationContext context: Context,
    private val mediaCache: MediaCache,
    private val vaultStore: VaultStore,
) : DataSource.Factory {

    private val appContext = context.applicationContext

    /**
     * 当前缓存模式。`@Volatile` 是必须的：它由主线程的温度策略写，
     * 由 ExoPlayer 的加载线程（Loading thread）读。
     */
    @Volatile
    var cacheMode: CacheMode = CacheMode.READ_WRITE

    private val httpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)
        // 很多 CDN 会在 http -> https 之间跳转，media3 默认拒绝跨协议跳转。
        .setAllowCrossProtocolRedirects(true)
        // 302 后保留 POST 体：部分站点用 POST 换真实地址。
        .setKeepPostFor302Redirects(true)

    /** 不走缓存的那条路：网络直接读。 */
    private val uncachedSource: DataSource.Factory =
        DefaultDataSource.Factory(appContext, httpFactory)

    /** 可读可写缓存的那条路。 */
    private val readWriteSource: DataSource.Factory by lazy {
        val cache = mediaCache.simpleCache
        DefaultDataSource.Factory(
            appContext,
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory()
                        .setCache(cache)
                        // 分片大小 5MiB：太小会让 SQLite 索引里塞满碎片记录，
                        // 每次 open 都要扫一遍；太大则一次写 flash 的时间过长，
                        // 在热节流的场景下容易触发长时间的写入阻塞。
                        .setFragmentSize(FRAGMENT_SIZE_BYTES),
                )
                // 缓存本身出问题（索引坏、磁盘满）时直接忽略缓存继续播，
                // 不要让"缓存坏了"变成"视频播不了"。
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
    }

    /** 只读缓存的那条路：命中就省网络，未命中就直接走网络、不回写。 */
    private val readOnlySource: DataSource.Factory by lazy {
        val cache = mediaCache.simpleCache
        DefaultDataSource.Factory(
            appContext,
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory)
                // 关键：把 sink 设为 null 就等于"只读不写"。
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
    }

    override fun createDataSource(): DataSource {
        val factory = when (cacheMode) {
            CacheMode.READ_WRITE -> readWriteSource
            CacheMode.READ_ONLY -> readOnlySource
            CacheMode.DISABLED -> uncachedSource
        }
        // 在缓存/网络那一层之外再包一层 scheme 分派：
        // vault:// 走解密数据源，其余原样透传给上面选出来的 factory。
        // 包在最外层而不是最内层，是因为加密文件根本不该经过 SimpleCache ——
        // 把密文缓存一份，等于在磁盘上留了第二份可读的东西，白费加密。
        return SchemeRoutingDataSourceFactory(factory, vaultStore).createDataSource()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val FRAGMENT_SIZE_BYTES = 5L * 1024L * 1024L

        /**
         * 用真实机型拼一个 Chrome-like UA。
         *
         * 为什么不写死一个固定的 UA 字符串：一部分服务端会按 UA 里的 Android 版本
         * 决定发 H.264 还是 H.265（老版本给 H.264），写死了"Android 14"会在真机上
         * 拿到跟系统能力不匹配的片源。这里如实上报，让服务端自己做对的事。
         */
        val USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36 ThermoPlayer/1.0"
    }
}