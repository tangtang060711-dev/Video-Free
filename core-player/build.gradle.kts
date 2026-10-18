plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// :core-player 是"播放内核的封装层"，它的职责边界要卡死：
//   有的  -> ExoPlayer 的全部装配（RenderersFactory / LoadControl / TrackSelector / MediaSourceFactory）
//   有的  -> 一个比 media3 Player 窄、比它更好用的 ThermoPlayer 接口
//   没有的-> 一行温度策略、一行 UI、一行文件扫描
// 这样 :ui 换皮、:thermal 换算法都不会碰到解码配置。
android {
    namespace = "com.thermo.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        // 大量 API 分级判断（API 29 的 ThermalStatus、API 30 的 setFrameRate）
        // 都在这里集中处理，NewApi 会逐条误报，直接关掉。
        disable += "NewApi"
        // Media3 的 UnstableApi 是 **lint** 检查（UnsafeOptInUsageError），不是 kotlinc 的
        // opt-in：它只是个普通注解，代码能编过，lint 才会报。
        // 我们大量使用 AnalyticsListener / DefaultLoadControl.Builder 这些标记为
        // unstable 但实际早已稳定的 API，这条规则在这里没有信息量，关掉。
        disable += "UnsafeOptInUsageError"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api 而非 implementation：:ui 需要拿到 androidx.media3.common.Player 去绑定 PlayerView，
    // 也需要 media3-ui 里的 PlayerView 本身。把它们放进 api，:ui 就不必再抄一遍版本号。
    api(libs.media3.exoplayer)
    api(libs.media3.ui)

    // 下面这些是"内核内部实现细节"，不该漏给上层
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.decoder)
    implementation(libs.media3.datasource)
    implementation(libs.media3.extractor)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}