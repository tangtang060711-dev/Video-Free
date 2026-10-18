package com.thermo.decoder.di

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.thermo.data.vault.SchemeRoutingDataSourceFactory
import com.thermo.data.vault.VaultStore
import com.thermo.decoder.RoutePlanner
import com.thermo.decoder.codec.VideoCodecProbe
import com.thermo.decoder.container.ContainerProbe
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * :decoder 的 Hilt 出口。
 *
 * 只提供两样东西：容器嗅探器与解码能力探测器。
 * 没有提供 [RoutePlanner] —— 它构造上是 @Inject，Dagger 自己会建。
 *
 * 依赖方向：:decoder -> :core-player（为了 QualityProfile），
 * :decoder -> :data（为了加密空间的 scheme 分派）。两条都不成环。
 */
@Module
@InstallIn(SingletonComponent::class)
object DecoderModule {

    /**
     * :decoder 需要一个 [DataSource.Factory] 来嗅探文件头（本地文件也要读 4KB）。
     *
     * 底层用 media3 的 [DefaultDataSource]：嗅探只读 4096 字节、只读一次，
     * 走缓存层反而会把文件头塞进 SimpleCache 白白占空间，
     * 所以这里**刻意不用** :data 的缓存版工厂。
     *
     * 但外面必须包一层 [SchemeRoutingDataSourceFactory]：
     * 加密空间用的是自定义 scheme，DefaultDataSource 不认它。
     * 不包的话，加密空间的视频会在预检这一步拿到未知容器，
     * 用户看到的是一句莫名其妙的提示，而真实原因只是数据源不认识那个前缀。
     */
    @Provides
    @Singleton
    @ProbeDataSource
    fun provideProbeDataSourceFactory(
        @ApplicationContext context: Context,
        vaultStore: VaultStore,
    ): DataSource.Factory =
        SchemeRoutingDataSourceFactory(DefaultDataSource.Factory(context), vaultStore)

    @Provides
    @Singleton
    fun provideContainerProbe(
        @ProbeDataSource factory: DataSource.Factory,
    ): ContainerProbe = ContainerProbe(factory)

    @Provides
    @Singleton
    fun provideVideoCodecProbe(
        @ApplicationContext context: Context,
    ): VideoCodecProbe = VideoCodecProbe(context)

    @Provides
    @Singleton
    fun provideRoutePlanner(
        containerProbe: ContainerProbe,
        videoCodecProbe: VideoCodecProbe,
    ): RoutePlanner = RoutePlanner(containerProbe, videoCodecProbe)
}