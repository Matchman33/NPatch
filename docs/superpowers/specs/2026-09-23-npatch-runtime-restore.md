# 恢复 NPatch 运行体系

状态：用户明确要求使用原 NPatch 体系，默认保留原包名，代码和资源配套加载。本设计替代此前的独立 WrapperComponentFactory 和 Pine 方案。继续只做本地测试，不连接 ADB。

## 运行与打包

直接构建并使用仓库原有的 meta-loader、patch-loader 和 core，启动链为 LSPAppComponentFactoryStub → libnpatch → LSPApplication → Vector/LSPosed → LSPlant。wrapper-loader 仅收集这些构建产物，不含另一套运行时代码。

简化管理器和 CLI 默认保留原包名。Manifest 中的原权限、Provider authority、process、taskAffinity 在同包名模式下保持原值，组件类名规范化但不改业务身份；资源表保持原字节。显式指定新包名仍可生成，但提示动态资源查询兼容风险，不再改写 resources.arsc。

保留完整原包于 assets/base.apk。原 NPatch 的 OriginApkHelper 同时识别旧 assets/npatch/origin.apk 布局。独立封装模式传入 SHA-256，采用锁、只读临时文件、摘要校验、原子发布；校验失败不发布缓存，损坏缓存可重建。

PatchConfig 增加 standalone 和 embeddedApkSha256。standalone 强制从原包缓存创建 LoadedApk，代码、资源路径配套，保留原组件工厂并使用原框架的缺失工厂回退。旧 NPatch 包没有新字段时保持原分支。

框架初始化保留，模块发现、管理器通信和模块加载关闭。原签名兼容开关映射原 NPatch 的等级 0/3，不添加新 Hook 引擎，不改变外层实际签名。

## 安装行为

同包名生成不再被拒绝，也不因设备已装原版而阻止导出。已知签名冲突会阻止安装入口，并提示不能直接覆盖安装；没有自动卸载或数据清除。打开入口要求已安装封装版本，避免误把原版启动当作封装运行成功。

## 构建适配

补齐原生子模块及原项目指定的 NDK/CMake。libxposed API/service 两个源码地址不可用时，core 的两个 Gradle 项目使用官方 Maven API/service/interface 102.0.0；API 对下游公开，以保持原先源码位于 core 模块中的依赖可见性。

此适配修改了 core 子模块内两个 Gradle 文件，保存或提交工程时需要同时保留这些修改。没有改变 core 子模块的固定提交，也没有改为第三方 Hook 实现。

## 验收

本地验证原入口和 Vector/Xposed 类存在、Pine 依赖/资产移除、ARM64/x86_64 原生库可构建、同包名与原资源表保持、NPatch 配置格式及原包 SHA-256、签名、缓存错误处理。设备运行、签名兼容效果和游戏登录仍需后续单独验证。
