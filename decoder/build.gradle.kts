plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// :decoder 的职责边界：
//   有的  -> "这段视频能不能硬解" —— 在**真正起解码器之前**给出答案
//   有的  -> "这个容器 Media3 认识吗" —— 尤其是 AVI 这个缺口
//   有的  -> 把上面两条 + 当前温度档翻译成一条 DecodeRoute
//   没有的-> 不自己建 ExoPlayer、不自己解容器、不自己写解码循环
//
// 它依赖 :core-player 只为了两件事：QualityProfile（画质约束的数据类）
// 和 PlaybackScenario（本地/网络/直播的判定）。反过来 :core-player 不依赖它。
android {
    namespace = "com.thermo.decoder"
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
        disable += "NewApi"
        // 这里用的全是 Media3 标了 @UnstableApi 的探测 API
        // （MediaCodecVideoRenderer.supportsFormat / MediaCodecUtil），
        // 它们不在 kotlinc 的 opt-in 范围内，只是 lint 检查。
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
    // QualityProfile 出现在 :decoder 的公开签名里（RoutePlanner.decide 的返回），
    // 所以用 api 而不是 implementation。
    api(project(":core-player"))

    // 新增：为了加密空间（vault://）能通过预检。
    // ContainerProbe 读文件头用的是这个模块自己的 DataSource，
    // 自定义 scheme 必须在这里也认得，否则加密空间的视频会走到
    // "预检拿不到容器" 的分支。:data 不依赖 :decoder，无环。
    implementation(project(":data"))

    implementation(libs.media3.decoder)
    implementation(libs.media3.extractor)
    implementation(libs.media3.datasource)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}