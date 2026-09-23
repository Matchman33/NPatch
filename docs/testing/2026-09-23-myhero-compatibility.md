# 我的勇者启动兼容性修复

日期：2026-09-23。设备：PLC110，Android 15 / API 35，arm64-v8a。

最终兼容性状态：启动通过，游戏登录失败，不能列为可正常使用的应用。

## 输入与复现

- 原包：`com.r2games.myhero.aligames`，7.6.1，versionCode 203，1,483,668,524 字节。
- 外层：`com.r2games.myhero.aligames.wrapped`，与原版共存。
- 原包 SHA-256：`372b418d479f6fa35e92ddedc5ea39e7f0b9827e49363ce9a875eda6dc408062`。
- 用户生成的旧外层启动即退出，日志为 `Wrapper loader failed to create application class loader`，根因为缺少 `android.support.v4.app.CoreComponentFactory`。
- 原版启动日志同样报告工厂类不存在，但 Android 使用默认工厂继续启动。

可重复执行的冷启动检查：

```powershell
.\scripts\test-wrapper-launch.ps1 -Serial 192.168.101.65:38527 `
  -Package com.r2games.myhero.aligames.wrapped `
  -Activity com.r2games.myhero.aligames.PrivacyActivity `
  -Seconds 10 -OutputDirectory out\wrapper\myhero\launch
