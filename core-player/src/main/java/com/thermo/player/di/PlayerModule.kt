package com.thermo.player.di

import com.thermo.player.Media3ThermoPlayer
import com.thermo.player.ThermoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 把 [ThermoPlayer] 接口绑到 Media3 实现上。
 *
 * ## 为什么是 @Provides 而不是 @Binds
 * [Media3ThermoPlayer] 的构造函数上已经挂了 @Inject，所以 Hilt 本来就能直接造它。
 * 用 @Provides 把接口指过去，等于只多了一个绑定；而 @Binds 要求写成
 * abstract class + 抽象方法，还得另外保住 @Singleton 作用域，
 * 在这个只有一个实现的场景下没有收益。
 *
 * ## 为什么必须是 Singleton
 * 一个进程只该有一个 ExoPlayer 实例：
 *   - 两个 ExoPlayer 会各自持有一路音频输出，Android 只允许一路 AudioTrack 拿到焦点，
 *     另一个会静默变成"无声播放"，还白烧一份解码功耗；
 *   - 解码器实例数是有限的（MediaCodecInfo.getMaxSupportedInstances()），
 *     同时起两个 4K 解码器在 8 Elite 上会直接 configure 失败。
 * 所以这里必须是 Singleton。要做多窗口/画中画，请上层做"切换"而不是"并存"。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideThermoPlayer(impl: Media3ThermoPlayer): ThermoPlayer = impl
}