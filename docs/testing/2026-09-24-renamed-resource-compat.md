# 重命名包名资源兼容测试

日期：2026-09-24

## 修复内容

- 重命名封装时只改写外层 `resources.arsc` 的主包名称字段，不改变资源 ID、块大小或
  其他资源数据。
- 内嵌 `assets/base.apk` 保持与输入 APK 完全一致。
- 代码、DEX 和原生库继续从内部原包缓存加载，资源从外层 APK 加载。
- 运行时把原包名形式的动态资源查询映射到新包名，同时保留新包名查询。

## 本地验证

固定测试输入为：

```text
wrapper-smoke/fixtures/wrapper-smoke-base.apk
```

以下命令构建成功：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-patch:test `
  :patch-loader:testDebugUnitTest :wrapper-cli:fatJar `
  :wrapper-smoke:assembleDebug :wrapper-manager:assembleRelease
```

静态检查确认：

- 外层 `resources.arsc` 包含 `example.npatch.renamed`，不再包含
  `example.npatch.smoke`。
- 外层资源表保持 `STORED`。
- `assets/base.apk` 与输入 APK 的 SHA-256 均为
  `0e81aa751e2a947c78e9fc7196d25c828685b37b3d579625e831159f54601d22`。

## 真机验证

设备：PLC110，Android 15 / API 35。

重命名为 `example.npatch.renamed` 后冷启动输出：

```text
PASS package=example.npatch.renamed ... codePath=true namedResource=true originalNamedResource=true signatures=true
```

路径符合设计：代码路径位于应用缓存中的内部原 APK，资源路径位于已安装的外层 APK，
`resourceIsWrapper=true`。

保留 `example.npatch.smoke` 原包名的回归样例也输出 `PASS`；代码和资源继续来自内部
原 APK，`codeMatchesResources=true`、`resourceIsWrapper=false`。

## Lint 说明

`patch-loader:lintDebug` 已通过。完整 lint 仍被两项既有问题阻塞：

- `wrapper-smoke:lintDebug` 会命中 `SignatureProbe` 中故意传入非法签名标志的负向测试
  代码。
- `wrapper-manager:lintRelease` 会命中 `WrapperViewModel.kt` 使用 API 33
  `InputStream.readNBytes()`、而项目最低 API 为 28 的问题。

两项均不在本次资源兼容改动文件中；烟雾 APK 与管理器 release 的正常构建已通过。
