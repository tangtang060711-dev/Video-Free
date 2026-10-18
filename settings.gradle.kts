// ============================================================================
//  ThermoPlayer — 模块声明
//  仓库在 settings 里集中声明；各模块内禁止再写 repositories
//  （FAIL_ON_PROJECT_REPOS 会让违规模块直接构建失败，避免依赖来源漂移）
// ============================================================================
pluginManagement {
    repositories {
        // ---- 国内镜像优先（本机实测 services.gradle.org / dl.google.com 不可达）----
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ---- 国内镜像优先 ----
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        // 自编的 media3-decoder-ffmpeg AAR 默认走 files(...) 直接依赖，
        // 见 ffmpeg/build.gradle.kts；若要改成坐标引用再放开下面这行
        // flatDir { dirs("${rootDir}/ffmpeg/libs") }
    }
}

rootProject.name = "ThermoPlayer"

// 书写顺序 = 依赖方向，方便一眼看出有没有出现反向依赖
include(":core-player")   // 基座：Media3 装配 + Player 抽象，不依赖任何业务模块
include(":thermal")       // 纯传感器：只吐温度等级，不反向依赖任何模块
include(":ffmpeg")        // 纯 JNI：无 Kotlin 层依赖
include(":decoder")       // -> :core-player  探测与硬/软决策
include(":data")          // -> :core-player  数据源/缓存/扫描/进度
include(":ui")            // -> :core-player, :data, :thermal
include(":app")           // -> 全部，只做装配与策略编排
