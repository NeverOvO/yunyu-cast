# 应用内更新

云屿投屏从 0.5.0-preview.1 开始提供应用内检查更新。更新来源固定为公开仓库 [NeverOvO/yunyu-cast](https://github.com/NeverOvO/yunyu-cast/releases)，不需要 GitHub 账号、token 或 `update.json`。

当前版本为 0.6.1-preview.1，versionCode 12。0.5.0 至 0.6.0 可通过“检查更新”获取本版，下载入口与附件见 [v0.6.1-preview.1](https://github.com/NeverOvO/yunyu-cast/releases/tag/v0.6.1-preview.1)。更新查询同时接收预览版和正式版。

## 用户操作

### 第一次升级到支持更新的版本

0.4.1 及以前没有应用内更新入口。先从 [0.6.1 发布页](https://github.com/NeverOvO/yunyu-cast/releases/tag/v0.6.1-preview.1) 下载 APK，通过 U 盘或 ADB 覆盖安装一次。之后可在首页或“画面设置”使用“检查更新”。

### 检查、下载和安装

1. 点“检查更新”，查看当前版本、新版本及更新说明。没有更高的有效版本时显示“已经是最新版”；网络或服务失败时显示原因和“重试”。
2. 点“下载并安装”后，系统 DownloadManager 开始后台下载。窗口显示百分比；暂时未知总大小时显示已下载大小。
3. 点“稍后安装”可关闭窗口并继续下载，点“取消下载”停止本次下载。退出后重新进入应用，可恢复显示下载进度。
4. 下载完成且文件检查通过后，若仍在应用页面并且没有投屏，会打开系统安装流程。稍后回来时也可点“安装更新”。
5. 首次更新若显示“允许安装更新”，进入电视系统页面允许“云屿投屏”作为安装来源，按返回后继续。在系统安装页面确认安装。

Android 8.0 起，安装来源授权由用户按应用控制。应用通过 [`PackageManager.canRequestPackageInstalls()`](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) 判断是否获准请求安装，通过 [`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES) 打开对应系统设置。允许安装来源不等于自动同意后续安装；云屿投屏仍交由系统页面让用户确认。

投屏期间不会弹出更新窗口或自动打开安装。安装会替换正在运行的应用；安装完成后会尝试恢复接收，电视系统限制后台启动时，重新打开“云屿投屏”即可。安装来源权限与自动显示投屏画面使用的悬浮窗权限相互独立。

### 自动检查与网络失败

应用在前台空闲，且距离上次检查至少 12 小时时自动查询更新。自动查询不会开始下载，下载需用户确认。预览版和正式版都会参与版本选择。

更新使用 GitHub 公开 Releases 接口。公开发布信息可以匿名读取；草稿不向匿名用户提供。参见 [GitHub List releases](https://docs.github.com/en/rest/releases/releases#list-releases)。客户端读取返回列表中的最多 100 条记录，从中选择语义版本最高、附件符合要求的新版。

GitHub 连接失败、限流、超时或新版附件尚未准备好时，应用显示重试提示，不会把失败当成“已经是最新版”。这些情况不影响当前版本的局域网投屏。

### 安装包检查

下载完成后，应用检查包名、比当前更大的 versionCode、与发布标签一致的语义版本、电视是否满足最低 Android 版本、签名集合，以及 GitHub 提供的 SHA-256 摘要（若有）。文件检查通过后复制到应用私有缓存，再交给系统安装；不符合要求的文件不会进入安装流程。

0.6.1 将安装包准备阶段的互斥锁提升为进程内共享锁，串行处理同一临时文件的清理、复制和检查。更新弹窗按宿主窗口安全高度限高，正文独立滚动，下载和关闭按钮位于底部。

本轮完成 ARM32 / ARM64 Release 构建、16 KiB 对齐及原发布证书签名，未执行测试、Lint、额外签名验证、截图、模拟器或真机验证，也没有本版覆盖升级的实测结果。0.6.0 的历史构建、签名验证和模拟器结果见[对应审计记录](audit-20261027.md)，不计为 0.6.1 的结果。

## 维护者发布契约

应用更新依赖发布元数据与安装包保持一致，未来版本需要沿用以下规则。

### 版本和包身份

| 项目 | 规则 | 本版对应值 |
| --- | --- | --- |
| 仓库 | 固定使用 `NeverOvO/yunyu-cast` 的公开 Releases | `NeverOvO/yunyu-cast` |
| versionName | 使用语义版本，必须高于已安装版本 | `0.6.1-preview.1` |
| versionCode | 每次发布递增，安装包内数值必须高于已安装版本 | `12` |
| Release tag | `v<versionName>`，与 APK 内版本一致 | `v0.6.1-preview.1` |
| APK 文件名 | `yunyu-cast-<versionName>.apk`，使用英文前缀 | `yunyu-cast-0.6.1-preview.1.apk` |
| 包名 | 保持 `com.yunyulai.tvcast` | `com.yunyulai.tvcast` |
| 签名 | 沿用当前 Release 证书，保持签名集合一致 | 不替换为调试证书 |
| 系统兼容 | 保持既有设备可用的 minSdk；当前为 API 26 | Android 8.0 及以上 |

APK 必须作为 Release 附件上传，附件状态为 `uploaded`，大小大于 0 且不超过 256 MiB。下载地址必须来自本仓库的 GitHub Release，不能换成外部网盘或镜像。仅创建 Git 标签、上传源码 ZIP 或发布不符合命名的 APK，不会成为可安装的更新。

预览版可以设置 `prerelease: true`，正式版可以设置为 `false`；两者都参与比较。稳定版与预览版的高低按语义版本判断，不以发布时间或“Latest”标记代替。

### 发布顺序

1. 增加 versionName 和 versionCode，生成同包名、同发布证书签名的 APK。
2. 创建对应标签及 Release 草稿，使用本版变更编写发布说明。首段使用简短普通文本，说明用户可见的变化，应用会显示这段内容。
3. 完整上传 `yunyu-cast-<versionName>.apk`、对应完整源码包、`SHA256SUMS.txt` 和第三方许可材料。
4. 附件上传完成后再发布，将 `draft` 设为 `false`。不要先公开 Release 再逐步补传 APK，以免电视看到尚未完成的版本。
5. 更新 README 下载入口、CHANGELOG 和版本发布说明，保留对应源码与构建资料。

客户端使用 GitHub Release 附件的 `digest` 字段获取可用的 SHA-256；`SHA256SUMS.txt` 供人工下载时核对，不能替代应用的包名、版本和签名检查。发布私钥及其密码不进入源码、Release 附件或公开日志。

0.5.0-preview.1 本次未新增或执行测试、Lint、模拟器或真机验证；0.4.0 的 13 项回归测试为历史记录。
