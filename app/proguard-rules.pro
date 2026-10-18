# ============================================================================
#  R8 规则
#  这个工程有两处反射，必须显式 keep：
#   1) Media3 用 Class.forName 找扩展渲染器（FFmpeg 软解）；
#   2) :thermal 用反射读热区结温（android.os.ThermalService，@hide）。
#  漏掉任何一条的后果都不是崩溃，而是**静默失效**：软解永远不生效、
#  温度永远读不到 —— 这种 bug 在真机上极难定位，所以规则写在最显眼处。
# ============================================================================

# ---- Media3 扩展渲染器（默认渲染工厂靠类名字符串反射加载）----
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class com.thermo.ffmpeg.** { *; }
# Media3 内部还会反射 MediaCodec 的一些方法（例如 setParameters / getInputBuffer）
-keepclassmembers class * extends android.media.MediaCodec {
    <init>(...);
}

# ---- 热区温度反射 ----
-keep class com.thermo.thermal.impl.** { *; }
-keepclassmembers class com.thermo.thermal.impl.** { *; }
-dontwarn android.os.ThermalService
-dontwarn android.os.IThermalService
-dontwarn android.os.IThermalService$Stub
-dontwarn android.os.HardwarePropertiesManager
-dontwarn android.os.IThermalStatusListener

# ---- Media3 ----
-dontwarn androidx.media3.**

# ---- 协程 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- 保留行号，线上排查解码问题全靠它 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile