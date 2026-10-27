# 安卓手机投屏能力与扩展范围

云屿投屏已实现 DLNA 视频接收，安卓手机可从支持 DLNA 的视频 App 发起投屏。安卓系统整屏镜像尚未实现；相册视频需要图库或发送 App 提供 DLNA 发送功能，并向电视提供可访问的 HTTP／HTTPS 视频地址。

本次评估日期为 2026-10-27，依据当前代码和官方资料。没有安卓手机实测记录，以下能力不等于特定品牌、机型或 App 已通过兼容性测试。

## 当前支持范围

| 手机上的操作 | 当前接收能力 | 使用条件与限制 |
| --- | --- | --- |
| 视频 App 内的“TV／投屏” | 已实现标准 DLNA 视频接收 | App 实际使用 DLNA，视频地址可由电视访问，编码可由电视解码。平台专有协议、设备白名单、登录授权或 DRM 可能阻止播放。 |
| 相册中发送视频 | 可使用现有 DLNA 视频接收 | 图库或发送 App 需要将手机文件提供为可访问的视频地址。手机本地的 `content://`、`file://` 路径不能直接交给电视。 |
| 相册中发送照片 | 尚未实现独立图片接收与展示 | 当前 DLNA 描述只声明视频和音频格式，没有声明 JPEG、PNG 图片格式。 |
| 手机系统的“无线投屏”、Smart View 等整屏镜像 | 尚未实现通用支持 | 这些入口所用的 Miracast、Google Cast 等协议，与现有 AirPlay／DLNA 接收不同。 |
| 第三方 App 通过 AirPlay 发送 | 有现有 AirPlay 接收路径 | 发送 App 必须实现兼容的 AirPlay 方式；没有对应组合的实测结果。 |

手机和电视需要处于可互通的局域网，双方也必须使用相同协议。同名 Wi-Fi、同一个路由器或同属安卓设备，都不能单独保证投屏互通。当前 DLNA 搜索响应要求手机地址与对应电视网络接口处于同一 IPv4 子网；访客网络、设备隔离等设置会影响发现和播放。

## 安卓视频的使用方式

1. 在电视打开“云屿投屏”，让手机和电视接入同一局域网。
2. 在支持 DLNA 的视频 App 中播放影片，点“TV／投屏”，选择“云屿投屏”。相册视频从图库的投屏功能或支持 DLNA 的发送 App 发起。
3. 如果列表中没有“云屿投屏”，先确认 App 支持 DLNA，再查看电视“连接帮助”中的 DLNA 状态。仅支持 Google Cast 或平台专有协议的入口无法直接使用当前接收端。

安卓系统整屏镜像不属于这条 DLNA 操作流程。即使某款电视自身能接收系统投屏，也需要区分它的系统服务与“云屿投屏”应用。

## 覆盖更多安卓设备的实现路线

跨品牌整屏镜像可以通过配套安卓手机发送端实现：用户在手机上选择电视并确认分享屏幕，手机使用 MediaProjection 采集画面、编码并在局域网中传输；电视增加对应的实时接收、解码和音画同步会话。发送端还需处理横竖屏切换、权限被撤销、前后台切换和连接中断。该路线需要同时开发手机端和电视端，目前没有包含在发布包中。

首次使用时，发送端按所用功能请求权限，例如内部音频捕获需要录音权限。每次开始新的屏幕采集会话都需要系统授权；Android 14 及以上还要求正确声明 MediaProjection 前台服务，采屏令牌不能跨会话重复使用。操作可以简化为“选择电视 → 开始投屏 → 系统确认”，系统确认步骤需要保留。[Android Media projection](https://developer.android.com/media/grow/media-projection)

内部音频捕获从 Android 10 开始提供，但被投 App 必须允许捕获，其音频用途和用户资料范围也须符合要求。部分 App 会禁止内录，普通发送端无法保证所有内容都有声音。[Android 音视频捕获](https://developer.android.com/media/platform/av-capture) 使用安全窗口或受保护视频输出的内容也可能无法采集；Android 的 `FLAG_SECURE` 会阻止窗口出现在截图或不安全的显示输出中。[Android FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)

相册内容可另走文件投送：手机将用户选择的视频或照片提供给电视，电视播放视频或展示图片。视频可复用现有 DLNA 控制；照片需要补充图片下载、解码、展示及格式声明。视频 App 的直接投屏仍依赖该 App 提供兼容协议和可播放内容，配套发送端不能保证解除平台限制。

## 系统投屏协议的接入限制

Miracast 接收涉及电视的 Wi-Fi Display 能力和系统授权。当前 AOSP 将 `CONFIGURE_WIFI_DISPLAY` 设为 `signature|knownSigner`，将 `CONTROL_WIFI_DISPLAY` 设为 `signature`。现有普通签名 APK 不能假定能在所有电视上取得这些权限，接入范围取决于设备系统和厂商支持。[AOSP 权限定义](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/res/AndroidManifest.xml)

Google Cast 的 Android TV 接收方案使用 Cast Connect，建立在已有 Cast 基础设施上，使 Cast 发送应用与 Android TV 应用通信。需要分别确认电视具备的 Cast 能力、发送应用以及接收应用的配置；集成 SDK 本身不能保证任意电视都成为通用 Google Cast 整屏接收器。[Google Cast Android TV Receiver](https://developers.google.com/cast/docs/android_tv_receiver/)

跨品牌覆盖需要分别记录手机型号、系统版本、发送 App、视频格式及电视解码能力。安卓版本和厂商实现存在差异，目前不承诺全机型、全部 App 或所有内容都可投屏，也不承诺所有组合达到 4K60。

## 现有代码依据

- [DlnaDescriptions.kt](../app/src/main/kotlin/io/github/jqssun/airplay/dlna/DlnaDescriptions.kt)：MediaRenderer 设备描述、控制服务和视频／音频格式声明。
- [DlnaRenderer.kt](../app/src/main/kotlin/io/github/jqssun/airplay/dlna/DlnaRenderer.kt)：SSDP 广播、M-SEARCH 响应及网络接口选择。
- [DlnaSoap.kt](../app/src/main/kotlin/io/github/jqssun/airplay/dlna/DlnaSoap.kt)：HTTP／HTTPS 视频地址接收、播放控制和地址限制。
- [AirPlayService.kt](../app/src/main/kotlin/io/github/jqssun/airplay/service/AirPlayService.kt)：AirPlay 服务发布，以及 DLNA 到播放器的控制接入。
