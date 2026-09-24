# NPatch 封装管理器

当前版本已改回 NPatch 自身的运行时：MetaLoader → libnpatch → LSPApplication → Vector/LSPosed → LSPlant。Pine 和此前单独实现的 WrapperComponentFactory 已移除，wrapper-loader 现在只负责收集原框架构建产物。

保留选择 APK/已安装应用、原图标与名称、原文件名、原包嵌入 `assets/base.apk` 的功能。默认保留原包名，运行时代码和资源均从校验后的原包缓存加载，资源表不再重命名。不需要手机另外安装 Xposed 或 Root；框架随生成物携带。

## 构建

简化管理器也会构建原 NPatch 原生运行时，已不再是免 NDK 的构建路径。本地构建需要：

- 完整的 JDK 21，不能只安装 JRE。Gradle 的 `Daemon JVM` 必须是 21，JDK 17 会产生“无效的源发行版：21”错误。
- Android SDK Platform 37.0（包名 `platforms;android-37.0`）。
- Android SDK Build Tools 37.0.0。
- Android NDK 29.0.13846066。
- CMake 3.31.6。
- Git 及完整的递归子模块。

首次获取源码时使用递归克隆，或在已有工作区补齐全部子模块：

```powershell
git clone --recursive <repository-url>
# 已经克隆仓库时执行：
git submodule update --init --recursive
```

推荐先设置 `JAVA_HOME` 和 `ANDROID_HOME`，以便根构建和 `core` included build 使用相同环境：

```powershell
$env:JAVA_HOME = "<JDK 21 安装目录>"
$env:ANDROID_HOME = "<Android SDK 目录>"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

.\gradlew.bat --version
```

`gradlew --version` 输出中的 `Daemon JVM` 应为 21。然后通过 Android Studio SDK Manager 安装上述组件，或使用命令行工具：

```powershell
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
  "platforms;android-37.0" `
  "build-tools;37.0.0" `
  "ndk;29.0.13846066" `
  "cmake;3.31.6"
```

如果不设置 `ANDROID_HOME`，则必须同时创建根目录的 `local.properties` 和 `core/local.properties`。两个文件内容相同，例如：

```properties
sdk.dir=D:/Sdk
```

只配置根目录的 `local.properties` 不够，因为 `core` 通过 `includeBuild("core")` 作为独立 Gradle 构建运行。完成环境配置后执行：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-manager:assembleRelease :wrapper-cli:fatJar :wrapper-patch:test :patch-loader:testDebugUnitTest
```

libxposed 源码子模块不可用时，core 的两个 Gradle 项目回退到官方 Maven Central 的 API/service/interface 102.0.0；这些是原框架的 API 依赖，不是其他 Hook 引擎。

- 管理器：`wrapper-manager/build/outputs/apk/release/wrapper-manager-release.apk`
- CLI：`out/wrapper/apk-wrapper.jar`
- 引导：`meta-loader` 原始入口 `LSPAppComponentFactoryStub`
- 运行时：`patch-loader` 的 `loader.bin` 及 ARM64/x86_64 `libnpatch.so`

## 使用

```powershell
java -jar out/wrapper/apk-wrapper.jar example.apk -o output
java -jar out/wrapper/apk-wrapper.jar example.apk -o output-signature --signature-compat
```

默认输出包名与原包相同。显式 `-p` 可改包名，但资源表仍保持原样，按当前包名查资源、硬编码包名或渠道 SDK 可能不兼容；管理器会提示这一限制。

本地 APK 保留输入文件名，已安装应用默认导出为“应用名称.apk”。保留图标和多语言名称。生成、导出和安装分开，禁止覆盖原始输入文件。

**同包名不代表同签名。** 默认外层仍使用 NPatch 内置签名；原版若使用不同证书，就不能直接覆盖安装，也不能在同一用户空间以同包名共存。管理器允许先生成和导出，但会阻止已知签名冲突的安装，不自动卸载应用或清除数据；打开操作也不会把原版当作已安装的封装版本。

## 运行策略

- 完整原包固定在 `assets/base.apk`，生成后核对 SHA-256。
- `assets/npatch/config.json` 使用原 PatchConfig，并记录独立封装模式及原包摘要；Manifest 的 npatch 元数据保持原框架格式。
- 独立封装模式强制准备原包缓存，重新建立 LoadedApk，让代码和资源配套。保留原框架组件工厂回退和原生库准备逻辑。
- 原包缓存使用锁、摘要检查、只读文件和原子发布，继续识别旧 NPatch 的 `assets/npatch/origin.apk` 布局。
- 保持原包名的独立封装中，宿主应用通过 `ApplicationInfo.sourceDir`、`getPackageCodePath()` 和 Java `File` 路径接口看到校验后的原包缓存；NPatch 模块调用方仍看到包含注入资产的外层 APK。该规则不按 Android 版本分支。
- 独立封装模式关闭管理器依赖、模块发现和模块加载，但保留 Vector/LSPosed 框架初始化。
- 原签名兼容开关默认关闭；开启时使用原 NPatch 的 SIGBYPASS_EXTREME（等级 3），不再使用 Pine 查询替换。
- 不修改 `assets/base.apk` 字节；可选 Frida Gadget 只作为外层运行时资产显式加载，不会写入内层原包。该能力不保证通过目标应用或服务端的所有完整性校验。
- 当前原框架构建提供 ARM64 和 x86_64，32 位应用不在本构建支持范围内。分包、sharedUserId、isolatedProcess 等仍在输入阶段拒绝。
- 同时保留外层资源副本和完整原包，大型 APK 仍有明显体积与 I/O 成本，尚未使用 NestedZip 去重。

## Frida Gadget

Gadget 改为每次生成 APK 时选择，不再作为管理器的构建时资产。选择目标 APK 后开启“启用 Frida Gadget”，再选择本地官方 Gadget `.so`；管理器会自动识别 ARM64 或 x86_64。详细流程见 [Gadget 运行时说明](gadget/README.md)。

管理器提供 Listen/Script 模式选择。Listen 模式可设置地址、端口和启动时是否等待客户端；Script 模式选择任意本地 UTF-8 JavaScript 文件。封装后内部名称固定为 `libnpatch-gadget.so`、`libnpatch-gadget.config.so` 和 `libscript.so`，配置文件由管理器生成。

关闭开关时，本次生成物不会包含 Gadget。开启后，运行时只在应用主进程提取并显式执行 `System.load()`；Script 模式缺少脚本、文件不是支持的 64 位 ELF、端口无效或脚本不是 UTF-8 时会拒绝生成。

## 验证边界

已使用官方 Frida 17.18.0 Android ARM64 Gadget 完成本地构建、实际 APK 封装和 Android 15 ARM64 真机 Script 模式验收。脚本在首次启动和不重装冷启动时均实际执行，Gadget 保持映射，smoke 自检全部通过。Listen 外部连接和具体目标应用兼容性仍需分别验证，不能据此宣称游戏登录或完整性校验已修复。

旧版独立加载器/Pine 的测试记录属于历史版本，不能当作本版 NPatch 运行时的设备验证。恢复设备测试后，先使用 example.npatch.smoke 样例检查代码/资源路径、组件、SO 和签名查询，再测试具体目标应用。

本轮结果见 [NPatch 原体系本地验证](docs/testing/2026-09-23-npatch-runtime-local.md)。

Gadget 结果见 [Frida Gadget 本地与真机验证](docs/testing/2026-09-23-frida-gadget-device.md)。

管理器按次选择与配置结果见 [Frida Gadget 管理器按次配置验证](docs/testing/2026-09-24-gadget-manager-options.md)。

同包名原包路径结果见 [Android 15 同包名原包路径回归](docs/testing/2026-09-24-android15-original-apk-path.md)。