```

脚本只读取本轮设备时间之后的日志，并对进程退出或目标包崩溃返回非零状态；存活不等同于业务功能通过，需要检查界面。

## 修复内容

1. 原组件工厂不存在、不可访问或无法实例化时，匹配 Android `LoadedApk.createAppFactory` 的有限回退规则。工厂代码自身异常不被吞掉。
2. 原代码会读取自身 APK。只加载内嵌 DEX，而把代码路径指向只包含加载器 DEX 的外层，会导致网易 `libnesec.so` 初始化时 SIGSEGV。进程内 `sourceDir` 与 `getPackageCodePath()` 现指向校验后的原包缓存；外层保留原始 DEX 字节供资源 APK 自读，加载器使用首个未占用的 `classesN.dex`。系统 `DelegateLastClassLoader` 优先从内嵌原包加载应用类，避免外层副本抢先加载。
3. RN SDK 按当前包名查找布局，原资源表包名导致查询返回 0，并在 `RNSdkInitDialog.initView` 抛出 `Resources$NotFoundException`。外层资源表包名现同步为外层包名，资源 ID 与内容不变；运行时资源路径保持外层。
4. 资源和 assets 按原压缩格式复制，避免将大型游戏资源全部解压再压缩，同时保留未压缩资源的文件描述符访问能力。移除 apkzlib 合并流程中一次读取后未使用的整项内存分配。生成日志增加阶段和耗时。

原始 `assets/base.apk`、原包 DEX 与 SO 字节未修改。没有添加 Gadget、LSP 模块或 PackageManager 签名伪装。

## 验证

- 5 项加载器测试、8 项封装测试、3 项资源表测试、3 项现有存储测试通过，共 19 项。
- 管理器 Release、CLI 构建通过，管理器与加载器 lint 均为 0 错误。
- 缺失工厂和未压缩资源测试在修复前失败、修复后通过。
- 自有样例的自身代码路径、动态资源查询分别在修复前返回 false；最终真机结果均为 true，Application、Provider、Service、Receiver、原工厂、布局、自定义 View、SO 加载检查均通过。
- 样例检查类加载器的实际路径包含内嵌原包路径，验证应用代码没有从外层 DEX 副本抢先加载。
- 游戏最终产物签名验证通过，内嵌原包 SHA-256 与输入一致；产物 3,182,410,981 字节。
- 已覆盖安装更新，保留原版与外层数据；最终外层先到达并显示“魔力数娱隐私保护提示”及“同意并继续”按钮，首次复测 10 秒内无崩溃。后续观察到游戏主界面；第二次冷启动等待 20 秒无崩溃，界面显示“点击登录”。
- `dumpsys activity` 确认当前前台是 `com.r2games.myhero.aligames.wrapped/com.r2games.myhero.aligames.MainActivity`。
- 管理器已更新到最新构建。助手未点击隐私确认或输入账号；观察期间渠道 SDK 自动登录日志报告成功，但未点击游戏登录按钮、进入角色场景，也未验证支付及完整的服务端校验流程。

以上为启动验收时的状态。用户随后报告点击登录被拒绝，下方记录追加的登录排查。

## 登录排查

用户点击登录后出现“支持正版游戏，维护健康网络”。随后通过 ADB 点击同一登录按钮并抓取目标进程日志，观察到：

- 渠道登录进入 `Sdk$1.onLoginSucc` / `verifyAliGamesLogin`，随后游戏记录 `third_login_fail`。
- 失败原因原文：`支持正版游戏，维护健康网络8`。不能仅根据末尾的 `8` 将其解释为某个已知错误码。
- 同机原版回到角色场景；外层仍停留在游戏登录页。
- 日志还包含 Walle `ChannelReader.getMap` 的 `JSONException: Value c!! ... cannot be converted to JSONObject`。直接检查原 APK 的 signing block，`0x71777777` 条目的原始载荷本来就是 `c!!=...` / `encryptType!!=...` 格式，因此不能直接认定此异常是封装丢失渠道信息引入的。
- 原包和外层的签名都通过 apksigner 校验，但证书不同：原包 SHA-256 为 `c74bcef49a25da9fcfef5a6f721a58f62f19deef39ad1b9ce15be32e21445fe8`，外层为 `08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`。

现有证据将问题定位到游戏/渠道登录校验阶段，尚不能区分包名、签名、其他完整性校验或登录参数兼容问题；没有取得足以解释该提示的具体校验返回细节。完整内嵌原 APK 不等于继承原包的系统安装身份。此次没有增加签名伪装、修改游戏校验或把提示隐藏后当作成功。登录问题仍未解决。

追加本地证据：`login-click.log`、`login-after.log`、`original-login.png`。原始日志可能包含 SDK 账号及设备标识，仅保存在忽略提交的本地目录，不写入本记录。

## 耗时与边界

同一输入在电脑端旧版 CLI 总耗时 118.6 秒；首次压缩优化后为 49.2 秒，最终版本封装日志为 42 秒。优化过程中的手机端完整生成为 81 秒，包含签名和原包一致性校验；该手机计时发生在最终 DEX 兼容修正之前，不能当作最终版严格性能基准。以上是单次样本，测试期间还有构建或无线 ADB 活动。

输出仍同时包含完整原 APK 和外层运行所需副本，约 3.18 GB；未实现 NestedZip 去重。首次提取、校验及无线 ADB 安装仍需要时间。Android 9 等其他系统版本、其他厂商以及后续登录和游戏流程尚未验证。

这次发现的可预防问题已经加入工厂回退、原始 DEX 保留、资源存储方式、资源表命名空间和真机自读路径测试。普通样例启动成功不能替代上述兼容行为，也不能代表任意加固游戏均可运行。

## 本地证据

`out/wrapper/myhero/` 为忽略提交的本地目录，包含：

- `before/`：原始组件工厂闪退。
- `after/`：工厂修复后的原生崩溃。
- `paths-after/`：资源 ID 为 0 的异常。
- `dex-launch/`、`confirmed-launch/`：最终启动日志。
- `smoke-dex-launch/`：最终样例 PASS 日志。
- `manager-81s.png`：手机端生成耗时。
- `game-sdk-login.png`：观察到渠道 SDK 自动登录提示。
- `game-current.png`、`foreground.txt`：游戏“点击登录”页面及外层前台包名。
- `dex-fixed/original.apk`：最终游戏外层产物。
