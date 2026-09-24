# 重命名包名资源兼容实施计划

日期：2026-09-24

## 目标

修复独立封装重命名包名后 `Resources.getIdentifier()` 返回 `0` 的问题，同时兼容
使用原包名和新包名的动态资源查询，保持 `assets/base.apk` 字节不变。

## 实施顺序

1. 在 `wrapper-patch` 增加资源表行为测试，覆盖包名重写、字节稳定性和非法输入。
2. 实现深模块 `WrapperResources.rewrite(byte[], originalPackage, targetPackage)`。
3. 将资源表重写接入 `WrapperPacker`，增加封装输出行为测试。
4. 在 `patch-loader` 增加名称映射测试，实现深模块
   `ResourcePackageCompat.install(originalPackage, targetPackage)`。
5. 重命名模式下让 `LoadedApk` 的代码路径继续指向原 APK，资源路径指向外层 APK，
   并在原应用创建前安装旧包名兼容映射。
6. 扩展 `wrapper-smoke`，分别检查新包名和原包名动态查询。
7. 运行相关单元测试、完整独立封装构建和 lint。
8. 生成 `example.npatch.renamed`，真机冷启动验证新旧包名查询均通过；再验证未重命名
   样例没有回归。

## 回归信号

修复前已通过以下路径稳定复现：

- 输入：`wrapper-smoke-debug.apk`
- 目标包名：`example.npatch.renamed`
- 设备：PLC110，Android 15 / API 35
- 结果：其余 smoke 检查通过，`namedResource=false`

修复完成的判定是同一输入和设备上新旧包名查询均为 `true`，且未重命名样例继续
完整通过。

## 实施结果

已于 2026-09-24 完成：

- 外层 `resources.arsc` 在重命名模式下定长改写为新包名，资源 ID、表长度和
  `assets/base.apk` 保持不变。
- 重命名模式的代码路径继续指向内部原包缓存，资源路径改为外层 APK。
- 运行时同时兼容新包名和原包名的 `Resources.getIdentifier()` 查询。
- `wrapper-patch`、`patch-loader` 单元测试及 CLI、烟雾 APK、管理器 release 构建通过。
- PLC110（Android 15 / API 35）冷启动验证通过：重命名样例与保留包名样例均输出
  `PASS`，其中重命名样例的 `namedResource=true`、`originalNamedResource=true`。
