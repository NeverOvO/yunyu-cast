# 第三方组件与许可

应用代码沿用根目录 [GPL v3](LICENSE)。下表记录本版本主要组件；各组件原有版权、文件头与许可均保留，具体范围以对应源码为准。

| 组件 | 固定来源 / 版本 | 许可材料 |
| --- | --- | --- |
| android-airplay-server | [a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea](https://github.com/jqssun/android-airplay-server/commit/a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea), v0.0.36 | [GPL v3](LICENSE) |
| UxPlay | [df67c212a433cf6dda3676dd40c097900d24e645](https://github.com/FDH2/UxPlay/tree/df67c212a433cf6dda3676dd40c097900d24e645) | [GPL v3](LICENSES/UxPlay-GPL-3.0.txt) |
| PlayFair（UxPlay 内） | 同 UxPlay 固定提交 | [GPL v3](LICENSES/PlayFair-GPL-3.0.md) |
| llhttp（UxPlay 内） | 同 UxPlay 固定提交 | [MIT](LICENSES/llhttp-MIT.txt) |
| libplist | [f41b1ea67045e0c09339974d83e389972d84f166](https://github.com/libimobiledevice/libplist/tree/f41b1ea67045e0c09339974d83e389972d84f166) | [LGPL 2.1 或后续版本](LICENSES/libplist-LGPL-2.1.txt) |
| libcnary（libplist 内） | 同 libplist 固定提交 | [LGPL 2.1 或后续版本](LICENSES/libcnary-LGPL-2.1.txt) |
| FFmpeg | [38b88335f99e76ed89ff3c93f877fdefce736c13](https://github.com/FFmpeg/FFmpeg/tree/38b88335f99e76ed89ff3c93f877fdefce736c13) | [许可说明](LICENSES/FFmpeg-LICENSE.md)、[LGPL 2.1](LICENSES/FFmpeg-LGPL-2.1.txt) |
| OpenSSL-CMake | [4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8](https://github.com/viaduck/openssl-cmake/tree/4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8) | [MIT](LICENSES/openssl-cmake-MIT.txt) |
| OpenSSL | 3.4.4；构建脚本下载 [固定版本原始源码包](https://mirror.viaduck.org/openssl/openssl-3.4.4.tar.gz) | [Apache 2.0](LICENSES/OpenSSL-3.4.4-Apache-2.0.txt)、[作者](LICENSES/OpenSSL-3.4.4-AUTHORS.md) |

FFmpeg 当前构建仅启用静态 avcodec / avutil 与 ALAC、AAC 解码器，未启用 `--enable-gpl` 或 `--enable-nonfree`。原生构建入口为 `app/src/main/cpp/CMakeLists.txt` 与 `cmake/BuildFFmpeg.cmake`。当前源码的 UxPlay 修改通过 `app/src/main/cpp/patches/UxPlay/` 中的 0001 至 0015 补丁保存；0013 调整直播播放列表等待与序列处理，0014 检查 SETUP 加密字段长度，0015 调整 HTTP 首包收发、分片解析和资源边界。

0.6.1 为 OpenSSL 3.4.4 下载包固定官方 SHA-256，配置位于原生构建入口；Gradle 9.8.1 分发包摘要位于 `gradle/wrapper/gradle-wrapper.properties`。本轮没有升级上述第三方组件版本，也没有改变其许可。

AndroidX / Jetpack Compose / Media3、Oboe、Dagger Hilt 等依赖通过 Gradle 获取；固定版本和坐标见 [gradle/libs.versions.toml](gradle/libs.versions.toml)，依赖源码与声明随各自公开组件提供。Kotlin 与 kotlinx.coroutines 同样保留其原有许可。本清单不改变任何第三方许可。

## 对应源码

本版完整源码附件 `yunyu-cast-0.6.2-preview.1-source.tar.gz` 包含项目源码、四个固定提交的子模块源码、0001 至 0015 原生补丁、构建脚本，以及构建使用的 OpenSSL 3.4.4 原始源码包。下载入口为 [v0.6.2-preview.1](https://github.com/NeverOvO/yunyu-cast/releases/tag/v0.6.2-preview.1)。旧 0.6.0 完整源码归档仍只包含 0001 至 0013 补丁，不包含本版修改。GitHub 自动提供的 Source code ZIP / TAR 不含子模块内容。

当前源码与完整源码包的构建入口见 [docs/SOURCE_BUILD.md](docs/SOURCE_BUILD.md)。APK 对应的完整源码随其 Release 提供，签名私钥和本机配置不随发布分发。
