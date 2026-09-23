# 内置 Hook 运行时本地验证

历史记录：以下是已撤销的 Pine 版本本地检查，不能作为当前 NPatch/Vector 版本的验收结果。

日期：2026-09-23。用户明确暂时关闭 ADB，只做本地测试。本记录不包含 Android 原生 Hook 执行或游戏登录成功的结论。

## 实现

- 内置 `top.canyie.pine:core:0.3.0`，不依赖设备安装 Xposed 或 Root，不提供外部模块加载入口。
- 管理器新增“原签名兼容”开关，默认关闭；CLI 使用 `--signature-compat`。
- 在原组件工厂和 Application 初始化前解析原包证书，安装自身签名查询 Hook。
- 覆盖旧 `signatures`、新 `signingInfo`、`PackageInfoFlags` 入口、包名/UID 的 `hasSigningCertificate`、当前外层路径的 `getPackageArchiveInfo`。
- 保留包名、UID、权限、路径等其他字段；深复制 PackageInfo，保留 SigningInfo 的签名历史，不修改其他包的查询结果。
- 没有实现 `checkSignatures`、直接 Binder 查询、文件级验签替换或服务端校验兼容。
- 关闭开关时不附加运行时 SO，也不初始化 Pine。Pine Java 类仍在加载器 DEX 中，许可证随产物保留。

## 自动检查

以下命令成功：

```powershell
.\gradlew.bat -PstandaloneWrapper=true `
  :wrapper-loader:testDebugUnitTest :wrapper-patch:test `
  :wrapper-cli:fatJar :wrapper-manager:assembleRelease `
  :wrapper-manager:lintRelease :wrapper-loader:lintRelease
```

共 24 项 JUnit 检查通过：

| 检查 | 数量 | 内容 |
| --- | --- | --- |
| WrapperComponentFactoryTest | 5 | 缺失工厂回退、原工厂保留、异常不被吞掉 |
| CertificateSetTest | 3 | 原证书/摘要/历史、多签名语义、无效输入、数组隔离 |
| WrapperPackerTest | 10 | 原包一致性、签名、路径保护、资源/DEX、运行时摘要、关闭模式、无效输入拒绝 |
| WrapperResourcesTest | 3 | 资源表包名、ID 与偏移保持、非法资源表拒绝 |
| WrapperRuntimeTest | 3 | ARM ELF、缺失/错误库及意外路径拒绝 |

管理器 lint 为 0 errors / 6 warnings，加载器为 0 errors / 8 warnings。加载器警告包含私有 API、动态原生库加载及上游库 16 KB 对齐问题；没有屏蔽这些警告。当前 Hook 模式限定 Android 9–15、ARM、4 KB 页，管理器与运行时均检查条件。该范围是允许尝试的范围，不是已经覆盖所有设备的兼容承诺。

## 产物检查

- 管理器 Release：7,415,900 字节，APK 签名验证通过；内置 loader.dex、runtime.zip 和 Pine 许可文件存在。
- Release loader.dex：59,932 字节；runtime.zip：64,858 字节，包含 arm64-v8a 和 armeabi-v7a 库。
- 自有样例关闭模式：`signatureCompat=false`，未附加 Hook SO。
- 自有样例开启模式：`signatureCompat=true`，运行时 `pine-0.3.0`，两个 ABI 的库摘要与配置一致。
- 游戏开启模式：同样两个 ABI 与摘要验证通过；完整内嵌原包 SHA-256 为 `372b418d479f6fa35e92ddedc5ea39e7f0b9827e49363ce9a875eda6dc408062`，与输入一致；最终 APK 签名验证通过，封装日志约 40 秒。
- 上述签名有效仅证明 APK 文件签名正确，不证明应用接受被替换的证书查询，也不证明游戏登录成功。

本地产物：

- `wrapper-manager/build/outputs/apk/release/wrapper-manager-release.apk`
- `out/wrapper/apk-wrapper.jar`
- `out/wrapper/hook/myhero-final/我的勇者.apk`
- `out/wrapper/hook/final-off/wrapper-smoke-debug.apk`
- `out/wrapper/hook/final-on/wrapper-smoke-debug.apk`

## 待设备验证

`wrapper-smoke` 已加入 Application.attachBaseContext 阶段的签名检查，以及 `:probe` 独立进程服务。检查内容包括旧/新查询、签名历史、证书原文与 SHA-256、错误证书返回 false、其他包信息、返回对象修改后的后续查询以及重复查询。原样例证书与外层证书不同，可以区分开关的实际效果。

恢复设备测试时先验证关闭和开启两种样例，再验证游戏。需要看到 `WrapperSmoke: SIGNATURE PASS`、`REMOTE SIGNATURE PASS` 与 `WrapperHooks` 的初始化/命中日志。不得只根据编译通过或文件里存在运行时库宣称 Hook 已生效。

本轮中途 ADB 断开，尝试安装关闭模式样例没有成功；用户随后明确只做本地测试，此后未继续连接或操作设备。当前手机上的管理器和游戏没有更新为本轮 Hook 版本。
