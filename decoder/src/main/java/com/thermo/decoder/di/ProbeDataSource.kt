package com.thermo.decoder.di

import javax.inject.Qualifier

/**
 * 标在"只给容器嗅探用"的裸数据源上。
 *
 * ## 为什么必须有这个限定符
 * 全工程里 `androidx.media3.datasource.DataSource.Factory` 有**两个合理但不同**的实现：
 *
 *  * `:data` 的 `ThermoDataSourceFactory` —— 带 SimpleCache 的读写缓存工厂，播放器用它；
 *  * `:decoder` 这里的 `DefaultDataSource.Factory` —— 不带缓存的裸工厂，只读 4096 字节文件头。
 *
 * 不加限定符时 Dagger 会直接报：
 * ```
 * [Dagger/DuplicateBindings] androidx.media3.datasource.DataSource.Factory is bound multiple times:
 *   @Provides @Singleton ... com.thermo.data.di.DataModule.provideDataSourceFactory(...)
 *   @Provides @Singleton ... com.thermo.decoder.di.DecoderModule.provideProbeDataSourceFactory(...)
 * ```
 * 而且这个错误**只在 :app 的 hiltJavaCompileDebug 阶段才爆** ——
 * 各模块单独编译（:data / :decoder / :core-player）全都是成功的，
 * 因为 Dagger 的图是提升到 :app 里才闭合的。这是本工程踩过的第一个真正的装配期错误。
 *
 * ## 为什么嗅探不能复用带缓存的工厂
 * 那会把每个被嗅探文件的前 4096 字节写进 SimpleCache 索引，
 * 用户浏览一遍目录就往 flash 里塞几千条无用索引 —— 白占空间、白耗电。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProbeDataSource