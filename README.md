# APK Loom

## 简介

APK Loom 是一个无需 Root 的独立 APK 封装工具。它将未修改的原 APK、加载器和运行时组合为一个可安装的 APK，并可按次加入 Frida Gadget。

底层基于 NPatch 运行时，继续使用其 MetaLoader、Vector/LSPosed 与 LSPlant 链路。上游项目官网：[npatch.nkbe.top](https://npatch.nkbe.top)，上游指南、架构说明和发布信息以官网为准。

管理器可选择本地 APK 或已安装应用，将原包保存到 `assets/base.apk`，再由 NPatch 运行时加载原包代码和资源。详细构建、兼容范围和使用说明见 [APK Loom 文档](WRAPPER.md)。

品牌源文件和 Android 图标预览见 [`branding`](branding) 目录。交错的两层方框分别表示原应用与外层运行时。

为了保持已安装管理器可直接升级以及既有封装格式兼容，Android `applicationId`、Java/Kotlin 包名、`assets/npatch` 和 `libnpatch.so` 暂时保留原技术名称。这些名称不再作为产品品牌展示。

## 支持版本

- 最低版本：Android 9。
- 最高版本：理论上与 [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed#supported-versions) 相同，具体兼容性仍以真机验证为准。
- 当前独立封装构建提供 ARM64 和 x86_64 运行时。

## 构建与使用

本地构建要求：

- Git 子模块必须完整检出，首次构建前执行 `git submodule update --init --recursive`。
- 使用完整的 JDK 21；可通过 `./gradlew --version` 或 `.\gradlew.bat --version` 确认 `Daemon JVM` 为 21。
- Android SDK 需要安装 Platform 37.0、Build Tools 37.0.0、NDK 29.0.13846066 和 CMake 3.31.6，并接受相应 SDK 许可证。
- 推荐设置 `ANDROID_HOME`。如果改用 `local.properties`，根目录和 `core` 目录都必须配置 `sdk.dir`，因为 `core` 是独立的 Gradle included build。

完整的环境配置与排错说明见 [封装管理器文档](WRAPPER.md#构建)。

独立封装管理器的推荐构建命令：

```powershell
.\gradlew.bat -PstandaloneWrapper=true -PallowDebugSigning=true :wrapper-manager:collectReleaseArtifacts :wrapper-manager:testDebugUnitTest :wrapper-patch:test :patch-loader:testDebugUnitTest
```

主要产物：

- Android 管理器：`wrapper-manager/build/outputs/apk/release/wrapper-manager-release.apk`
- 命令行工具：`out/wrapper/apkloom-cli.jar`
- 本地校验包与校验文件：`out/releases/1.0.7-local/`。`-local` 表示使用本机 Debug 签名，不应作为正式版本发布。

正式构建须配置固定签名密钥并去掉 `-PallowDebugSigning=true`；缺少签名会使构建失败。
版本号在 `gradle.properties` 中显式维护，已不依赖远端分支提交数。
签名配置及 CI 说明见 [发布构建](WRAPPER.md#发布构建)。

传统 NPatch 使用方式：

- Jar：下载 `npatch.jar`，执行 `java -jar npatch.jar`。
- 管理器：在 Android 设备上安装 `manager.apk`，按照应用内指引操作。

## 上游下载

- 稳定版：[GitHub Releases](https://github.com/7723mod/NPatch/releases)
- 测试构建：[GitHub Actions](https://github.com/7723mod/NPatch/actions)

## 致谢

- [LSPosed](https://github.com/JingMatrix/LSPosed)：核心框架。
- [Xpatch](https://github.com/WindySha/Xpatch)：分支来源。
- [Apkzlib](https://android.googlesource.com/platform/tools/apkzlib)：APK 重打包工具。

## 许可证

APK Loom 基于 NPatch，按 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) 发布。修改或分发时需要保留相应许可证和版权说明。
