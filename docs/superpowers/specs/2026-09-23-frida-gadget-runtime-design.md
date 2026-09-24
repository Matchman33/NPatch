# 外层 Frida Gadget 运行时设计

## 目标

在不修改 `assets/base.apk` 的前提下，让外层 NPatch 引导过程可选加载 Frida Gadget。支持 Listen 和 Script 两种交互模式；Script 文件允许使用 `.so` 后缀，并与 Gadget、配置文件位于同一运行时目录。

## 交互输入与资产约定

2026-09-24 起，Gadget 从构建时目录改为每次封装时选择。管理器提供启用开关、本地 Gadget 文件选择、Listen/Script 模式和对应配置；CLI 接受等价的本地文件参数。内部仍统一使用三个固定名称：

- `libnpatch-gadget.so`：对应 ABI 的 Gadget ELF。
- `libnpatch-gadget.config.so`：Gadget JSON 配置。
- `libscript.so`：Script 模式的 UTF-8 JavaScript。

固定内部名称避免动态路径进入早期引导代码，也避免用户源文件名影响 Frida 的相邻配置发现。用户选择的源文件不需要预先重命名；封装器根据 ELF 头自动识别 ARM64 或 x86_64，并在目标 APK 内重命名。

## 构建与校验

`wrapper-loader` 只生成基础 NPatch 运行时，不再收集 Gadget。`WrapperGadget` 根据管理器或 CLI 输入生成配置，`WrapperRuntime` 在每次封装时明确移除旧 Gadget 条目，再加入本次选择并执行白名单、ELF 架构、文本编码和文件大小校验：

- `listen` 由界面设置地址、端口和 `wait`/`resume`，端口冲突策略固定为 `fail`。
- `script` 额外要求用户选择本地 UTF-8 脚本，封装后 `interaction.path` 固定为 `libscript.so`。
- 其他交互模式、越目录路径、缺失文件或 ABI 不匹配在封装阶段拒绝。

脚本虽然以 `.so` 结尾，但按 UTF-8 文本校验，不执行 ELF 校验。

## 运行时流程

MetaLoader 在解析当前进程 ABI 后、加载 `libnpatch.so` 前检查 Gadget 资产。仅包名对应的主进程执行以下流程：

1. 清理应用私有 `code_cache/npatch-gadget` 中的旧 Gadget 文件；该目录避开 NPatch 对 `cache/npatch` 的旧版缓存清理。
2. 将 Gadget、配置和可选脚本提取到同一个目录并设为只读。
3. 通过 Gadget 的绝对路径调用 `System.load()`。
4. Gadget 完成 Listen 等待或 Script 执行后，继续原 NPatch 引导链。

Gadget 开关关闭时，本次生成物不包含 Gadget，即使输入运行时来自旧版构建资产。加载失败纳入 MetaLoader 的阶段诊断，不静默降级，避免用户误以为注入已经生效。

## 边界

当前不修改内层 APK，不处理 32 位 ABI，不在远程或隔离进程加载，也不提供规避完整性检测的能力。真实 Gadget 二进制不进入仓库，自动测试使用最小 ELF 样本验证归档规则；Script 模式已完成 Android 15 ARM64 真机验收，Listen 外部连接仍需单独验收。
