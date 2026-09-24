# Frida Gadget 管理器按次配置验证

日期：2026-09-24。本轮将 Gadget 从构建时资产改为每次生成 APK 时由管理器或 CLI 显式选择；未进行新的 Android 设备安装或运行测试。

## 设计结果

- 管理器新增“启用 Frida Gadget”开关，关闭时生成物不包含 Gadget。
- Gadget 从系统文件选择器读取，自动识别 `arm64-v8a` 或 `x86_64`，源文件名不影响封装。
- Listen 模式可设置地址、端口及启动时 `wait`/`resume`。
- Script 模式选择任意本地 UTF-8 JavaScript 文件。
- APK 内部名称固定为 `libnpatch-gadget.so`、`libnpatch-gadget.config.so` 和 `libscript.so`。
- `wrapper-loader` 不再把 `gadget/runtime` 自动写入管理器或 CLI。
- CLI 新增 `--gadget`、`--gadget-mode`、`--gadget-address`、`--gadget-port`、`--gadget-resume` 和 `--gadget-script`。

## 自动验证

以下命令通过：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-manager:assembleRelease :wrapper-cli:fatJar :wrapper-patch:test :patch-loader:testDebugUnitTest
```

结果为 `BUILD SUCCESSFUL`，共 295 个任务。新增测试覆盖：

- ARM64/x86_64 ELF 识别。
- Listen 配置生成和端口校验。
- Script 固定路径、UTF-8 校验和脚本写入。
- 关闭开关移除旧 Gadget 条目。
- 本地选择覆盖旧构建资产，并记录 `gadgetEnabled`、`gadgetAbi`、`gadgetMode`。

Release 管理器资源中已确认存在中英文 Gadget 开关。基础 `runtime.zip` 只包含 `loader.bin` 与 ARM64/x86_64 `libnpatch.so`，不包含预装 Gadget。CLI `--help` 已列出全部新增参数。

## 剩余验证

昨晚的 Android 15 ARM64 Script 真机结果证明底层加载链可用，但新的文件选择界面和按次配置尚未重新进行真机端到端验收。后续应分别验证 Listen 的 USB 转发连接、Script 文件执行、关闭开关的无 Gadget 生成物，以及选择错误 ABI/压缩包时的错误提示。
