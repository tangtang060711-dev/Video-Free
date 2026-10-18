plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// :thermal 是全工程依赖最少的模块，这一点是刻意的：
// 它只做"读温度"一件事，不引 media3 / 不引 compose。
// 依赖越少，它作为降级信号的可靠性越高（不会因为播放器崩溃而一起挂掉）。
android {
    namespace = "com.thermo.thermal"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 反射调用 android.os.ThermalService 时用到了 getMethod，
    // Lint 的 NewApi 检查在这里会误报，关掉该条规则。
    lint {
        disable += "NewApi"
        abortOnError = false
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
