# 外层 Frida Gadget 运行时设计

## 目标

在不修改 `assets/base.apk` 的前提下，让外层 NPatch 引导过程可选加载 Frida Gadget。支持 Listen 和 Script 两种交互模式；Script 文件允许使用 `.so` 后缀，并与 Gadget、配置文件位于同一运行时目录。

## 资产约定

构建输入位于忽略提交的 `gadget/runtime/<abi>/`。内部统一使用三个固定名称：

- `libnpatch-gadget.so`：对应 ABI 的 Gadget ELF。
- `libnpatch-gadget.config.so`：Gadget JSON 配置。
- `libscript.so`：Script 模式的 UTF-8 JavaScript。

固定内部名称避免动态路径进入早期引导代码。用户可以把不同版本的官方 Gadget 重命名为该名称。ARM64 与 x86_64 可分别启用；某个 ABI 一旦出现 Gadget 资产，就必须具备完整的模式依赖。

## 构建与校验

`wrapper-loader` 将可选资产写入 `runtime.zip` 的 `gadget/<abi>/`。`WrapperRuntime` 只接受白名单路径，校验 ELF 架构、文本编码和文件大小，并解析配置：

- `listen` 要求 Gadget 和配置。
- `script` 额外要求同目录脚本，且 `interaction.path` 必须严格为 `libscript.so`。
- 其他交互模式、越目录路径、缺失文件或 ABI 不匹配在封装阶段拒绝。

脚本虽然以 `.so` 结尾，但按 UTF-8 文本校验，不执行 ELF 校验。

## 运行时流程

MetaLoader 在解析当前进程 ABI 后、加载 `libnpatch.so` 前检查 Gadget 资产。仅包名对应的主进程执行以下流程：

1. 清理应用私有 `code_cache/npatch-gadget` 中的旧 Gadget 文件；该目录避开 NPatch 对 `cache/npatch` 的旧版缓存清理。
2. 将 Gadget、配置和可选脚本提取到同一个目录并设为只读。
3. 通过 Gadget 的绝对路径调用 `System.load()`。
4. Gadget 完成 Listen 等待或 Script 执行后，继续原 NPatch 引导链。

Gadget 未配置时不改变现有引导行为。加载失败纳入 MetaLoader 的阶段诊断，不静默降级，避免用户误以为注入已经生效。

## 边界

当前不修改内层 APK，不处理 32 位 ABI，不在远程或隔离进程加载，也不提供规避完整性检测的能力。真实 Gadget 二进制不进入仓库，自动测试使用最小 ELF 样本验证归档规则；Script 模式已完成 Android 15 ARM64 真机验收，Listen 外部连接仍需单独验收。
