# 发布可靠性本地验证

## 范围

本轮仅使用本地 JVM、Gradle、Android SDK 工具与固定 APK 母版。未连接、安装或操作任何真机，未推送远端或发布 Release。

## 验证内容

结果：49 项 JVM 测试通过；管理器 Release、加载器 Debug、母版 Debug lint 均无错误（保留既有警告）；管理器本地 Release 与 CLI 构建通过。工作流 YAML 和其中 5 段 shell 脚本的语法检查通过，尚未运行远端 CI。

- 活跃工作空间锁：另一个窗口的清理不删除仍在使用的输入；释放锁后的会话可回收。
- 清理边界：拒绝删除当前会话外的文件；无法获取锁时保留目录并报告警告。
- 打包取消：取消嵌入后不发布 APK，任务中间目录被回收；开始跟踪前取消也会关闭输入流。
- 空间估算：包含解压后的原生库，并拒绝溢出的大小计算。
- 原包缓存：错误摘要不发布，损坏缓存可修复，未使用的旧摘要文件可回收，活跃代际不删除。
- 正式签名：缺少发布密钥时 verifyReleaseSigning 失败；本地验证显式使用 allowDebugSigning。
- 构建：管理器与 CLI 产物包含版本信息及 SHA-256；固定母版原名/改名封装用于本地签名与内嵌原包一致性检查。

本地验证包版本为 `1.0.7-local (614)`，使用本机 Debug 证书；APK 签名和产物摘要验证通过。
原名与改名的封装 APK 均通过 apksigner 验证，`assets/base.apk` 与固定母版摘要相同，各自输出目录只剩最终 APK。
原始封装证据目录为 `out/reliability-smoke-e94aa94fbf184669a82ad4c9ae9a5a1a/`，不纳入 Git。

复验命令：

```powershell
.\gradlew.bat -PstandaloneWrapper=true -PallowDebugSigning=true `
  :wrapper-manager:testDebugUnitTest :wrapper-patch:test `
  :patch-loader:testDebugUnitTest :wrapper-manager:lintRelease `
  :patch-loader:lintDebug :wrapper-smoke:lintDebug `
  :wrapper-manager:collectReleaseArtifacts
```

## 摘要 I/O 测量

运行 `java scripts/OriginCopyBenchmark.java 128`，使用固定随机内容、64 KiB 缓冲区、文件同步和交替执行顺序。两条路径的摘要一致。

| 轮次 | 分开复制与读取校验 | 复制时同步计算摘要 |
| --- | ---: | ---: |
| 0（含预热） | 217 ms | 194 ms |
| 1 | 268 ms | 290 ms |
| 2 | 258 ms | 209 ms |
| 3 | 258 ms | 212 ms |

读取量由 256 MiB 降至 128 MiB。耗时受电脑文件缓存和并行构建影响，此结果不代表手机启动提升比例。缓存命中仍全量检查摘要，未用文件名或时间戳跳过完整性校验。

## 尚未验证

- 前台服务在实际 ROM 上的后台存活、通知权限和超时行为。
- 真机取消、缓存被系统回收及应用升级后的多进程代际回收。
- GitHub Actions 在远端 Runner 的完整执行和正式密钥签名。

JVM 测试不能代替这些设备与远端验证。签名库和 ZIP 库部分阻塞步骤只在阶段边界响应取消；文档提供方可能保留取消导出的部分文件。
