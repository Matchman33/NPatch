# NPatch 原体系本地验证

日期：2026-09-23。按用户要求，仅本地构建与测试，未使用 ADB、未安装或卸载手机应用。

## 结果

- 已移除 Pine 依赖、自定义 WrapperComponentFactory、SignatureHooks 和对应运行时资产收集逻辑。
- 现直接使用 NPatch 的 MetaLoader、LSPApplication、libnpatch，以及原 core 的 Vector/LSPosed/LSPlant。
- 默认保留输入包名。游戏产物由 aapt2 确认仍为 com.r2games.myhero.aligames，版本 7.6.1 / 203，显示名称与原图标资源引用保持。
- 比较真实游戏 APK，外层 resources.arsc 与原包完全相同；内嵌 assets/base.apk 的 SHA-256 与输入一致。
- 开启兼容的游戏产物配置：standalone=true、useManager=false、sigBypassLevel=3、newPackage 为原包名。
- 原框架重新创建原包 LoadedApk，并让独立封装的代码与资源使用同一缓存；这项运行策略已编译，Android 实际资源加载仍待设备验证。

## 检查

以下构建成功：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-patch:test :patch-loader:testDebugUnitTest :wrapper-cli:fatJar :wrapper-manager:assembleRelease :wrapper-smoke:assembleDebug :wrapper-manager:lintRelease :meta-loader:lintRelease :patch-loader:lintRelease
```

16 项测试通过：封装 10 项、NPatch 运行时资产 3 项、原包缓存 3 项。覆盖同包名、权限与 authority 保持、资源表字节保持、NPatch 配置、原包一致性、输入/输出保护、运行时 DEX/ELF、缓存布局兼容、损坏缓存恢复和错误摘要拒绝。

lint：管理器 0 errors / 6 warnings，MetaLoader 0 errors / 8 warnings，patch-loader 0 errors / 12 warnings。未隐藏剩余警告。修正了原 MetaLoader 的 API 29 回调标注，使 lint 与系统调用边界一致。

DEX 检查确认存在：

- top.nkbe.npatch.metaloader.LSPAppComponentFactoryStub
- top.nkbe.npatch.loader.LSPApplication
- de.robv.android.xposed.XposedBridge
- org.matrix.vector.nativebridge.HookBridge

ARM64 的 libnpatch.so 已由本机 NDK 构建，ELF LOAD 对齐为 0x4000；这仅证明该库的对齐，不代表已在 16 KB 页设备测试。还构建了 x86_64 版本。

## 产物

- 管理器：wrapper-manager/build/outputs/apk/release/wrapper-manager-release.apk
- CLI：out/wrapper/apk-wrapper.jar
- 同包名游戏、开启原框架签名兼容：out/wrapper/npatch/same-package/我的勇者.apk
- 同包名样例，关闭/开启兼容：out/wrapper/npatch/smoke-off/ 和 smoke-on/

真实游戏封装约 40 秒，最终签名及内嵌原包一致性检查通过。输入 SHA-256 为 372b418d479f6fa35e92ddedc5ea39e7f0b9827e49363ce9a875eda6dc408062。所有测试输出位于忽略提交的 out 目录。

## 边界

当前构建提供 ARM64 和 x86_64，不支持仅有 32 位原生库的应用。libxposed 两个不可用源码子模块通过官方 Maven API 102 依赖完成构建，core 子模块内的两个 Gradle 修改需要随工程保留。

同包名产物仍重新签名，不能直接覆盖不同签名的原版。管理器会提示此冲突并允许导出；本轮没有卸载原版或清空任何手机数据。

这不是新的游戏登录成功报告。实际 Android 引导、组件、资源、签名查询及登录仍待真机验证；此前独立加载器/Pine 的设备或本地结果不能代替本版验收。
