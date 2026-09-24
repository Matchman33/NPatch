# APK 测试母版

`wrapper-smoke-base.apk` 是 APK Loom 独立封装功能的固定测试输入，不是生成后的外层
APK。文件中不包含 `assets/base.apk`。

## 文件信息

- 包名：`example.npatch.smoke`
- 来源模块：`wrapper-smoke`
- 构建类型：Debug
- 文件大小：3,964,132 字节
- SHA-256：`4a2dfbd7e7c501717d77766d511f9fb67d349d3ac0005101d6a72f35d3a3439b`
- 包名自检：界面和 `WrapperSmoke` 日志会输出当前包名、写死的
  `example.npatch.smoke` 以及独立的 `packageIdentity` 结果；该结果不影响总
  `PASS`。

CLI、管理器和真机回归测试应优先使用该文件，以保证不同电脑上的测试输入一致。

## 更新方式

修改 `wrapper-smoke` 后需要更新母版时，先构建：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-smoke:assembleDebug
```

再用新生成的 `wrapper-smoke/build/outputs/apk/debug/wrapper-smoke-debug.apk` 替换
`wrapper-smoke/fixtures/wrapper-smoke-base.apk`，并同步更新本文件中的大小和 SHA-256。
