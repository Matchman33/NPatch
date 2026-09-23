# NPatch 封装管理器

当前版本已改回 NPatch 自身的运行时：MetaLoader → libnpatch → LSPApplication → Vector/LSPosed → LSPlant。Pine 和此前单独实现的 WrapperComponentFactory 已移除，wrapper-loader 现在只负责收集原框架构建产物。

保留选择 APK/已安装应用、原图标与名称、原文件名、原包嵌入 `assets/base.apk` 的功能。默认保留原包名，运行时代码和资源均从校验后的原包缓存加载，资源表不再重命名。不需要手机另外安装 Xposed 或 Root；框架随生成物携带。

## 构建

需要 Java 21、SDK/Build Tools 37、NDK 29.0.13846066 和 CMake 3.31.6。简化管理器也会构建原 NPatch 原生运行时，已不再是免 NDK 的构建路径。

```powershell
git submodule update --init core
git -C core submodule update --init --recursive external/axml/manifest-editor external/apache/commons-lang external/dobby external/fmt external/lsplant external/xz-embedded
.\gradlew.bat -PstandaloneWrapper=true :wrapper-manager:assembleRelease :wrapper-cli:fatJar :wrapper-patch:test :patch-loader:testDebugUnitTest
```

设置 `JAVA_HOME` 和 `ANDROID_HOME`。libxposed 源码子模块不可用时，core 的两个 Gradle 项目回退到官方 Maven Central 的 API/service/interface 102.0.0；这些是原框架的 API 依赖，不是其他 Hook 引擎。

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
- 独立封装模式关闭管理器依赖、模块发现和模块加载，但保留 Vector/LSPosed 框架初始化。
- 原签名兼容开关默认关闭；开启时使用原 NPatch 的 SIGBYPASS_EXTREME（等级 3），不再使用 Pine 查询替换。
- 不修改原包字节，不注入 Gadget，不保证通过目标应用或服务端的所有完整性校验。
- 当前原框架构建提供 ARM64 和 x86_64，32 位应用不在本构建支持范围内。分包、sharedUserId、isolatedProcess 等仍在输入阶段拒绝。
- 同时保留外层资源副本和完整原包，大型 APK 仍有明显体积与 I/O 成本，尚未使用 NestedZip 去重。

## 验证边界

用户要求暂时关闭 ADB，只做本地测试。本次验证覆盖构建、封装、签名、资源表与原包一致性、框架资产及缓存异常处理；新运行时尚未做设备验收，也没有据此宣称游戏登录已修复。

旧版独立加载器/Pine 的测试记录属于历史版本，不能当作本版 NPatch 运行时的设备验证。恢复设备测试后，先使用 example.npatch.smoke 样例检查代码/资源路径、组件、SO 和签名查询，再测试具体目标应用。

本轮结果见 [NPatch 原体系本地验证](docs/testing/2026-09-23-npatch-runtime-local.md)。
