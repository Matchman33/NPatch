# APK 测试母版

`wrapper-smoke-base.apk` 是 APK Loom 独立封装功能的固定测试输入，不是生成后的外层
APK。文件中不包含 `assets/base.apk`。

## 文件信息

- 包名：`example.npatch.smoke`
- 来源模块：`wrapper-smoke`
- 构建类型：Debug
- 文件大小：3,943,689 字节
- SHA-256：`0e81aa751e2a947c78e9fc7196d25c828685b37b3d579625e831159f54601d22`

CLI、管理器和真机回归测试应优先使用该文件，以保证不同电脑上的测试输入一致。

## 更新方式

修改 `wrapper-smoke` 后需要更新母版时，先构建：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-smoke:assembleDebug
```

再用新生成的 `wrapper-smoke/build/outputs/apk/debug/wrapper-smoke-debug.apk` 替换
`wrapper-smoke/fixtures/wrapper-smoke-base.apk`，并同步更新本文件中的大小和 SHA-256。
