# 完整源码包与构建

## 递归克隆

```sh
git clone --recurse-submodules https://github.com/NeverOvO/yunyu-cast.git
cd yunyu-cast
git checkout v0.4.1-preview.1
git submodule update --init --recursive
```

项目保留 Git 子模块及固定提交。UxPlay 的修改保存在父仓库补丁文件中，由 Gradle 构建任务恢复基线并应用。

## 使用 Release 的完整源码包

下载 `yunyu-cast-0.4.1-preview.1-source.tar.gz` 后解压，在解压目录运行：

```sh
sh scripts/prepare-source-archive.sh
```

该脚本仅为 UxPlay 建立本地 Git 索引基线，供现有补丁任务恢复源文件；不下载代码、不运行构建，也不创建提交。完整包内的 UxPlay 为固定提交的原始源码，Gradle 随后应用仓库的 0001–0011 补丁。GitHub 自动生成的 Source code ZIP / TAR 不含子模块，不能替代完整包。

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

完整包的 `third_party-sources/openssl-3.4.4.tar.gz` 是实际构建使用的原始源码归档，供获取对应源码使用。现有 OpenSSL-CMake 脚本仍从固定版本镜像 URL 下载；打包该归档不会自动改成离线构建。Gradle、SDK 和 Maven 依赖同样需要网络。

## 发布与构建范围

0.4.1 的已发布 APK 使用原有构建和签名产物。本次公开仓库整理没有重新编译或重新签名 APK，也没有宣称源码包经过独立构建或与 APK 逐字节可复现。

仓库 Actions 只提供手动触发的未签名 APK 构建，不读取发布私钥，不自动创建 Release。预览版的下载地址使用明确版本标签，避免将预览包误当作稳定版。
