# NPatch 框架

[![构建](https://img.shields.io/github/actions/workflow/status/7723mod/NPatch/main.yml?branch=master&logo=github&label=构建&event=push)](https://github.com/7723mod/NPatch/actions/workflows/main.yml?query=event%3Apush+is%3Acompleted+branch%3Amaster) [![下载](https://img.shields.io/github/v/release/7723mod/NPatch?color=orange&logoColor=orange&label=下载&logo=DocuSign)](https://github.com/7723mod/NPatch/releases/latest) [![下载量](https://shields.io/github/downloads/7723mod/NPatch/total?logo=Bookmeter&label=下载量&logoColor=yellow&color=yellow)](https://github.com/7723mod/NPatch/releases)

## 简介

NPatch 是一个无需 Root 的 LSPosed / LSPatch 风格框架，通过向目标 APK 写入 DEX 和原生库，让应用在自身进程中获得 Xposed API 支持。

上游项目官网：[npatch.nkbe.top](https://npatch.nkbe.top)。上游版本的指南、架构说明和发布信息以官网为准。

本分支新增独立 APK 封装流程：管理器可选择本地 APK 或已安装应用，将未修改的原包保存到 `assets/base.apk`，并使用 NPatch 自身运行时加载原包代码和资源。外层运行时还可选择显式加载 Frida Gadget，支持 Listen 与同目录 `.so` 文件名的 Script 模式。详细构建、兼容范围和使用说明见 [封装管理器文档](WRAPPER.md)。

## 支持版本

- 最低版本：Android 9。
- 最高版本：理论上与 [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed#supported-versions) 相同，具体兼容性仍以真机验证为准。
- 当前独立封装构建提供 ARM64 和 x86_64 运行时。

## 构建与使用

独立封装管理器的推荐构建命令：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-manager:assembleRelease :wrapper-cli:fatJar :wrapper-patch:test :patch-loader:testDebugUnitTest
```

主要产物：

- Android 管理器：`wrapper-manager/build/outputs/apk/release/wrapper-manager-release.apk`
- 命令行工具：`out/wrapper/apk-wrapper.jar`

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

NPatch 使用 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) 发布。修改或分发时需要保留相应许可证和版权说明。
