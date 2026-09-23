# 管理器导出回退设计

日期：2026-09-23

## 问题

管理器原先在每次导出时直接启动 `ACTION_CREATE_DOCUMENT`。部分 Android 系统虽然包含 DocumentsUI，但从应用进程启动该 Intent 时仍会抛出 `ActivityNotFoundException`，异常未被捕获时会直接终止管理器进程。

## 行为

- 优先保留系统文档创建器，使支持该能力的设备仍可自行选择保存位置。
- 在界面边界捕获文档创建器同步启动失败。
- Android 10 及以上系统发生该异常时，通过 `MediaStore.Downloads` 将生成物导出到 `Download/NPatch/<文件名>`。
- Android 9 无法无权限写入公共下载目录，因此回退到应用专属的外部下载目录。
- 回退导出成功后向用户显示实际保存位置。
- 复制失败时删除未完成的 MediaStore 记录，避免留下损坏或不可见的半成品。
- 正常文档创建器流程继续保留“禁止覆盖原始输入 APK”的保护。
- 同名文件由系统自动生成 `(1)` 等后缀，不覆盖已有导出文件。

## 组件职责

- `MainActivity`：启动系统文档创建器，并在启动失败时调用回退导出。
- `WrapperViewModel`：在后台线程中复制文件，负责 MediaStore 创建、完成标记、失败清理和用户提示。

## 验证

- 构建 Release 管理器并运行现有单元测试。
- 将管理器安装到已连接的 Android 15 设备。
- 使用小型已安装应用生成封装 APK，并在实际触发 `ActivityNotFoundException` 后确认自动写入 `Download/NPatch`。
- 校验导出 APK 可解析、v3 签名有效，并包含完整的 `assets/base.apk`。
- 连续导出两次，确认第二个文件使用 `(1)` 后缀、两个文件哈希一致、管理器 PID 不变且没有 `AndroidRuntime` 崩溃日志。
