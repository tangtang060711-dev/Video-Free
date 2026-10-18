// ============================================================================
//  :app —— 主壳：Hilt 装配 + 温度降级编排 + 前台 Service + 打包
//
//  这个模块是**唯一**知道"所有模块都存在"的地方：
//    :core-player  Player 抽象 / Media3 配置
//    :decoder      容器与编码探测、硬软切换决策
//    :data         数据源与缓存、本地扫描、进度、设置
//    :thermal      温度读取（不依赖任何业务模块）
//    :ui           Compose 播放页
//    :ffmpeg       JNI 软解（可选，见下面的 hasFfmpeg）
//  这样做的好处：降级策略（谁该在多少度降什么）只在这一个文件里，
//  不需要在五个模块之间来回看。
// ============================================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// :ffmpeg 是否参与本次构建。
// 它需要先交叉编译出 libffmpeg_thermo.so，编译一次要十几分钟，
// 开发期不该每次都付这个代价。settings.gradle.kts 里也是同一个条件，
// 所以这里可以直接用 findProject 判断，两处不会不一致。
val hasFfmpeg = findProject(":ffmpeg") != null

android {
    namespace = "com.thermo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thermo.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.4"

        vectorDrawables.useSupportLibrary = true

        // 只出 arm64-v8a。
        // 理由：骁龙 8 Elite 是纯 64 位，armeabi-v7a/x86/x86_64 三份只会让 APK 大 3 倍；
        // 而且软解库（FFmpeg）每个 ABI 要单独编一遍，多一个 ABI 就多十几分钟构建。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 侧载自用：先用 debug 签名，免得装不上要卸载重装（换签名必须卸载）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // ABI 裁剪必须在 :app 统一做。
            // 只在 :ffmpeg 里限定 abiFilters 是不够的 —— Media3 的 AAR、
            // 以及任何带 .so 的传递依赖都会把 x86/x86_64/armeabi-v7a 塞进来。
            excludes += setOf(
                "lib/x86/**",
                "lib/x86_64/**",
                "lib/armeabi-v7a/**",
                "lib/armeabi/**",
                "lib/mips/**",
                "lib/mips64/**",
            )
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin",
                "**/*.proto",
            )
        }
    }

    lint {
        abortOnError = false
        disable += "NewApi"
        // demo/自用工程，不追 lint 报告
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-player"))
    implementation(project(":decoder"))
    implementation(project(":data"))
    implementation(project(":thermal"))
    implementation(project(":ui"))
    if (hasFfmpeg) {
        // 必须是 implementation（而不是 runtimeOnly），原因见 :ffmpeg 的说明：
        // Media3 用 Class.forName 反射加载 FfmpegAudioRenderer，
        // 反射要求这个类在**同一个 classloader** 里 —— 打包进 APK 才满足。
        implementation(project(":ffmpeg"))
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)      // ProcessLifecycleOwner：判断"App 是否在前台"
    implementation(libs.androidx.lifecycle.service)      // LifecycleService：前台播放服务
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
