# 云屿投屏

![云屿投屏手绘云朵电视横幅](design/handdrawn/tv-banner-master.png)

云屿投屏是安装在 Android 电视上的 AirPlay / DLNA 接收端，用于接收 iPhone 屏幕镜像，以及视频 App 通过兼容投屏协议发送的影片。界面面向客厅和长辈使用：大字引导、遥控器操作、打开后自动接收。

当前版本为 **0.5.0-preview.1 家庭试用版**，支持 Android 8.0 及以上的 ARM32 / ARM64 设备。项目围绕国行索尼 85X90 系列使用场景开发，其他电视的兼容性以实际运行结果为准。

[下载 0.5.0 APK](https://github.com/NeverOvO/yunyu-cast/releases/download/v0.5.0-preview.1/yunyu-cast-0.5.0-preview.1.apk) · [发布说明与附件](https://github.com/NeverOvO/yunyu-cast/releases/tag/v0.5.0-preview.1) · [更新记录](CHANGELOG.md) · [反馈问题](https://github.com/NeverOvO/yunyu-cast/issues)

## 安装和投屏

1. 从本仓库 Release 下载 APK，复制到 U 盘，在电视文件管理器中打开安装。按电视提示允许该安装来源，也可通过已启用的 ADB 安装。
2. 打开“云屿投屏”，授予系统要求的必要权限。手机和电视连接同一个路由器的局域网，电视可以使用有线网络。
3. 在手机上选择“云屿投屏”：
   - iPhone 整屏镜像：控制中心 → 屏幕镜像 → 云屿投屏。
   - 视频 App：播放影片 → TV / 投屏或 AirPlay 按钮 → 云屿投屏。

如果 iPhone 的镜像控件已经显示 Mac 名称，先点开控件，再从设备列表选择电视。视频 App 需要支持 DLNA 或 AirPlay。

应用无需手动开始接收，也没有“暂停接收”开关。手机停止投屏后，电视等待下一次连接；遥控器返回键结束本次投屏并退出应用界面，接收服务继续运行。连接帮助等弹窗会先关闭自身。

在“画面设置”中，“投屏时自动打开画面”若显示“去开启”，请按电视系统页面允许显示在其他应用上层。该权限用于退出界面后，在下一次投屏时自动显示画面。后台接收仍以电视开机、网络可用且系统允许服务运行为前提，强制停止应用或电视断电会中断接收。

同一发布签名的 0.2.0、0.3.0、0.4.0、0.4.1 可以直接覆盖安装。最初的 0.1.0 调试签名版本需要先卸载；自行构建的调试包也不能覆盖本项目的 Release 签名包。

## 在电视上更新

0.4.1 及以前没有应用内更新功能，需要先手动覆盖安装 0.5.0 一次。之后可在首页或“画面设置”点“检查更新”。发现新版后，点“下载并安装”，电视会在后台下载并显示进度；可选择“稍后安装”或“取消下载”，退出后重新打开可恢复下载进度。

下载完成时，若仍在应用页面且没有投屏，会打开系统安装流程。首次更新需要允许“云屿投屏”作为安装来源，返回后继续；最终安装仍需在电视系统页面确认。投屏期间不会弹出更新窗口或自动打开安装。

应用也会在前台空闲、距离上次检查至少 12 小时时自动查询。更新来自本仓库公开的 GitHub Releases，包含预览版和正式版，不需要账号或 token。网络失败会显示重试提示，不会误报“已经是最新版”，也不影响当前版本投屏。更新完成后会尝试恢复接收；电视系统阻止后台启动时，重新打开应用即可。

操作说明与维护者发布规则见[应用内更新](docs/UPDATES.md)。

## 功能

- AirPlay 屏幕镜像、镜像声音及音视频接收。
- DLNA MediaRenderer 发现、播放、暂停、停止、进度跳转和音量控制。
- 1080p、2K、4K 镜像目标，以及 30 / 60 帧选项。
- 自动接收、后台运行和开机接收；遥控返回只结束当前投屏。
- 中文连接引导、画面设置、连接帮助、协议状态和日志。
- 应用内检查更新、后台下载、进度恢复和系统确认安装。
- 手绘云朵电视图标、电视桌面横幅和应用内标记。

## 画质与兼容性

首次安装默认请求 4K / 60 帧，可在“画面设置”中调整：

| 镜像目标 | 尺寸 | 帧率选项 |
| --- | --- | --- |
| 1080p | 1920 × 1080 | 30 / 60 帧 |
| 2K | 2560 × 1440 | 30 / 60 帧 |
| 4K | 3840 × 2160 | 30 / 60 帧 |

修改后，在手机上断开并重新投屏。出现卡顿时，可改用 4K / 30 帧或 1080p / 60 帧。

这些设置是接收端向发送端声明的协商目标。实际分辨率、帧率和流畅度受手机、电视解码器与网络共同影响，设置为 4K60 不代表实际收到并稳定显示原生 4K60。视频 App 直接投送影片时，清晰度取决于片源和 App 提供的视频流。

DLNA 支持可直接访问的 HTTP / HTTPS 视频地址。需要登录授权、DRM 保护、平台自有投屏协议或设备白名单的影片，可能无法播放或发现设备。本项目不支持 DRM 受保护影片，不包含 Miracast、Google Cast 或各视频平台自有接收协议，也不提供脱离局域网的点对点投屏。

早期版本已收到索尼电视可用反馈。0.5.0 已完成 Release 构建与原发布证书签名，本次没有新增或运行测试、Lint、模拟器或真机检查，应用内更新尚未进行电视实机测试。0.4.0 的 13 项纯逻辑回归测试属于此前版本，范围见[代码审计记录](docs/audit-20261025.md)。

## 找不到设备或连接失败

1. 打开首页“连接帮助”，查看 AirPlay、DLNA 状态和电视 IP；没有局域网地址时先连接电视网络。
2. 确认手机和电视在同一局域网，未被访客网络、设备隔离或 VPN 隔开。
3. 点“重新查找”，然后重新打开手机的屏幕镜像或视频 App 投屏列表。
4. 仍有问题时，在 [Issues](https://github.com/NeverOvO/yunyu-cast/issues) 提供电视完整型号、Android 版本、手机型号与系统版本、投屏 App、操作步骤和相关日志。公开日志前请遮盖设备名称、IP 及视频地址中的个人信息。

“接收服务运行中”表示服务已启动，不等于手机已经发现电视。Android 模拟器的内部网络也不能代替真实电视局域网测试。

## 从源码构建

需要 Git、JDK 17 及以上、Android SDK 37、NDK 27.0.12077973、CMake 3.22.1，以及本机可用的 Make、Perl、Python 3 和 patch。Gradle Wrapper 会下载项目指定的 Gradle 版本；首次构建还需要下载 Android 依赖和 OpenSSL 源码。

```sh
git clone --recurse-submodules https://github.com/NeverOvO/yunyu-cast.git
cd yunyu-cast
```

在项目根目录创建本机 `local.properties`，写入 Android SDK 路径：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

生成可安装的调试包：

```sh
./gradlew :app:assembleDebug
```

输出为 `app/build/outputs/apk/debug/app-debug.apk`。生成 Release 包：

```sh
./gradlew :app:assembleRelease
```

未配置发布证书时，输出 `app/build/outputs/apk/release/app-release-unsigned.apk`，需要自行签名后安装。仓库不提供发布私钥。项目支持在本机 `local.properties` 中配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，这些字段和证书文件不得提交到仓库。

单独运行已有的 JVM 回归测试：

```sh
./gradlew :app:testDebugUnitTest
```

应用包名为 `com.yunyulai.tvcast`。APK 同时包含 `armeabi-v7a` 和 `arm64-v8a`，不包含 x86 模拟器构建。

Release 另附包含原生子模块和 OpenSSL 原始包的完整源码归档；解压构建方式见[完整源码构建说明](docs/SOURCE_BUILD.md)。GitHub 自动生成的源码 ZIP 不包含子模块。

仓库 Actions 仅提供手动触发的未签名构建，不会自动使用发布证书或上传 Release。

## 源码、素材与许可

本项目基于 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) 的 v0.0.36，起始提交为 [`a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea`](https://github.com/jqssun/android-airplay-server/commit/a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea)，应用代码继续按 [GNU GPL v3](LICENSE) 发布。上游原始说明保存在 [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md)，其中的商店链接和包名属于上游项目。

AirPlay / RAOP 底层实现来自 [UxPlay](https://github.com/FDH2/UxPlay)。项目还使用 libplist、OpenSSL、FFmpeg、Oboe、AndroidX / Media3 等组件，保留各组件原有许可与版权。感谢上游项目及贡献者，也感谢 Next Player 对播放器实现的贡献。

- [使用说明](云屿投屏-使用说明.md)
- [应用内更新与发布规则](docs/UPDATES.md)
- [参与贡献](CONTRIBUTING.md)
- [来源与修改声明](NOTICE.md)
- [第三方组件与许可](THIRD_PARTY_NOTICES.md)
- [安全问题反馈](SECURITY.md)
- [界面设计说明](docs/design.md)
- [手绘素材方案、完整提示词和原图](design/handdrawn/)

手绘素材由内置 imagegen 生成。本项目与 Apple、Sony 无隶属关系，也不是其官方或认证接收端。
