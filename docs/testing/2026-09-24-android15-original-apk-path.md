# Android 15 同包名原包路径回归

日期：2026-09-24。设备：OnePlus PLC110，Android 15 / API 35，arm64-v8a，通过无线 ADB 连接。

本轮验证独立封装在保持原包名时，不再按 Android 版本决定宿主可见的 APK 路径。宿主应用统一看到校验后的原包缓存；NPatch 模块调用方继续看到包含加载器和注入资产的外层 APK。

## 输入与构建

- 原包：`com.r2games.myhero.aligames`，版本 7.6.1，versionCode 203。
- 原包文件：`/sdcard/mt2/apks/原版.apk`，1,483,668,524 字节。
- 生成时保持原包名并开启原签名兼容，关闭 Frida Gadget。
- 管理器设备端生成耗时 65 秒，外层 APK 为 3,183,844,889 字节。
- 设备的核心破解模块允许不同签名的同包名 APK 直接覆盖安装；该能力不属于 NPatch 自身保证范围。

完整构建命令通过：

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-manager:assembleRelease :wrapper-cli:fatJar :wrapper-patch:test :patch-loader:testDebugUnitTest
```

## 结果

- 外层 APK 跨签名覆盖安装成功，目标进程持续存活。
- `code_cache` 中生成的原包缓存为 1,483,668,524 字节，与输入大小一致。
- 目标进程的内存映射和多个文件描述符指向该缓存原包；外层安装路径仍作为系统安装入口存在。
- Unity 正常进入游戏标题界面，没有出现 `Not enough storage space to install required resources`。
- 启动日志中未发现存储不足、`No space left`、`FATAL EXCEPTION`、`SIGABRT` 或进程退出。
- 日志仍有原包自身缺少 `android.support.v4.app.CoreComponentFactory` 的非致命回退信息；原版也存在该信息，本轮不将其视为路径回归。

本轮只验证关闭 Gadget 时的 APK 路径和大型 Unity 应用启动。它不证明 Android 15 上 Frida Gadget 的 Java bridge、Listen 客户端连接、游戏登录或服务端完整性校验可用。

## 本地证据

以下文件位于忽略提交的 `out/test/android15/`：

- `wrapped-launch.png`：封装版进入游戏标题界面。
- `wrapped-launch-logcat.txt`：冷启动日志。
- `wrapped-after-start-logcat.txt`：继续观察后的日志。
