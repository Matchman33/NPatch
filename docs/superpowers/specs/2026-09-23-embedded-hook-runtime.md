# 内置 Hook 运行时与签名兼容

状态：此 Pine 方案已被用户撤销。当前实现使用原 NPatch/Vector/LSPlant 体系，以下内容仅保留为历史设计。

用户已确认开始实现内置 Hook 能力，普通设备无需安装 Xposed 或 Root。保留已经验证有效的加载路径、资源命名空间、DEX 自读和工厂回退修复。

## 选择

- 完整复用 NPatch/Vector 运行时：能力广，但当前依赖缺失且耦合模块、服务和原生初始化。
- 使用内置 Pine 0.3.0 Java Hook 引擎：本轮采用，可随加载器携带原生库，实现内部签名兼容，无外部模块管理。
- 仅代理 PackageManager Binder：体积小，但对已有缓存和其他调用入口的覆盖较弱。

首版只在用户开启签名兼容时初始化 Pine。管理器提供开关，CLI 使用 `--signature-compat`，默认关闭。开关关闭时不携带 Hook 原生库、不安装 Hook；DEX 包含运行时 Java 类但不初始化它们。

## 数据与启动

`assets/base.apk` 保持原字节。原生库放在 `assets/wrapper/runtime/<abi>/libpine.so`，配置记录运行时版本和库摘要；不覆盖目标应用原生库。按进程 ABI 提取至摘要命名的私有目录，校验后以只读文件加载，版本之间不覆盖。

在原组件工厂、Application 和 Provider 初始化之前，从已校验原包解析原签名、完整 SigningInfo 和历史证书；随后初始化 Hook 并安装签名查询兼容。只改本进程对当前外层包或自身 UID 的签名查询结果，保留包名、UID、路径、权限及其他包的真实信息。返回 PackageInfo 时复制对象，避免修改系统缓存。

覆盖 PackageInfo 的旧 signatures、新 signingInfo，包名/UID 形式的 hasSigningCertificate，以及指向当前已安装外层 APK 的 getPackageArchiveInfo。原证书读取和 Hook 安装失败时报告具体阶段，不假装启用成功。日志仅记录 Hook 安装与首次命中的 API，不输出账号或请求数据。

当前引擎提供 ARM 原生构建；生成前检查输入 ABI，运行时再次检查实际进程 ABI。版本范围限定 Android 9–15，原生预编译库为 4 KB 页对齐，管理器与运行时检查页大小并拒绝 16 KB 页设备。实际兼容性以实测为准。此功能不改变实际 APK 签名，不保证通过直接文件验签、服务端完整性验证或所有游戏校验，不添加 Gadget 或外部模块入口。

## 验证

单元测试覆盖证书匹配、多签名/历史证书规则、非法证书类型、运行时资产与配置、缺失或损坏运行时拒绝及关闭模式。自有样例用与外层不同的证书签名，在 Application 初始化阶段比较查询结果与 APK 原证书；验证新旧查询、UID 查询、错误证书返回 false、其他包不变和重复调用。随后验证多进程入口及游戏的实际 Hook 命中与登录结果。手机上已有原版不卸载、数据不清空。

用户随后明确要求暂时关闭 ADB、只做本地测试。本轮设备步骤改为待验证，不在本轮继续连接或操作手机。

来源：[Pine](https://github.com/canyie/pine)，Maven Central `top.canyie.pine:core:0.3.0`。Pine 使用 Anti 996 License 1.0，发布时保留其许可与归属。
