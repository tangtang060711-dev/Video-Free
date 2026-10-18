package com.thermo.data.di

import androidx.media3.datasource.DataSource
import com.thermo.data.datasource.ThermoDataSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * :data 对外的唯一出口。
 *
 * 只绑定一个类型：`androidx.media3.datasource.DataSource.Factory`。
 * :core-player 的 `Media3ThermoPlayer` 构造里要的就是这个接口 ——
 * 它不知道、也不关心背后是 DefaultHttpDataSource 还是 OkHttp。
 * 想换实现（比如给某个站点加专用 referer），只改 ThermoDataSourceFactory，
 * 播放内核一行都不用动。
 *
 * 用 @Provides 而不是 @Binds：ThermoDataSourceFactory 自己有 @Inject 构造，
 * @Binds 需要写成 abstract class + abstract fun，而这里还可能在构造时做点事，
 * @Provides 更直接。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDataSourceFactory(impl: ThermoDataSourceFactory): DataSource.Factory = impl
}