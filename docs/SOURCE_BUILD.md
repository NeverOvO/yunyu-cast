# 完整源码包与构建

当前版本为 0.6.3-preview.1，versionCode 14。APK、完整源码归档、SHA256SUMS 和第三方许可材料通过 [v0.6.3-preview.1 发布入口](https://github.com/NeverOvO/yunyu-cast/releases/tag/v0.6.3-preview.1) 提供。

## 递归克隆当前源码

```sh
git clone --recurse-submodules https://github.com/NeverOvO/yunyu-cast.git
cd yunyu-cast
git submodule update --init --recursive
```

项目保留 Git 子模块及固定提交。当前 UxPlay 修改保存在父仓库 `app/src/main/cpp/patches/UxPlay/` 中的 0001 至 0015 补丁，由 Gradle 构建任务恢复基线并按顺序应用。0014 处理 SETUP 加密字段长度，0015 处理 HTTP 首包收发、分片解析和资源边界。

获取本版发布标签对应源码时，在克隆目录执行：

```sh
git checkout v0.6.3-preview.1
git submodule update --init --recursive
```

历史标签 `v0.6.0-preview.1` 仅包含 0001 至 0013 补丁，不包含本版修改。

## 使用 Release 的完整源码包

下载 `yunyu-cast-0.6.3-preview.1-source.tar.gz` 后解压，在解压目录运行：

```sh
sh scripts/prepare-source-archive.sh
```

该脚本仅为 UxPlay 建立本地 Git 索引基线，供现有补丁任务恢复源文件；不下载代码、不运行构建，也不创建提交。完整包内的 UxPlay 为固定提交的原始源码，Gradle 随后应用仓库的 0001 至 0015 补丁。旧 0.6.0 完整包仍只包含 0001 至 0013 补丁。GitHub 自动生成的 Source code ZIP / TAR 不含子模块，不能替代完整包。

## 环境与命令

准备 Git、JDK 17 及以上（发布环境使用 Android Studio 自带 JDK）、Android SDK 37、NDK 27.0.12077973、CMake 3.22.1、Make、Perl、Python 3 和 patch。根目录 `local.properties` 只需设置本机 `sdk.dir`；不能复用其他人的绝对路径。

```sh
./gradlew :app:assembleDebug
```

调试包可安装，但不能覆盖官方发布签名的安装包。发布构建：

```sh
./gradlew :app:assembleRelease
```

无签名配置时输出 `app/build/outputs/apk/release/app-release-unsigned.apk`，安装前需要自行签名。项目的发布私钥不随源码提供；不能将自己的包作为原发布证书的覆盖升级。

完整包的 `third_party-sources/openssl-3.4.4.tar.gz` 是构建使用的原始源码归档，供获取对应源码使用。OpenSSL-CMake 脚本从固定版本镜像 URL 下载；打包该归档不会自动改成离线构建。Gradle、SDK 和 Maven 依赖同样需要网络。

0.6.1 为 Gradle 9.8.1 分发包和 OpenSSL 3.4.4 原始包固定 SHA-256，摘要分别配置于 `gradle/wrapper/gradle-wrapper.properties` 和 `app/src/main/cpp/CMakeLists.txt`。OpenSSL 使用该版本官方公布的源码包摘要；旧 0.6.0 归档不包含本轮新增的构建下载校验配置。

## 发布与构建范围

0.6.1 已完成 ARM32 / ARM64 Release 构建与 R8 处理、16 KiB 对齐及原发布证书签名。构建排除了三个 `lintVital` 任务，没有执行测试、Android Lint、额外签名验证、截图、模拟器或真机验证，也没有本版覆盖升级的实测结果。本地构建环境处理和执行范围见[2026-10-28 记录](audit-20261028.md)。

0.6.0 已完成 ARM32 / ARM64 Release 构建与原发布证书签名；APK 的 v2 / v3 签名验证通过，证书 SHA-256 与 0.5.4 一致。43 项 JVM 回归测试、17 组原生检查通过，完整 lintRelease 为 0 个错误、70 个警告和 1 个提示。Android 35 模拟器已检查大字号、遥控和权限流程。这些均为历史结果，详见[0.6.0 代码审计记录](audit-20261027.md)。

独立模拟器不等同于索尼电视或真实 AirPlay / DLNA 投屏环境。源码包未进行独立构建，不宣称与发布 APK 逐字节可复现。实际分发状态及附件以对应 GitHub Release 为准。

仓库 Actions 只提供手动触发的未签名 APK 构建，不读取发布私钥，不自动创建 Release。预览版的下载地址使用明确版本标签，避免将预览包误当作稳定版。

## 0.6.2 发布构建

0.6.2 已完成 ARM32 / ARM64 Release 构建、R8 处理、16 KiB 对齐及原发布证书签名。构建排除了三个 `lintVital` 任务，并跳过会重置 UxPlay 文件的 `applyUxplayPatches`，使用本地已有的 0001 至 0015 补丁源文件。

本机构建先因 OpenSSL 下载中断失败，随后使用 0.6.1 完整源码归档内的同版本依赖；还移除了 `app/build` 目录中的同步副本生成文件，再次构建完成。未运行测试、Android Lint、额外签名验证、截图、模拟器或真机验证。完整源码归档仍采用固定子模块与持久补丁，不包含本机副本文件或签名材料。

## 0.6.3 发布构建

本版完成 ARM32 / ARM64 Release 构建与 R8 处理，构建退出码为 0。沿用原生依赖与 0001 至 0015 补丁；本机构建排除了三个 `lintVital` 任务并跳过 `applyUxplayPatches`，保留已有原生工作区。未新增或运行测试、Lint、截图、模拟器或真机验证。签名交付状态见 [0.6.3 发布说明](releases/0.6.3-preview.1.md)。
