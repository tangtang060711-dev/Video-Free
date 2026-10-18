package com.thermo.data.vault

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 按 URI scheme 分派的数据源工厂。
 *
 * 为什么要在**数据源这一层**做分派，而不是在播放器里判断：
 * Media3 拿到的是一个 Uri，它自己的 DefaultDataSource 只认 file/content/asset/http 等
 * 已知 scheme。"加密空间"是个自定义 scheme，插件式地塞进这一层，
 * 播放器（:core-player）一行都不用改，预检（:decoder 的 ContainerProbe）
 * 也自动拿到了同一套能力 —— 否则嗅探文件头那一步会在自定义 scheme 上失败，
 * 用户会看到"这条媒体无法预检"，而实际上是数据源不认识它。
 */
class SchemeRoutingDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val vault: VaultStore,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        SchemeRoutingDataSource(delegate.createDataSource(), vault)
}

/** 一条 URI 一个分支：vault:// 走解密源，其余原样交给 delegate。 */
class SchemeRoutingDataSource(
    private val delegate: DataSource,
    private val vault: VaultStore,
) : DataSource {

    private var active: DataSource = delegate
    private var vaultSource: VaultDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        // 只挂在代理上。解密源不参与统计：它的字节数等于密文字节数，
        // 混进去会让"网络流量"这类统计出现双份。
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (vault.isVaultUri(dataSpec.uri)) {
            val source = vaultSource ?: VaultDataSource(vault).also { vaultSource = it }
            active = source
            return source.open(dataSpec)
        }
        active = delegate
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun close() {
        try {
            delegate.close()
        } finally {
            vaultSource?.close()
        }
    }
}