// 根工程只声明插件版本，一律 apply false，由各模块自行 apply。
// 这里刻意不写 subprojects {} 块：一是 configuration-cache 友好，
// 二是避免隐式地把配置灌给所有子模块导致构建图难排查。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
