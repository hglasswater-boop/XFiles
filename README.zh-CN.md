<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**面向本地存储、NAS 媒体与 Google TV 的开源 Android 文件管理器。**

> **手机双栏，电视遥控优先。SMB2/3 流式播放、视频故事板、安装包支持，无广告、无遥测。**

[![Release](https://img.shields.io/github/v/release/hglasswater-boop/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/hglasswater-boop/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#技术栈)
[![Network](https://img.shields.io/badge/network-SMB2%2F3-informational)](#隐私)

[English](README.md) · [日本語](README.ja.md) · **简体中文**

<img src="docs/assets/dual-pane.png" width="360" alt="XFiles 双栏文件浏览器">

</div>

---

## 这个 fork 的重点

XFiles 保留了上游项目类似 X-plore 的树形操作方式，并进一步针对 **NAS 与大量视频文件** 做了扩展。保存的 SMB 共享会直接出现在普通文件树中，远程视频可以先用故事板查看内容，再直接从 NAS 流式播放。手机端还可以把同一媒体交给 Chromecast，而不需要先完整下载到本机。

手机端与 **Google TV** 端采用独立的产品形态：手机端是双栏触控浏览器，电视端是单栏、遥控器优先的界面。归档浏览、安装包、加密设置备份、容量信息、媒体工具和常用文件操作都保留在同一套 XFiles 中。

## 主要功能

### NAS 优先的 SMB2 / SMB3

- 已保存的 SMB 服务器直接显示在普通文件树中。
- 默认优先使用内置 **Rust SMB 引擎**，并保留 SMBJ 兼容回退。
- 可直接在 SMB 上浏览、复制、移动、重命名、生成缩略图与故事板、播放视频。
- 同一共享内移动时，条件允许会使用服务器端 rename。
- 针对大型 NAS 视频优化随机读取、预取、滚动缓存和播放优先级。
- 管线化写入和 Android 后台传输机制用于维持长时间 SMB 复制。
- SMB 密码通过 Android Keystore 保护。

### 打开视频前先看内容

点击视频缩略图可打开 **故事板时间线**。XFiles 会逐步提取并缓存视频各处的帧，并优先处理当前可见区域。

- 故事板帧数可设置为 **6 到 120**，步长为 2。
- 最小采样间隔可设置为 **1 到 10 秒**。
- 点击帧可从对应时间开始播放或跳转。
- 长按可打开更细的预览时间线。
- 本地播放器和 Chromecast 控制界面共用同一套故事板。
- 播放器提供双列纵向故事板。
- 可针对单个视频重新生成海报缩略图和故事板缓存。

### 面向浏览场景的视频播放器

- 竖屏时故事板位于视频与控制区之间，横屏时位于视频侧边，保留视频高度。参见[布局说明](docs/VIDEO_PLAYER_LAYOUT.md)。
- 在播放器准备前恢复上次播放位置。
- 左右双击 **-10 / +10 秒**。
- 右侧上下滑动调节媒体音量。
- 显示帧计数并支持逐帧步进。
- 画中画提供 **-5 / +5 秒**操作。
- 对异常音频时间戳做有条件的速率修正，不改变正常媒体流。

### 手机端 Chromecast

手机版可以通过临时 HTTP Range relay 投送本地、SMB 和 provider-backed 媒体。

- 播放 / 暂停、跳转、上一项 / 下一项和播放列表控制。
- Receiver 只保留当前项目，XFiles 本地仍维护原文件夹的逻辑播放列表。
- 合并快速连续 seek，并提供即时控制反馈。
- 复用 SMB 句柄并预热，降低 seek 和切换视频的延迟。
- Cast 控制界面也支持故事板。
- 返回文件列表后仍可通过迷你播放器继续控制。
- 通知支持上一项 / 下一项、±10 秒和播放 / 暂停。

Chromecast 只包含在 **手机版**。Google TV 版用于电视本机浏览和播放，不包含 Cast 控制栈。

### Google TV 专用版本

- 独立包名与 Leanback 启动入口。
- 真正的 **单栏**浏览器，减少 D-pad 焦点歧义。
- 左右键显示操作栏。
- 明确控制上下行导航和焦点恢复。
- 遥控器优先的播放器操作。
- TV 专用自更新流程。

### 文件操作与容量信息

- 手机端双栏树形浏览，归档文件可像文件夹一样打开。
- 多选、复制、移动、删除、重命名、新建文件夹、创建 ZIP、解压。
- 复制 / 移动开始前明确确认目标位置。
- **上移一层**：把直接子文件夹里的内容移到父目录，只删除已经为空的文件夹；支持本地与 SMB 根目录。
- 冲突处理支持 Skip / Overwrite / Keep both。
- 长任务支持后台继续、进度、吞吐量显示和取消。
- 本地存储和 SMB 共享显示容量 / 使用率。
- 文件夹容量可选择关闭、仅本地、或本地 + SMB。
- 支持按文件夹保存排序方式，并可调整显示密度、缩略图大小和上下文菜单顺序。

### 与其他应用协作

- 可作为 Android `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 分享目标。
- 多个视频分享时保留 `video/*` 等有意义的聚合 MIME 类型。
- `PICK_FILES` 模式可把本地或 SMB 文件以临时只读 URI 返回给其他应用，而不泄露 SMB 凭据。
- 可寻址 SMB 输出桥支持需要随机写入、truncate 和提交语义的媒体工具。
- 文件详情中的名称、路径、大小、时间、MIME 和视频元数据可以长按选择并复制。

### 安装包与媒体工具

- 支持安装 `.apk`、`.apks`、`.apkm`、`.xapk` 和原始 `.aab`。
- 使用 Android `PackageInstaller`；AAB 在设备上通过内置 bundletool 相关组件转换。
- XAPK 扩展文件在 Android 存储权限允许的范围内通过正常安装路径处理。
- **重建 MP4 容器**：不重新编码，直接把支持的音视频 sample remux 到新的 MP4；支持本地与 SMB 输入。
- 长时间 remux 复用后台任务、进度通知和取消机制。

### 设置备份与更新

- 设置导入 / 导出使用 **AES-256-GCM** 和 PBKDF2-HMAC-SHA256 进行密码加密。
- 备份包含浏览器设置、收藏、目录排序、文件关联和已保存的 SMB 连接及受保护凭据。
- 手机版与 TV 版都可检查适合自身包名的签名更新。
- 手机设置中将 **最新普通版** 与可并存的 **诊断版** 分开安装，诊断包不会覆盖普通安装。
- 设置页显示自动检查、当前版本 / 构建号、最近检查时间和状态。

## 手机版与 TV 版

| | 手机版 | Google TV |
|---|---|---|
| 包名 | `app.local1st.files` | `app.local1st.files.tv` |
| 浏览器 | 双栏树形 | 单栏、遥控器优先 |
| 主要输入 | 触控 / 手势 | D-pad / 遥控器 |
| Chromecast 控制 | 支持 | 不包含 |
| 故事板 | 浏览器 / 播放器 / Cast | TV UI 支持的播放器 / 浏览功能 |
| 自更新 | 支持 | 支持 |

## 下载

从 [**GitHub Releases**](https://github.com/hglasswater-boop/XFiles/releases) 获取签名 APK。

当前稳定版本线为 `v1.4.3-smb`：

- `XFiles-1.4.3-smb.apk`：普通手机版。
- `XFiles-TV-1.4.3-smb.apk`：Google TV 版。
- Release CI 同时生成手机端 AAB 作为 CI artifact。

Mobile / TV 的应用内更新仅使用 Debug CI 发布的签名 `debug-latest`。Release CI 仅在首次创建版本标签时发布稳定版，同时构建手机 AAB，不再发布 `nightly`。`diagnose/*` 分支仍可发布独立包名的 `diagnostic-latest`。

**从旧 Nightly 版本迁移**：旧版无法自动找到新的更新源。请勿卸载应用，需手动覆盖安装一次已签名的普通版 APK，以保留应用数据。参见[1.4.2 发布说明](docs/releases/1.4.2-smb.md)。


需要 **Android 8.0 / API 26 或更高版本**。

## 基础功能

- 类似 X-plore 的可展开树形导航。
- 支持双指缩放的图片查看器。
- 文本查看 / 编辑与分页 Hex 查看器。
- 音频播放器和 Media3 视频播放器。
- 文件名递归搜索，支持 `*` / `?` 通配符、`.mp4` 扩展名形式以及归档内搜索。
- ZIP / JAR / APK、7z、TAR 系列和 RAR 归档浏览。
- 高性能并行 ZIP 创建 / 解压。
- APK / APKS / APKM / XAPK / AAB 安装器。
- Material 3 Expressive、动态配色和 edge-to-edge UI。
- 多语言界面。

## 隐私

XFiles **没有账号、广告或遥测**。网络权限仅用于用户主动使用的网络功能，主要包括 SMB / NAS、手机端 Chromecast 和更新检查。

保存的 SMB 密码由 Android Keystore 加密保护；导出的设置备份在写入前也会加密。实际权限声明可查看 [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) 和各版本专用 Manifest。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 / UI | Kotlin、Jetpack Compose、Material 3 Expressive |
| Android | minSdk 26、compile/target SDK 37 |
| 架构 | MVVM + StateFlow、手写 DI composition root |
| SMB | 默认 Rust SMB2/3 引擎，SMBJ 兼容回退 |
| 媒体 | Media3 ExoPlayer、Coil 3、手机端 Media3 Cast |
| 持久化 | DataStore Preferences、Android Keystore |
| 归档 | java.util.zip、commons-compress、xz、junrar |
| 安装包 | PackageInstaller、内置 bundletool、ARSCLib |
| 设置备份 | AES-256-GCM、PBKDF2-HMAC-SHA256 |

Rust Android JNI 在普通 APK 组装前会验证 arm64-v8a、armeabi-v7a 与 x86_64。

## 构建

需要 JDK 17+ 与 Android SDK platform 37。

```bash
./gradlew :app:assembleMobileDebug :app:assembleTvDebug
```

输出：

```text
app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
app/build/outputs/apk/tv/debug/app-tv-debug.apk
```

Release CI 构建签名的手机 / TV APK 与手机 AAB。Debug CI 会运行两个版本的单元测试、构建签名 Debug APK、验证包名和 Rust JNI，并在 API 35 Android Emulator 上执行启动与生命周期 smoke test。

## 项目来源

本仓库是基于 [Local1stDotApp/XFiles](https://github.com/Local1stDotApp/XFiles) 的个人 fork。感谢上游作者与贡献者提供基础实现。本 fork 在 NAS / 媒体、Chromecast、Google TV、故事板、更新和外部集成等方面有意与上游产生差异。

## 许可证

[GPL-3.0-only](LICENSE)。发布修改版时，请按许可证要求同时提供对应源代码。
