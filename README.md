本项目由Deepseek V4Pro和V4.1Flash开发
# VideosFree

一个把「少发热」当作第一设计目标的 Android 视频播放器。

硬解优先，只有硬解真的不支持时才落到 FFmpeg 软解；软解路径里刻意不编进
H.264 / H.265 / VP9 / AV1 的解码器 —— 从二进制层面堵死「4K HEVC 掉进 CPU 软解」这条路。

- 目标设备：红米 K80 Pro / 骁龙 8 Elite / HyperOS 4（按 Android 17 对待）
- 技术栈：Kotlin + Jetpack Compose + 协程 Flow + Hilt + Media3 ExoPlayer
- 最低系统：Android 7.0（API 24），targetSdk 35

## 功能

**播放**
- 容器：MP4 / MKV / AVI / MOV / FLV / TS
- 视频：H.264 / H.265 / VP9 / AV1（优先 MediaCodec 硬解）
- 音频：AAC / Opus / FLAC / Vorbis / MP3
- 协议：HTTP / HTTPS / HLS / DASH / 本地文件 / content URI
- 音轨、字幕轨切换，倍速播放（0.5x ~ 3x）

**手势**
- 单击唤出/隐藏控件，双击播放暂停
- 左右滑调进度（松手才 seek，拖动过程只更新 UI）
- 左半屏上下滑调亮度，右半屏上下滑调音量
- 长按全屏加速（倍速可在设置里调，1.5x ~ 5.0x）

**媒体库**
- 本地视频扫描（MediaStore，自动跟随媒体库变化）
- 列表 / 平铺两种显示方式
- 缩略图开关，缩略图抽取时长 10% 处的一帧（避开片头黑帧）
- 多选：连播 / 批量删除

**加密空间**
- AES-256-CTR 加密存储，密钥由 AndroidKeyStore 包裹
- 导入即「搬移」：源文件加密后删除，删不掉则改名 `.1`，从相册与主界面消失
- 支持多选导入
- 指纹 / 锁屏密码解锁

**下载**
- 自研多线程分片下载器，1 ~ 32 线程可调（默认 8）
- 服务端支持 Range 时分片并行；不支持时自动回退顺序下载
- 进度持久化到 SQLite，断点可续

**低发热**
- SurfaceView 直渲，不做每帧 CPU 拷贝
- 按场景区分 LoadControl 缓冲（本地小缓冲 / 网络中等 / 直播小缓冲）
- 帧率匹配，不做无谓插帧
- 三级温控降级：降预加载 → 限码率/分辨率 + 关高刷 → 仅音频或最低画质
- 退到后台默认立即暂停；可开启「视频后台播放」，并支持后台仅音频时释放视频 Surface

## 模块划分

| 模块 | 职责 | 依赖 |
|---|---|---|
| `:core-player` | Media3 装配、Player 抽象、渲染器与解码器选择 | 无业务模块 |
| `:thermal` | 温度监听，只对外吐 `Flow<ThermalLevel>` | 无 |
| `:ffmpeg` | CMake + JNI 软解兜底 | 无 |
| `:decoder` | 硬解能力探测与硬/软路由决策 | `:core-player` |
| `:data` | 数据源、缓存、本地扫描、加密空间、下载器 | `:core-player` |
| `:ui` | Compose 播放页、手势、控件、ViewModel | `:core-player` `:data` `:thermal` |
| `:app` | 装配与策略编排（含 `ThermalPolicyCoordinator`） | 全部 |

三级降级策略刻意放在 `:app` 而不是 `:thermal`：`:thermal` 只负责回答「现在多热」，
「热了该做什么」是产品决策，放在壳层才能单独测试、单独改。

## 构建

### 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |

### 步骤

1. 在项目根目录新建 `local.properties`，写入本机 SDK 路径：

```
sdk.dir=/path/to/AndroidSdk
```

Windows 上要转义反斜杠：

```
sdk.dir=D\:\\AndroidSdk
```

2. 编译 debug 包：

```bash
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

> 仓库里**没有** FFmpeg 预编译静态库（31 MB）。缺了它项目仍然能编译通过 ——
> `:ffmpeg` 会自动跳过 native 构建，`FfmpegLibrary.isAvailable()` 返回 false，
> 播放器退回「纯 MediaCodec + 平台软解」路径。想启用软解见下一节。

### 启用 FFmpeg 软解

二选一。

**A. 自己交叉编译**（aarch64，单核约 8 ~ 15 分钟）

1. 下载 [FFmpeg 7.1 源码](https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz)，解压到 `_ffsrc/ffmpeg-7.1/`
2. 在带 bash 与 NDK r27 的环境（Windows 可用 Git Bash）执行：

```bash
bash ffmpeg/build_ffmpeg.sh
```

3. 产物会出现在 `ffmpeg/src/main/cpp/prebuilt/arm64-v8a/{lib,include}`

**B. 用 Release 附件**

下载 `prebuilt-arm64-v8a.zip`，解压到 `ffmpeg/src/main/cpp/prebuilt/` 下，
确保存在 `ffmpeg/src/main/cpp/prebuilt/arm64-v8a/lib/libavcodec.a`。

只想暂时关掉 FFmpeg：把 `gradle.properties` 里的 `thermo.enableFfmpeg` 改成 `false`。

### 只出 arm64-v8a

ABI 裁剪统一在 `:app` 的 `packaging.jniLibs.excludes` 里做。
不在 `:ffmpeg` 单独限，是因为 Media3 扩展的 AAR 自带多个 ABI，只在一个模块裁不干净。

## 目录结构

```
.
├── app/                 # 主壳：Activity、路由、前台服务、温控策略编排
├── core-player/         # Media3 装配、Player 接口
├── decoder/             # 硬解探测、硬/软路由决策
├── ffmpeg/              # CMake + JNI 软解兜底
│   ├── build_ffmpeg.sh  # FFmpeg 交叉编译脚本
│   └── src/main/cpp/    # CMakeLists.txt、thermo_ffmpeg.c
├── data/                # 数据源、缓存、扫描、加密空间、下载器
├── thermal/             # 温度监听
├── ui/                  # Compose 播放页、手势、控件
├── docs/                # 权限、后台限制、温控调试笔记
└── gradle/libs.versions.toml
```

## 第三方组件

| 组件 | 版本 | 许可证 |
|---|---|---|
| AndroidX Media3 | 1.9.4 | Apache-2.0 |
| Jetpack Compose | BOM 2024.12.01 | Apache-2.0 |
| Hilt / Dagger | 2.52 | Apache-2.0 |
| Kotlin Coroutines | 1.9.0 | Apache-2.0 |
| FFmpeg | 7.1 | LGPL-2.1-or-later |

FFmpeg 由本仓库的 `ffmpeg/build_ffmpeg.sh` 交叉编译，构建参数中**没有**
`--enable-gpl`、`--enable-nonfree`、`--enable-version3`，
因此产物是 LGPL-2.1-or-later，不含任何 GPL-only 组件。

未编入的软解器：H.264、H.265、VP9、AV1（这是低发热设计的一部分，不是遗漏）。

## 许可证

[GNU General Public License v3.0](LICENSE)
