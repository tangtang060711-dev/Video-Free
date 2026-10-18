# G. 权限、后台限制、电池优化豁免、发热调试

> 适用：红米 K80 Pro / 骁龙 8 Elite / 澎湃 OS 4.0.0.8（按 Android 17 对待），
> 工程 `D:\Videos\ThermoPlayer`，包名 `com.thermo.player`。
> 本文件是第七部分交付物，全部命令都在本机实测位置（`D:\ADB\platform-tools\adb.exe`）。

---

## G1. 权限清单：为什么是这个集合，不是别的

全部集中声明在 `app\src\main\AndroidManifest.xml`，**其他模块的 manifest 一个权限都不写**
（`:thermal` `:core-player` `:decoder` `:data` `:ui` 的 manifest 都是空 `<manifest />`）。

| 权限 | 为什么需要 | 什么时候申请 |
|---|---|---|
| `INTERNET` | HTTP/HTTPS/HLS/DASH 拉流 | 安装即授予（normal） |
| `ACCESS_NETWORK_STATE` | `DefaultHttpDataSource` 拿不到网络状态时无法区分"断网"与"服务器 500"，错误提示会含糊 | 安装即授予 |
| `WAKE_LOCK` | `ExoPlayer.Builder.setWakeMode(C.WAKE_MODE_LOCAL)` 在**本地文件**播放时用的 `WifiLock`/`WakeLock`。网络流走 `WAKE_MODE_NETWORK` 也在此权限覆盖内 | 安装即授予 |
| `FOREGROUND_SERVICE` | 后台播放必须有前台服务，否则 Android 12+ 直接杀 | 安装即授予 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | **API 34 起强制**：不声明这个，`startForeground` 带 `mediaPlayback` 类型会抛 `SecurityException` | 安装即授予 |
| `POST_NOTIFICATIONS` | **API 33 起通知是运行时权限**。拿不到 = 前台服务通知不可见 = 系统认为"没有前台服务" = 立刻被杀 | 第一次开始播放时申请 |
| `READ_MEDIA_VIDEO` | API 33+ 读本地视频（MediaStore） | 进入本地列表页时申请 |
| `READ_MEDIA_AUDIO` | API 33+ 读本地音频 | 同上 |
| `READ_EXTERNAL_STORAGE` | API ≤ 32 的等价权限。**必须带 `android:maxSdkVersion="32"`** —— 不带的话在 API 33+ 设备上它会变成"申请了一个已被系统废弃的权限"，部分 ROM 会直接拒绝整个权限组 | 同本地列表页 |

### 刻意**不**申请的权限

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` —— Play 商店明确禁止；侧载自用虽可行，但用户手点设置页就够了（见 G3）。真加了这个权限，`dumpsys` 里会被标成可疑应用。
- `READ_MEDIA_IMAGES` —— 本播放器不播图片。
- `MODIFY_AUDIO_SETTINGS` —— Media3 的 `AudioSink` 走 `AudioManager` 公开 API，不需要。
- `SYSTEM_ALERT_WINDOW` —— 悬浮窗播放不在需求内，而且它是隐私敏感权限。

### 运行时申请的正确时机（写进 `MainActivity` / `HomeScreen` 的原则）

`POST_NOTIFICATIONS` **不要在 `onCreate` 就申请**。理由：冷启动第一屏弹权限框，用户还没看到任何内容，
拒绝率极高；而这个权限被拒绝的后果（后台播放被砍）用户当下感受不到。
正确时机是 `onIsPlayingChanged(true)` 第一次触发、且即将切后台之前 —— 或者更简单：
**进本地列表页申请媒体权限时一起申请**（那时用户已经表达了"我要用它"）。

`READ_MEDIA_VIDEO` 同理：不要一启动就申请，进"本地视频"页再申请。

---

## G2. 后台限制：Android 12 → 17 逐版本踩过的坑

### G2.1 前台服务的启动限制（API 31+）
`Context.startForegroundService()` 后，**5 秒内**必须调 `ServiceCompat.startForeground(...)`，
否则抛 `ForegroundServiceDidNotStartInTimeException`。

工程里的做法（`app\src\main\java\com\thermo\app\PlaybackService.kt`）：
`onCreate` 里**同步**调 `startForegroundCompat()`，不等协程、不等网络、不等播放器 ready。
通知内容先用 `thermoPlayer.snapshot.value` 的当前值渲染，之后再更新。

### G2.2 后台启动前台服务的限制（API 31+）
应用进入后台后**不能再启动**前台服务。所以 `PlaybackService.start(context)` 只能在
以下时机调用：Activity 可见时、`onIsPlayingChanged(true)` 时、或从通知动作里。
工程里 `MainActivity` 是在 `snapshot.playbackState != STATE_IDLE` 时启动，
这个判断发生在 `onCreate`（可见）阶段，安全。

### G2.3 `mediaPlayback` 类型的额外要求（API 34+）
API 34 起前台服务类型被"用途绑定"：
- 必须已声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`；
- 启动时应用必须**正在播放媒体或即将播放**（`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`）；
- 系统会在 `onTimeout` 之外检查；`mediaPlayback` 类型本身**没有** 6 小时超时限制
  （有超时的是 `shortService` / `dataSync` / `mediaProjection`）。

### G2.4 通知可见性是前台服务成立的前提（API 33+）
这是最容易踩的一条：**没有 `POST_NOTIFICATIONS`，前台服务通知不显示，
系统会把服务降级成普通后台服务，然后按后台限制杀掉它。**
表现是"息屏放一会儿音乐就停了"，日志里 `ActivityManager: Killing ... (adj 200)`。
`PlaybackService.ensureChannel()` 用了 `NotificationManagerCompat.from(this).areNotificationsEnabled()` 判断，
不 enabled 时会走 `startForegroundCompat` 的兜底分支（仍调用，但 UI 层会提示用户去开）。

### G2.5 后台仅音频（本工程的主动优化，不是系统要求）
`ThermalPolicyCoordinator` 在 `onAppBackground()` 时会调用
`thermoPlayer.releaseVideoSurface()`（条件：有视频轨 + `settings.audioOnlyInBackground` + 正在播放）。

这一条是**单点收益最大**的省电策略：视频解码器（MediaCodec 的 hardware instance）
在后台仍然占着 GPU 的一段常驻带宽，而且它会阻止 SoC 进 deep idle；
摘掉 Surface 之后 ExoPlayer 会走 `onVideoDisabled` 路径释放解码器，
整个视频管线停摆，只剩音频 `AudioTrack`，功耗从 1.5~2.5W 掉到 0.1~0.3W。
---

## G3. 电池优化豁免 & 澎湃 OS 的自启动/后台白名单

### G3.1 标准 Android 路径（AOSP 有、澎湃 OS 也保留）
```
设置 → 应用设置 → 应用管理 → ThermoPlayer → 省电策略 → 无限制
```
对应的 adb 等效命令（**需要用调试机或 root，普通 adb shell 也能跑**）：
```bat
:: 把本应用加入 Doze 白名单（= 设置里的"无限制"）
adb shell dumpsys deviceidle whitelist +com.thermo.player

:: 查看当前白名单
adb shell dumpsys deviceidle whitelist

:: 允许后台运行（appops 层）
adb shell cmd appops set com.thermo.player RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops get com.thermo.player RUN_ANY_IN_BACKGROUND
```

从代码里弹系统设置页（**这是 Play 商店允许的正规做法**，比申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 合规）：
```kotlin
// :app 里如果需要，可加这样一个入口（本工程暂未需要，因为播放时是前台服务）
val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
startActivity(intent)
```
注意这两个 Intent 的区别，很容易用错：
- `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` —— 打开**整个列表**，用户自己找。
- `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `data = "package:com.thermo.player"` —— **直接弹本应用的豁免确认框**，但这个用途需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，属于商店限制项。侧载自用可以用，本工程刻意没用。

### G3.2 澎湃 OS（HyperOS）特有三件套
澎湃 OS 在 AOSP 之上又加了三层，**只做 G3.1 是不够的**：

1. **自启动管理**
   设置 → 应用设置 → 应用管理 → ThermoPlayer → 自启动 → 打开
   （不开的话，从通知栏 `PendingIntent.getService` 拉起的服务可能被拦）

2. **后台弹出界面 / 锁屏显示**
   同上页面里的"其他权限" → 后台弹出界面。
   （影响的是从通知动作恢复 UI 的场景）

3. **神隐模式 / 省电策略**
   设置 → 电池与性能 → 应用省电策略 → ThermoPlayer → 无限制
   对应的 MIUI/HyperOS 专有命令（**不一定在每台机上都存在，试试看**）：
   ```bat
   adb shell dumpsys power | findstr /i "miui hyper os_"
   adb shell "dumpsys activity settings | grep -i 'miui\|hyper'"
   ```
   如果 `dumpsys` 里能看到 `MiuiPowerKeeper` / `PowerKeeper` 字样，说明这台机确实跑着神隐模式。

> 实测建议：**先只做 G3.1，跑一遍 30 分钟息屏播放测试**；
> 如果日志里出现服务被杀，再补 G3.2。三层全开会让系统完全不限制本应用，
> 万一有 bug 就是"后台常驻耗电"，反而是负面影响。
---

## G4. 发热调试 adb 命令大全

> 先把 adb 加到 PATH 或用全路径：`D:\ADB\platform-tools\adb.exe`。
> 下面按"你要回答的问题"分组，每组都标了**看什么数字**。

### G4.0 先确认设备在线
```bat
adb devices -l
adb shell getprop ro.product.model                :: 应为 24117RK2DC (Redmi K80 Pro)
adb shell getprop ro.build.version.release        :: 澎湃 OS 4.0.0.8 对应的 Android 版本
adb shell getprop ro.build.version.sdk
adb shell dumpsys package com.thermo.player | findstr /i "versionName minSdk targetSdk"
```

### G4.1 「现在到底有多热」—— 读温度源
```bat
:: ① 官方 ThermalStatus（本工程 PowerManagerThermalSource 读的就是这个）
adb shell dumpsys thermalservice
::   看 "Thermal Status: 0" 这一行。0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN
::   本工程的映射：NONE/LIGHT→NORMAL  MODERATE→WARM  SEVERE→HOT  CRITICAL 及以上→CRITICAL

:: ② 电池温度（本工程 LegacyBatteryThermalSource 读的就是这个，单位 0.1°C）
adb shell dumpsys battery | findstr /i "temperature"
::   "temperature: 351" = 35.1°C。正常 30~36，>38 开始 WARM，>41 HOT，>44 CRITICAL

:: ③ 热区原始结温（本工程 ThermalZoneSource 反射读的就是这些文件）
adb shell "cat /sys/class/thermal/thermal_zone*/type; echo '-----'; cat /sys/class/thermal/thermal_zone*/temp"
::   输出是两段对齐的表。type 里找 cpu/gpu/skin/battery 字样，temp 单位是 0.001°C
::   例如 cpu-0-0 的 62000 = 62°C。这才是 SoC 结温，比电池温度准得多、早 20~30 秒

:: ④ 只看 SoC 结温，过滤成人类可读（一次性）
adb shell "for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type 2>/dev/null); v=$(cat $z/temp 2>/dev/null); case \"$t\" in *cpu*|*gpu*|*skin*) echo \"$t = $((v/1000)).$((v%1000/100))C\";; esac; done"
```

### G4.2 【最重要】不要等手机真的烫起来 —— 伪造温度来测三级降级
```bat
:: 强制系统 ThermalStatus，直接触发本工程的 WARM / HOT / CRITICAL 三档
adb shell cmd thermalservice override-status 2      :: 2 = MODERATE → 应触发第 1 档（限 1080p + 关高刷）
adb shell cmd thermalservice override-status 3      :: 3 = SEVERE   → 应触发第 2 档（限 720p + 关高刷 + 后台仅音频）
adb shell cmd thermalservice override-status 4      :: 4 = CRITICAL → 应触发第 3 档（480p；若已在软解则摘 Surface 切仅音频）
adb shell cmd thermalservice override-status 0      :: 复位

:: 复位后立刻确认
adb shell dumpsys thermalservice | findstr /i "Thermal Status"
```
**这是整套调试里最有价值的一条命令**：不用把手机捂热 20 分钟，不用真的烧机，
三级降级的每一条动作都能在 3 秒内触发并验证。

同时用另一路日志对着看降级是不是真的生效了：
```bat
adb logcat -c && adb logcat -v time -s ThermoPlayer:V ThermalPolicy:V FfmpegAudioRenderer:V
::   期望看到形如：
::   I ThermalPolicy: 降级 plan tier=1 preload=0.5 qualityCap=1920x1080 followFrameRate=false reason=...
::   I ThermoPlayer: 预加载预算调整请求 scale=0.5（LoadControl 是 build 时固定的，下次重建生效）
::   I ThermoPlayer: 画质上限变更：1920x1080@8000000 ...
```

### G4.3 「到底走的硬解还是软解」
```bat
:: ① 我们自己的日志（AnalyticsListener.onVideoDecoderInitialized 打出来的）
adb logcat -s ThermoPlayer:V | findstr /i "decoder 解码器"
::   c2.qti.*   / OMX.qcom.*        → 高通硬解（期望值）
::   c2.android.* / OMX.google.*     → 平台软解（比 FFmpeg 省电，可接受）
::   OMX.ffmpeg.* / c2.ffmpeg.*     → 我们的 FFmpeg 扩展（只应出现在音频，视频不该出现）

:: ② 系统侧的编解码器实例
adb shell dumpsys media.codec | findstr /i "avc hevc vp9 av01 decoder"
adb shell dumpsys media.codec | findstr /i "name="
::   看 "state" 那一列：running / idle。后台仅音频时视频解码器应该**消失**，只剩音频

:: ③ 确认 FFmpeg 扩展真的被 Media3 加载了
adb logcat -s ThermoPlayer:V | findstr /i "FFmpeg native"
::   FfmpegLibrary 初始化会打 "FFmpeg native 库已加载，版本=..."；没打说明 so 没被加载

:: ④ 直接问 native 库
adb shell run-as com.thermo.player ls -l lib/    :: debug 包可以 run-as
```

### G4.4 「掉帧了吗 / 渲染顺不顺」
```bat
:: 我们自己的掉帧事件（AnalyticsListener.onDroppedVideoFrames）
adb logcat -s ThermoPlayer:V | findstr /i "掉帧"

:: 系统渲染统计（按包名）
adb shell dumpsys gfxinfo com.thermo.player
::   看 "Total frames rendered" / "Janky frames" / "90th percentile"

:: SurfaceFlinger 的刷新率与合成情况
adb shell dumpsys SurfaceFlinger --latency "SurfaceView[com.thermo.player]"
adb shell dumpsys display | findstr /i "refreshRate modeId"
::   看当前 modeId 是不是被我们的 DisplayRefreshRateAdapter 改成了 60Hz
::   期望：播 24fps 片源时不应停在 120Hz。手机上 24fps/120Hz = 5 倍重复，纯浪费

:: 显示模式列表
adb shell dumpsys display | findstr /i "supportedModes"
```

### G4.5 「费了多少电、谁在耗」
```bat
:: 清零统计 → 播 10 分钟 → 看结果
adb shell dumpsys batterystats --reset
:: （播放 10 分钟）
adb shell dumpsys batterystats com.thermo.player
::   关键看这几行：
::   "Estimated power use (mAh)" —— 总耗电
::   "Uid u0aXXX: ... ( cpu=..., sensor=..., gpu=... )" —— 分项
::   对比基准：夸克播同一段 1080p30 应该是本播放器的 1.5~2 倍才算"明显更低发热"

:: 更细粒度的电量历史（可导出后用 Battery Historian 看）
adb shell dumpsys batterystats --charged com.thermo.player > batt.txt

:: 实时功耗（部分 ROM 有，澎湃 OS 一般有）
adb shell dumpsys power | findstr /i "mWakefulness mScreenOn"
adb shell cat /sys/class/power_supply/battery/current_now     :: 单位 µA，负数是放电
adb shell cat /sys/class/power_supply/battery/voltage_now     :: 单位 µV
::   功率 = current_now × voltage_now / 1e12 瓦。例如 450000 × 4000000 / 1e12 = 1.8W
```
### G4.6 「后台播放为什么不继续」
```bat
:: ① 前台服务活着吗
adb shell dumpsys activity services com.thermo.player
::   找 "ServiceRecord{... com.thermo.app/.PlaybackService}"
::   "isForeground=true" 才算真的前台。"foregroundServiceType=0x2" 是 mediaPlayback

:: ② 通知有没有真的发出来
adb shell dumpsys notification --noredact | findstr /i "thermo_playback com.thermo.player"
::   没有这两行 = POST_NOTIFICATIONS 没给 = 前台服务形同虚设

:: ③ 有没有被系统杀
adb logcat -b events | findstr /i "am_kill am_proc_died"
::   出现 "Killing 12345:com.thermo.player/u0aXXX (adj 200)" 就是被杀

:: ④ Doze 状态
adb shell dumpsys deviceidle get deep / get light
adb shell dumpsys deviceidle whitelist | findstr thermo

:: ⑤ 强制进入 Doze 来压测（比真等几小时快）
adb shell dumpsys deviceidle force-idle
:: （播放 5 分钟看会不会停）
adb shell dumpsys deviceidle unforce
```

### G4.7 「用命令行启动播放，绕过 UI」
```bat
:: 外部 URI 唤起（Manifest 里声明了 VIEW intent-filter）
adb shell am start -a android.intent.action.VIEW -d "https://example.com/a.m3u8" -t "application/x-mpegURL" com.thermo.player
adb shell am start -a android.intent.action.VIEW -d "file:///sdcard/Movies/test.mkv" -t "video/x-matroska" com.thermo.player

:: 直接读本地文件路径试播（把测试片推进去）
adb push D:\test\sample_4k_hevc.mp4 /sdcard/Movies/
adb shell am start -a android.intent.action.VIEW -d "file:///sdcard/Movies/sample_4k_hevc.mp4" -t "video/mp4" com.thermo.player

:: 回到列表页（本工程是单 Activity，返回不会停播）
adb shell input keyevent KEYCODE_BACK

:: 强杀重来
adb shell am force-stop com.thermo.player
```

### G4.8 一轮完整的对比测试脚本（可直接抄）
```bat
:: ---- 准备 ----
adb shell dumpsys batterystats --reset
adb shell cmd thermalservice override-status 0
adb logcat -c
adb push D:\test\1080p30_h264.mp4 /sdcard/Movies/

:: ---- 开始：用 4K HEVC 做压力片，最容易拉开差距 ----
adb shell am start -a android.intent.action.VIEW -d "file:///sdcard/Movies/sample_4k_hevc.mp4" -t "video/mp4" com.thermo.player
:: （锁屏，让它以"后台仅音频"跑 30 分钟）
adb shell input keyevent KEYCODE_POWER

:: ---- 收尾：30 分钟后 ----
adb shell dumpsys battery | findstr /i "temperature"
adb shell dumpsys thermalservice | findstr /i "Thermal Status"
adb shell dumpsys batterystats com.thermo.player | findstr /i "Estimated power"
adb logcat -d -s ThermoPlayer:V ThermalPolicy:V > thermo_run.log
```
**同一个片子、同样 30 分钟、同样锁屏，换成夸克再跑一遍**，两份 `batterystats` 的
"Estimated power use (mAh)" 数字就是"明显更低发热"这个需求唯一诚实的验收指标。

---

## G5. 本工程的省电设计 → 验收对照表

| 需求里的策略 | 落在哪个文件 | 怎么用 adb 验证 |
|---|---|---|
| SurfaceView 直渲，不用 TextureView | `ui\...\player\VideoSurface.kt`（内部就是 `PlayerView`，其默认 `surface_type` 是 surface_view） | `adb shell dumpsys SurfaceFlinger \| findstr SurfaceView`；出现 `SurfaceView[com.thermo.player]` 即正确，出现 `TextureView` 就是错的 |
| `EXTENSION_RENDERER_MODE` + `enableDecoderFallback` | `core-player\...\internal\ThermoRenderersFactory.kt` | `adb logcat -s ThermoPlayer:V \| findstr 解码器`，看是否出现 `c2.android.*`（平台软解兜底生效） |
| MediaCodecList 按 profile/level 探测硬解 | `decoder\...\codec\VideoCodecProbe.kt`（走 `MediaCodecVideoRenderer.supportsFormat`，静态方法不真起解码器） | `adb logcat -s ThermalPolicy:V \| findstr preflight` |
| LoadControl 按场景分档 | `core-player\...\internal\LoadControlFactory.kt` | `adb logcat -s ThermoPlayer:V \| findstr 缓冲`（会打出 "1~4s/16MB" 之类） |
| 帧率匹配，不无必要插帧 | `core-player\...\internal\DisplayRefreshRateAdapter.kt` | `adb shell dumpsys display \| findstr refreshRate`，播 24fps 片时不应是 120Hz |
| 省电模式限最高码率/分辨率 | `data\...\settings\ThermoSettings.kt` + `ThermalPolicyCoordinator` | 打开省电开关后 `ThermalPolicy` 应打出 tier>=1 的 plan |
| 生命周期，onPause 停止解码 | `ui\...\PlayerScreen.kt`（`LifecycleEventEffect(ON_PAUSE)`）+ `PlayerViewModel.onPauseForBackground()` | 按 HOME 后 `adb shell dumpsys media.codec \| findstr running` 里的视频解码器应消失 |
| 后台前台 Service + 仅音频释放 Surface | `app\...\PlaybackService.kt` + `ThermalPolicyCoordinator` | `dumpsys activity services com.thermo.player` 有 `isForeground=true`；`dumpsys media.codec` 里视频解码器应消失 |
| 三级温度降级 | `app\...\ThermalPolicyCoordinator.kt` | `adb shell cmd thermalservice override-status 2/3/4` 逐档验证 |

---

## G6. 构建与安装（本机实测命令）

### G6.1 为什么不用 `gradlew` 而是直接调 gradle.bat
本机 `services.gradle.org` 不可达，`gradle wrapper` 任务必失败。
`gradle\wrapper\gradle-wrapper.properties` 里的 `distributionUrl` 已改为腾讯云镜像
且 `validateDistributionUrl=false`，所以 **`gradlew.bat` 也能用**；
但直接调本地发行版更稳（不依赖 wrapper 逻辑）：

```bat
set GRADLE=C:\Users\zerom\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1\bin\gradle.bat
%GRADLE% :app:assembleDebug --console=plain -p D:\Videos\ThermoPlayer
```

### G6.2 FFmpeg native 的构建（**可选**，不跑也能出 APK）
```bat
"C:\Program Files\Git\usr\bin\bash.exe" /d/Videos/ThermoPlayer/ffmpeg/build_ffmpeg.sh
```
- 耗时约 **2~3 分钟**（8 核 `-j8`，实测 configure ~70s + make ~60s）；
- 产物落 `ffmpeg\src\main\cpp\prebuilt\arm64-v8a\{include,lib}`，
  **这个路径必须与 `CMakeLists.txt` 里的 `${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}` 一致**；
- `:ffmpeg` 的 `build.gradle.kts` 会检测 `libavcodec.a` 是否存在：
  **不存在就静默跳过 native 构建**，`FfmpegLibrary.isAvailable == false`，播放器退回纯 MediaCodec 路径。
  这是刻意的可降级设计 —— 不能让十几分钟的 native 编译卡住出包。
- `gradle.properties` 里的开关：`thermo.enableFfmpeg=true` / `thermo.abiFilter=arm64-v8a`。

### G6.3 安装
```bat
adb install -r D:\Videos\ThermoPlayer\app\build\outputs\apk\debug\app-debug.apk
:: release（用 debug 签名，便于覆盖安装）
%GRADLE% :app:assembleRelease --console=plain -p D:\Videos\ThermoPlayer
adb install -r D:\Videos\ThermoPlayer\app\build\outputs\apk\release\app-release.apk
```

### G6.4 两个必须跑一次的一次性配置（澎湃 OS）
```bat
:: 首次安装后，把应用加入 Doze 白名单（= 设置里的"无限制"）
adb shell dumpsys deviceidle whitelist +com.thermo.player
:: 授予通知权限（否则前台服务通知不可见 → 后台被砍）
adb shell pm grant com.thermo.player android.permission.POST_NOTIFICATIONS
:: 授予媒体读取权限
adb shell pm grant com.thermo.player android.permission.READ_MEDIA_VIDEO
adb shell pm grant com.thermo.player android.permission.READ_MEDIA_AUDIO
```
自启动 / 神隐模式见 G3.2，必须手点。