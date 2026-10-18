# media3 的 DefaultRenderersFactory 用 Class.forName 反射加载扩展渲染器，
# 而我们的 :ffmpeg 模块正好就是那个扩展。这几条 keep 必须存在，
# 否则 R8 会把 FfmpegAudioRenderer / FfmpegVideoRenderer 当无用类裁掉，
# 表现为：调试包一切正常，release 包一遇到软解需求的片源就报
# "Extension renderer not found"。
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class com.thermo.ffmpeg.** { *; }
-keepclassmembers class * extends androidx.media3.exoplayer.Renderer {
    <init>(...);
}