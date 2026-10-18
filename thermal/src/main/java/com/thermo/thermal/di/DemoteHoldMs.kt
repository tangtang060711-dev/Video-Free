package com.thermo.thermal.di

import javax.inject.Qualifier

/**
 * "降温确认时长"的注入限定符。
 *
 * 为什么要单独造一个 Qualifier，而不是给构造函数参数写默认值？
 * 因为 Dagger/Hilt 是按 JVM 构造函数签名找绑定的，Kotlin 的默认参数
 * 对它是不可见的——`demoteHoldMs: Long = 60_000L` 在 Dagger 眼里
 * 就是一个必须被提供的 `long`，编译期直接报
 * "Long cannot be provided without an @Provides-annotated method"。
 * 所以只能显式提供，并用限定符把它和别的 Long 区分开。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoteHoldMs