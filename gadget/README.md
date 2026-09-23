# Frida Gadget 运行时

仓库不分发 Frida Gadget 二进制。请从可信来源取得与目标 ABI 匹配的官方 Gadget，解压后重命名为 `libnpatch-gadget.so`，并按下列目录放置：

```text
gadget/runtime/
├─ arm64-v8a/
│  ├─ libnpatch-gadget.so
│  ├─ libnpatch-gadget.config.so
│  └─ libscript.so
└─ x86_64/
   ├─ libnpatch-gadget.so
   ├─ libnpatch-gadget.config.so
   └─ libscript.so
```

`gadget/runtime/` 已被 Git 忽略，避免误提交第三方二进制或测试脚本。每个 ABI 可以独立启用，但同一 ABI 至少要同时提供 Gadget 和配置文件。Script 模式还必须提供脚本文件。

## Listen 模式

把 [Listen 配置示例](examples/listen/libnpatch-gadget.config.so) 复制到目标 ABI 目录。示例监听 `127.0.0.1:27043`，并在 Gadget 加载时等待 Frida 客户端连接。真机通过 USB 调试时可执行：

```powershell
adb forward tcp:27043 tcp:27043
frida -H 127.0.0.1:27043 Gadget
```

目标应用会停在 Gadget 的加载阶段，直到连接成功。若希望应用不等待，需要修改配置中的 `on_load`，并理解这会改变附加时机。

## Script 模式

把 [Script 配置示例](examples/script/libnpatch-gadget.config.so) 和 [脚本示例](examples/script/libscript.so) 一起复制到目标 ABI 目录。

脚本文件允许并要求使用 `.so` 文件名，但其内容必须是 UTF-8 JavaScript。文件名固定为 `libscript.so`，配置使用同目录相对路径：

```json
{
  "interaction": {
    "type": "script",
    "path": "libscript.so",
    "on_change": "ignore"
  }
}
```

运行时会把 Gadget、配置和脚本提取到同一个应用私有的 `code_cache/npatch-gadget` 目录，再显式加载 Gadget。打包后的内部路径为 `assets/npatch/gadget/<abi>/`，不会修改 `assets/base.apk`。

## 构建与限制

放好资产后重新执行正常构建命令。封装时会校验 Gadget 为对应 ABI 的 64 位小端 ELF，配置和脚本为 UTF-8，并限制配置交互模式为 `listen` 或 `script`。

当前只在包名对应的主进程加载 Gadget，避免多个进程争用同一监听端口。自定义进程、`isolatedProcess`、32 位 ABI 和应用 zygote 不在当前支持范围内。仓库不提交实际 Gadget 二进制；Android 15 ARM64 真机已验证 Script 模式加载和执行，Listen 模式的外部客户端连接仍需单独验收。
