# Frida Gadget 本地与真机验证

## 验证范围

本轮验证外层 NPatch 运行时对 Frida Gadget Listen/Script 模式的资产收集、归档校验和显式加载入口，并在 Android 15 ARM64 真机上完成 Script 模式执行。Listen 配置已通过自动校验，但尚未连接外部 Frida 客户端。

## 测试资产

- Frida 版本：`17.18.0`。
- 平台：`android-arm64`。
- 官方压缩包：`frida-gadget-17.18.0-android-arm64.so.xz`。
- 压缩包 SHA-256：`c176650121a70b4345ce3d47c422df295fc8d931fb14f75887dddc0230c79e81`，与官方 Release API 一致。
- 解压 Gadget SHA-256：`c87c53efc10a9b6f2f4259b7b962d403cac729d6ee6f6a83f679489f92671a3a`。
- 解压文件大小：25,220,848 字节；ELF64、小端、AArch64 机器号检查通过。
- 动态依赖：Android 系统的 `libm.so`、`liblog.so`、`libdl.so` 和 `libc.so`。
- Script 文件名：`libscript.so`，内容为 UTF-8 JavaScript。

测试二进制和脚本放在 Git 忽略的 `gadget/runtime/arm64-v8a/`，不会进入提交历史。

## 自动测试

`:wrapper-patch:test` 通过，覆盖以下规则：

- 未配置 Gadget 时保持原三项 NPatch 运行时。
- ARM64/x86_64 ELF 架构校验。
- Listen 和 Script 模式配置。
- Script 缺失、越目录路径和无效配置拒绝。
- `libscript.so` 按 UTF-8 脚本处理，不按 ELF 处理。

完整 Release 构建通过：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-manager:assembleRelease :wrapper-cli:fatJar :wrapper-patch:test :patch-loader:testDebugUnitTest
```

## 实际封装

使用 `wrapper-smoke-debug.apk` 作为输入，通过 `out/wrapper/apk-wrapper.jar` 生成外层 APK。输出包含：

- `assets/base.apk`：3,963,220 字节，SHA-256 与输入一致。
- `assets/npatch/gadget/arm64-v8a/libnpatch-gadget.so`：25,220,848 字节，SHA-256 与本地 Gadget 一致。
- `assets/npatch/gadget/arm64-v8a/libnpatch-gadget.config.so`：Script 配置，`path` 为 `libscript.so`。
- `assets/npatch/gadget/arm64-v8a/libscript.so`：UTF-8 JavaScript。

`apksigner` 验证通过，输出使用 APK Signature Scheme v3，签名者为 NPatch 内置证书。

## 真机结果

- 设备：PLC110，Android 15，SDK 35，`arm64-v8a`。
- 外层 smoke APK 使用 `adb install -r -t` 安装成功。
- 首次测试发现 NPatch 后续会清理 `cache/npatch`，因此将 Gadget 稳定目录改为 `code_cache/npatch-gadget`。
- 修复后 Gadget、配置和 `libscript.so` 均保持在同一私有目录，权限分别为只读可执行和只读。
- 测试脚本创建 `cache/npatch-gadget-script.loaded`，内容为 `loaded`，证明 Script 代码实际执行。
- `/proc/<pid>/maps` 可见 `libnpatch-gadget.so` 从 `code_cache/npatch-gadget` 映射。
- 不重装再次冷启动后，脚本标记再次生成，Gadget 再次映射。
- `WrapperSmoke` 报告 `PASS`，代码、资源、组件、原生库、类加载器和签名检查全部通过，应用进程保持运行。

真机测试使用的写标记脚本只存在于 Git 忽略的运行时目录。测试结束后已恢复仓库提供的通用 `libscript.so` 示例。

## 待验证

1. 将配置切换为 Listen 模式，确认 `on_load=wait` 时进程等待，并通过 ADB 端口转发连接 27043。
2. 检查具体目标应用自身的完整性校验、登录流程和多进程行为。
