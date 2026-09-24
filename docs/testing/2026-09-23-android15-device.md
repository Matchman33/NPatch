# Android 15 设备验收

日期：2026-09-23。设备型号 PLC110，Android 15 / API 35，arm64-v8a，通过无线 ADB 连接。

使用 `wrapper-manager-release.apk` 进行设备操作。主要运行时测试对象为本仓库新增的 `wrapper-smoke` 自有样例。后续导出回退测试使用已安装的 `com.matchman33.myheromom` 作为小型输入，只执行提取、生成和导出，没有安装生成物，也没有修改其原安装包或数据。

## 结果

| 检查 | 结果 |
| --- | --- |
| 管理器安装、冷启动和首页 | 通过 |
| 读取已安装应用列表、搜索并选择样例 | 通过 |
| 提取已安装样例并在手机端生成 APK | 通过 |
| 原版与 `.wrapped` 外层同时安装 | 通过 |
| 外层原 Application / AppComponentFactory | 通过 |
| Provider 创建及通过外层 authority 查询 | 通过 |
| Service、显式广播和第二 Activity 跳转 | 通过 |
| 原资源表、XML 布局、自定义 View 及 Context ClassLoader | 通过 |
| 原包内 arm64 SO 解包、加载及 AndroidX PathIterator 调用 | 通过 |
| 原版与外层数据独立 | 通过，原版计数为 3 时外层为 1 |
| 内嵌 `assets/base.apk` 字节一致性 | 通过，SHA-256 相同 |
| 手机生成物的签名验证 | 通过 |
| 原版、管理器均卸载且外层缓存删除后冷启动 | 通过，约 772 ms，全部样例断言为 PASS |
| 本地 APK 文件选择及默认文件名 | 通过，保留 `Codex-Loader-Smoke-20260923.apk` |
| 系统文档选择器导出及同名文件保护 | 通过，自动保存为 `(1).apk`，原输入哈希未变 |
| 导出 APK 再安装和数据保留 | 通过，冷启动 PASS，计数从 3 增至 4 |
| 文档创建器启动失败时的下载目录回退 | 通过，自动写入 `Download/NPatch`，管理器未崩溃 |
| 回退导出的同名文件处理 | 通过，第二次导出为 `MyHeroMom (1).apk`，两个文件 SHA-256 一致 |

原包与手机生成物的内嵌原包 SHA-256：

```text
227c322f9a2bab92016d153aa4f142e560c3a37366e79f653547b66087dd3d50
```

运行时检查实际发现的 SO 位于外层私有目录：

```text
code_cache/wrapper/<原包摘要>/arm64-v8a/libandroidx.graphics.path.so
```

设备禁止 shell 使用 `pm clear`，因此缓存重建测试改为先停止自有样例，再通过其 debuggable 身份删除自己的 `code_cache/wrapper`。管理器临时卸载使用保留数据的方式，测试后已恢复安装。

## 复现与证据

```powershell
.\gradlew.bat -PstandaloneWrapper=true :wrapper-smoke:assembleDebug
```

固定样例母版：`wrapper-smoke/fixtures/wrapper-smoke-base.apk`。其源码位于
`wrapper-smoke`，样例是独立测试项目，不随管理器打包。

本机证据保存在 `out/wrapper/device/`：

- `wrapped-running.png`：原版仍安装时，外层运行的 PASS 页面。
- `independent-cold-start.png`：原版和管理器移除、缓存重新生成后的 PASS 页面。
- `manager-result.png`：管理器成功生成并导出的界面。
- `device-generated.apk`：从设备安装目录取回的手机生成物。
- `local-file-export.apk`：通过系统文档选择器导出的生成物。

本轮未发现需要修复的产品代码问题。Android 9/12/14、其他厂商设备、分包和签名自校验应用不由本次结果覆盖。原生库验证覆盖实际 SO 加载及库调用流程，不等价于任意 JNI 库兼容性保证。

## 导出回退补充验证

设备上的系统文档创建 Activity 可被 shell 查询到，但管理器实际启动 `ACTION_CREATE_DOCUMENT` 时仍抛出 `ActivityNotFoundException`。新版管理器捕获该异常后改用 MediaStore 导出，验证结果如下：

```text
保存位置：Download/NPatch/MyHeroMom.apk
文件大小：15498030 字节
SHA-256：8486311014cc45e2279069fd73c856cf1a970b613e26efb822f66dbc0adcbbb0
内嵌 assets/base.apk：存在，7548512 字节
APK 签名：v3 验证通过，单签名者
```

再次点击导出后生成 `MyHeroMom (1).apk`，两个导出文件哈希一致。测试前已清空 logcat，连续导出期间管理器 PID 保持不变，没有 `AndroidRuntime` 崩溃记录。
