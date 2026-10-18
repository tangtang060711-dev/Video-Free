plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// :data 的职责边界：
//   有的  -> DataSource.Factory（HTTP 连接策略 + 磁盘缓存开关）
//   有的  -> 本地媒体库扫描（MediaStore）
//   有的  -> 播放进度持久化（裸 SQLiteOpenHelper，不引 Room）
//   有的  -> 用户偏好（省电模式）
//   没有的-> 一行播放器代码、一行温度策略、一行 Compose
//
// 注意：:data **不依赖** :core-player。它只提供一个符合 media3
// androidx.media3.datasource.DataSource.Factory 的绑定，:core-player 通过
// Hilt 在 :app 组装时拿到它。这样 :data 可以脱开播放器单独测试。
android {
    namespace = "com.thermo.data"
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
        // 大量 API 分级（WRITE_EXTERNAL_STORAGE vs MANAGE_EXTERNAL_STORAGE、
        // MediaStore 在 API 29 的分区存储切换）都在这里集中判断，NewApi 会逐条误报。
        disable += "NewApi"
        // Media3 的 UnstableApi 只是 lint 检查，不影响 kotlinc。
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
    // api：DataSource.Factory 会出现在 :data 的公开签名里（ThermoDataSourceFactory.createDataSource），
    // 上层编译时需要这个类型可见。
    api(libs.media3.datasource)

    // SimpleCache 需要 StandaloneDatabaseProvider（在 media3-database 里）。
    // 这是 media3 自带的、基于 android.database 的实现，不是 Room —— 零额外依赖。
    implementation(libs.media3.database)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}