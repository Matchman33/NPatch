# Frida Gadget 运行时

仓库不分发 Frida Gadget 二进制。请从可信来源取得与目标 ABI 匹配的官方 Gadget，解压为 `.so` 文件。管理器会读取本地文件并自动识别 `arm64-v8a` 或 `x86_64`，不要求源文件使用固定名称。

## 管理器

选择目标 APK 后，开启“启用 Frida Gadget”：

1. 点击“选择本地 Gadget”，选择解压后的官方 Gadget `.so`。
2. 选择“监听”或“脚本”模式。
3. 监听模式填写地址和端口，并选择启动时是否等待客户端。
4. 脚本模式选择本地 UTF-8 JavaScript 文件；源文件名和扩展名不限。
5. 点击“生成 APK”。

Gadget 只会加入本次生成的 APK。关闭开关后生成的 APK 不包含 Gadget。管理器不会把所选 Gadget 永久编译进自身，也不会修改内嵌的 `assets/base.apk`。

封装时统一使用以下内部名称：

```text
assets/npatch/gadget/<abi>/
├─ libnpatch-gadget.so
├─ libnpatch-gadget.config.so
└─ libscript.so               # 仅脚本模式
```

配置文件由管理器根据界面选项生成。固定内部名称避免用户选择的源文件名与 Frida 相邻配置规则不一致。

## 监听模式

默认监听 `127.0.0.1:27043`。开启“启动时等待客户端连接”时，目标应用会停在 Gadget 加载阶段，直到 Frida 客户端连接：

```powershell
adb forward tcp:27043 tcp:27043
frida -H 127.0.0.1:27043 Gadget
```

关闭等待选项会生成 `"on_load": "resume"`。端口冲突策略固定为 `fail`，避免连接到错误的 Gadget 实例。

## 脚本模式

用户可以选择任意本地 UTF-8 JavaScript 文件。封装时文件会复制为 `libscript.so`，并生成固定配置：

```json
{
  "interaction": {
    "type": "script",
    "path": "libscript.so",
    "on_change": "ignore"
  }
}
```

仓库中的 [脚本示例](examples/script/libscript.so) 可作为起点。`.so` 只是内部固定文件名，内容仍然是 JavaScript，不是 ELF。

## CLI

监听模式：

```powershell
java -jar out/wrapper/apk-wrapper.jar example.apk -o output `
  --gadget C:\path\to\frida-gadget.so `
  --gadget-mode listen `
  --gadget-address 127.0.0.1 `
  --gadget-port 27043
```

加入 `--gadget-resume` 可让 Listen 模式加载后立即继续。脚本模式：

```powershell
java -jar out/wrapper/apk-wrapper.jar example.apk -o output `
  --gadget C:\path\to\frida-gadget.so `
  --gadget-mode script `
  --gadget-script C:\path\to\hook.js
```

## 校验与限制

封装阶段会校验 Gadget 为 ARM64 或 x86_64 的 64 位小端 ELF，脚本为 UTF-8 且不超过 16 MiB。Gadget 文件上限为 128 MiB。

运行时只在包名对应的主进程加载 Gadget。自定义进程、`isolatedProcess`、32 位 ABI 和应用 zygote 不在当前支持范围内。Android 15 ARM64 真机已验证 Script 模式加载和执行；Listen 外部连接和具体目标应用兼容性仍需分别验证。
