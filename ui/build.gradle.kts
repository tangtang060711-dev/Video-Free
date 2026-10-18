// ============================================================================
//  :ui —— Compose 播放页 / 手势 / 字幕 / 音轨 / 画质
//
//  分层说明（刻意保留的"越层"依赖）：
//    :ui -> :core-player  播放抽象、PlayerSnapshot、事件流
//    :ui -> :thermal      温度等级，用于温度徽标与"省电模式"开关
//    :ui -> :decoder      打开前的容器/编码预检（要能立刻告诉用户"这条 AVI 播不了"）
//    :ui -> :data         播放进度落盘、设置项
//  理想做法是让 :ui 只依赖一个接口、由 :app 实现（端口/适配器）。
//  这里不做那层抽象的理由：本工程是单一 App，四个依赖都已在 :app 里装配，
//  再插一层接口只会让"温度降级要通知 UI"这条链路多两次跳转，
//  而这条链路恰恰是最需要一眼看清的。改动时如果 :ui 要复用给别的 App，
//  再把 RoutePlanner / PlaybackPositionStore 换成接口即可。
// ============================================================================
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)   // Kotlin 2.0 起 Compose 编译器是独立插件
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.thermo.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        disable += "NewApi"
        // Media3 的 @UnstableApi 是 lint 检查，不是 kotlinc 的 opt-in 注解，
        // 代码能编过、只有 lint 会报，所以这里关掉。
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
    api(project(":core-player"))
    implementation(project(":thermal"))
    implementation(project(":decoder"))
    implementation(project(":data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui.tooling.preview)
//    debugImplementation(libs.androidx.compose.ui.tooling)   // 需要 tooling 才开，平时不引

    api(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}