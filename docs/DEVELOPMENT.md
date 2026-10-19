# 开发与维护说明

面向接手维护的人。用户安装说明看官方模块仓库的 README。

## 项目结构

```
app/src/main/java/com/quietmap/app/
├── QuietMapModule.java        模块入口（libxposed API 102，单入口类）
├── Feature.java               功能清单（key = 开关键 = 日志 featureId）
├── Config.java                目标进程侧读 remote prefs（失败回退编译期默认值）
├── ConfigStore.java           模块 App 侧写 remote prefs（写不进回退本地并 Log.w）
├── HookState.java             目标进程内诊断状态（registered / hit / skip）
├── ServiceBridge.java         XposedService 绑定（丢占位 binder + SendBinder 补投）
├── ReportProvider.java        诊断回传 ContentProvider（高德进程 → 模块 App）
├── MainActivity.java          设置入口（Material 3，点标题弹关于）
├── SettingsFragment.java      设置页（状态卡 / 十二开关 / 诊断卡 / 页脚）
├── MaterialSwitchPreference.java
├── dexkit/
│   ├── Anchors.java           混淆类锚点定义（"不发送请求"）
│   └── DexKitLocator.java     运行时字符串锚定定位（抗混淆改名，失败 fail-open）
└── hooks/
    ├── HookKit.java           定位与 hook 的公共封装（黑名单、fail-open、匿名 Hooker）
    ├── Hooks.java             按开关注册 hook
    ├── Report.java            命中回传（每 3 秒快照上报）
    └── SplashGateHooks / SplashRequestHooks / SplashShakeHooks /
        BgMsgFetchHooks / BgAwakenReportHooks / BgAfcTaokeHooks /
        BgUtdidUploadHooks / H5InstallApkHooks / PopupUpgradeHooks /
        HomeBannerHooks / HomeOperateBarHooks / HomeMsgFilterHooks
app/src/main/resources/META-INF/xposed/   模块声明三件套
app/src/test/java/com/quietmap/app/        纯 JVM 单测（Feature 映射 / 开关矩阵）
tools/check-mounts.sh                     挂载点体检（dexsql）
```

## 构建

工具链：AGP 8.12.2 + Gradle wrapper 8.13 + JDK 21 + compileSdk 36 / minSdk 29 / targetSdk 36，
Java 17 源码级别。`GRADLE_USER_HOME=D:\gradle-home`（依赖已缓存）。

```bash
cd /d/quietmap
./gradlew assembleDebug assembleRelease
```

两个构建都必须通过。release 产物是 `app/build/outputs/apk/release/quietmap-<versionName>.apk`
（R8 minify + shrinkResources 开启）。签名读仓库根目录 `keystore.properties`（不入库）：
没有该文件时 release 显式借用 debug 证书——可以安装，但不算正式签名，**不要发布**。

注意：本机 `D:\Android\Sdk\platforms` 里 `android-37.0` 这种目录名 AGP 解析不了，
compileSdk 只能用 36（已实测 36 可用；需要 37 时先把安装目录改名成 `android-37`）。

## 单测

纯 JVM JUnit，不碰框架 API 与设备：

```bash
./gradlew test
```

覆盖两块：`FeatureTest`（十二个开关 key ↔ 编译期默认值映射，key 一旦改动会断掉用户已存的
开关状态，测试锁死它）、`HookSwitchMatrixTest`（十二个钩子开关矩阵：每开关独立、全开、全关，
含批 2 三个开屏键与批 3 三个首页键的独立项）。

## 挂载点体检

高德升级后先跑体检再谈适配（Git Bash）：

```bash
bash tools/check-mounts.sh                      # 默认 E:/download/base(4).apk
bash tools/check-mounts.sh "E:\download\base(5).apk"
```

首次会为 APK 建 DexSQL 缓存（约 40 秒，写 `tools/.dexsql-cache/`，不碰 APK 目录）。
每个挂载点输出三态：

- `OK` — 类和方法都在，完整签名没变
- `SIG` — 类在但方法签名变了（改名 / 参数变化）
- `GONE` — 类没了（混淆名变了）

混淆类挂载点（`Lze5#attemptRequestData`）额外按方法体字符串锚点复核：签名在但
`"不发送请求"` 锚点串丢了也判 `SIG`（运行时 DexKit 会定位失败），输出带 `[锚点OK]` 标记。
末尾汇总 OK/SIG/GONE 计数（批 3 后共 30 项）。SIG/GONE 的项要先用 dexsql 重新定位：

```bash
E:/tools/dexsql/dexsql.exe query --input <apk> \
  --sql "SELECT class_descriptor,name,descriptor FROM methods WHERE ..."
```

重新定位时优先找非混淆锚点（Activity、SPI 接口、字符串常量），再顺着调用链往下找，
不要凭类名相似下结论。混淆类先用 `method_strings` 按方法体特征串反查（如
`WHERE value LIKE '%不发送请求%'`），拿到类名/方法名后同步 `hooks/` 下对应类的常量
（`SplashRequestHooks.ATTEMPT_CLASS_NAMES`）与 `tools/check-mounts.sh` 的挂载点清单
（完整签名与 dexsql 的 `methods.descriptor` 列同构）。

## 稳定性规范（强制）

1. **Hooker 一律匿名内部类**，不用 lambda——规避 bootclasspath desugar 问题。
2. **intercept 方法体整段 try/catch(Throwable)**，catch 记日志后 `chain.proceed()`
   放行原逻辑（fail-open）。`chain.proceed()` 任何路径至多一次：抑制类钩子命中路径
   0 次（不调原方法，这正是钩子目的），异常回退路径恰好 1 次；禁止写成
   `try { return proceed(x); } catch { return proceed(); }` 这种会执行两遍的形状。
3. **hook 三件套**：`module.hook(...).setId(稳定 id).setExceptionMode(PROTECTIVE).intercept(...)`，
   id 用 `<featureId>#<label>`，升级后保持稳定便于比对日志。
4. **框架类黑名单**：目标类名以 `android.` / `java.` / `dalvik.` / `androidx.` / `kotlin.`
   开头则拒绝 hook（`HookKit.isFrameworkClass`，findClass 与 install 双重检查）。
5. **fail-open**：定位失败只记 `<featureId> skip: ...` 跳过该项，绝不向外抛；
   install 整段还要再包一层 try/catch(Throwable)——框架只兜 `RuntimeException`，
   `HookFailedError` 继承 `Error`，漏出去会带崩高德进程。
6. **同一方法只挂一个 hook**；多个挂载点分属不同方法（如 sendAwakenPage 的两个重载）不在此列。
7. **开关只认 remote prefs**：写进模块自己的 MODE_PRIVATE 高德进程读不到；
   写不进 remote 时 Log.w 回退本地，UI 同步置灰。

诊断日志 tag 固定 `QuietMap`（`module.log`），格式：
`<featureId> registered <label>` / `hit <detail>` / `skip: class not found <name>` /
`hook failed <label>`。命中经 `hooks/Report` 每 3 秒回传给 `ReportProvider`
（authority `io.github.yigenhuobah.quietmap.qmreport`，键长 128/256、条数 800 上限），
设置页诊断卡展示各钩子 registered / hit N / skip。

## 发布流程

模块同时发布到两个仓库，缺一个用户就收不到更新：

| 仓库 | 作用 | tag 格式 |
|---|---|---|
| `yigenhuobah/quietmap` | 源码主仓库，Release 面向直接下载 | `v0.1.0` |
| `Xposed-Modules-Repo/io.github.yigenhuobah.quietmap` | 官方模块仓库，LSPosed 管理器从这里读 | `1-0.1.0`（`<versionCode>-<versionName>`） |

### 1. 构建与验证

```bash
# 改 app/build.gradle 的 versionCode / versionName
cd /d/quietmap
./gradlew test assembleDebug assembleRelease
bash tools/check-mounts.sh
```

单测、双构建、挂载点体检全过才能发。release 产物约 2–3 MB（R8 开启）。
真机验证：冷启动高德，logcat 过滤 `QuietMap` 应见各钩子 `registered ...`、无
`hook failed` / 大面积 `skip`，高德不崩溃；再逐项触发一次（亮屏、升级弹窗等）确认
`hit ...` 有日志、设置页诊断卡有计数。

### 2. 主仓库

```bash
git add -A && git commit -m "v0.1.0：<一句话>"
git tag v0.1.0 && git push origin main --tags
gh release create v0.1.0 app/build/outputs/apk/release/quietmap-0.1.0.apk \
  --title "0.1.0" --notes "<更新说明>"
```

### 3. 官方模块仓库

```bash
cd <official-repo 克隆目录>
# 换入新 APK；SUMMARY 和 README.md 同步主仓库的用户说明
cp /d/quietmap/app/build/outputs/apk/release/quietmap-0.1.0.apk .
git add -A && git commit -m "0.1.0"
git tag 1-0.1.0 && git push origin main --tags
gh release create 1-0.1.0 quietmap-0.1.0.apk --title "0.1.0" --notes "<更新说明>"
```

注意事项（与静盒一致）：

- **tag 必须是 `<versionCode>-<versionName>`**（如 `1-0.1.0`），解析失败模块不会出现在索引里。
- 官方仓库里只需要 `SUMMARY`、`README.md` 和 release 资产，不要推源码。
- 仓库 Description 只能网页设置；API 对 `maintain` 角色返回 404。
- 发版只需 `maintain` 权限；加协作者需要 admin，走 submission issue。
- 发布后 5–60 分钟官方索引重建，自查：
  `curl -s https://modules.lsposed.org/modules.json | grep -o 'io.github.yigenhuobah.quietmap'`

### 4. 收尾

更新 CHANGELOG 与兼容版本说明。

## 兼容性声明

仅保证高德 17.01.0.2012，其它版本尽力而为。挂载点绝大多数是非混淆类（`com.autonavi.*`
业务包 / 淘宝、支付宝 SDK 包），升级后大概率仍在，但签名可能变——先跑
`tools/check-mounts.sh` 再说。混淆挂载点有两个：`Lze5#attemptRequestData` 由 DexKit
字符串锚定兜底；批 3 的 `Llt1#IDeliveryDisplay.start`（IDeliveryDisplay 实现类）
目前按类名直挂，升级后若类名变化会记 skip（fail-open，不影响其余钩子），
重新定位方法是用 dexsql `class_interfaces` 按接口
`Lcom/autonavi/minimap/bundle/msgbox/mainmap/IDeliveryDisplay;` 反查新实现类。
锚点失效时 check-mounts 会判 SIG 并提示换锚。

## GPL 红线

本项目为 MIT。**零参考 MapAdKiller / MapClean 的代码与文档**（其许可与本项目不兼容）：
没有下载、打开或摘抄它们的任何内容。所有 hook 目标签名只来自两处——任务清单的调研结论
与 `E:/tools/dexsql/dexsql.exe` 对目标 APK 的实测；实测不一致时一律以 dexsql 为准并在
报告注明。同理，调试中也不要把它们的产物放进本仓库。

## 变更记录

### 批 3（v0.3.0）首页营销三件套 + 红点体系

三个新开关（默认全开），共 12 个挂载点（check-mounts 总数 18 → 30）：

| 开关 | 挂载点 | 动作 |
|---|---|---|
| `home_banner` | `BannerRequestHolder#sendList` 2 参 + 3 参重载 | 不调用（Banner 拉取断源） |
| `home_banner` | `BannerManager#b(String,ZZ,OnLoadBannerListener)` | 不调用 |
| `home_operate_bar` | `OperateActivityMapWidget#refreshSplashBar(...)` | 不调用（运营活动条） |
| `home_operate_bar` | `MsgTipMapWidget#showTips(AmapMessage,int)` | return `false`（地图消息气泡） |
| `home_msg_filter` | `MessageBoxManager#getMessages` **三个重载** | 不调用（消息/红点拉取总入口） |
| `home_msg_filter` | `MessageBoxManager#retrieveRemoteMessages(Set,String)` | 不调用（远程营销消息断源） |
| `home_msg_filter` | `Llt1;->start(Activity,AmapMessage)`（IDeliveryDisplay 实现类） | 不调用（主图投放） |
| `home_msg_filter` | `AjxModuleBadge#updateAppBadge(String,int)` | 不调用（角标红点更新） |
| `home_msg_filter` | `AjxModuleBadge#setBadgeDisplay(String,boolean)` | 强制 `false`（角标显示广播不发出） |

dexsql 复核与任务清单的出入（一律以 dexsql 为准）：

1. `MessageBoxManager#getMessages` 实测是**三个**重载（清单只写 1 个）：
   `(GetMessageListener,Z,xf2)`、`(GetMessageListener,Z,Z,xf2)`、
   `(GetMessageListener,Z,Z,xf2,String)`。三条全挂。外部触发入口只有
   `ModuleMessageBox#hasNewMessage`（AJX 桥）与 `fetchMessageFromMainMap`；
   后者两个调用点都传 0，实际早退不拉取。
2. `IDeliveryDisplay` 是纯接口（`access_flags=17`），hook 接口方法无效
   （ART 走 itable 分派到实现类）。dexsql `class_interfaces` 查到实现类
   `Llt1;`，`AMapHomeMsgManager$b$a;->run()V` smali 里 `new-instance Llt1;`
   后 `invoke-interface IDeliveryDisplay;->start` —— 必须挂 `Llt1;->start`。
3. `AbstractModuleBadge` 是抽象类（`access_flags=1025`），
   `updateAppBadge`/`setBadgeDisplay` 的实体实现都在唯一子类
   `com.amap.bundle.badgesystem.ajxmodule.AjxModuleBadge`（smali 实测），
   故挂子类而不是抽象类。
4. `BannerManager#b` 与调研清单一致；`sendList` 2/3 参重载均存在。

`home_msg_filter` 的取舍（白名单式，非一刀切）：

- 本批取**数据层断源**（不拉远程消息），不按 `isAd` 逐条过滤：`getMessages`
  是拉取总入口，逐条过滤需要 hook 回调并改写
  `GetMessageListener.onFinish(List,List,boolean,boolean)` 的列表，复杂度高
  且回调可能已触发 UI 更新。`AmapMessage.isAd` 实测是 String 字段，
  `refreshSplashBar`/`showTips` 均以 `get` 读取（method_field_accesses 实证）。
- dexsql 检索 `AmapMessage` 全部 `TOKEN_*` 常量（`TOKEN_UPDATE_APP`、
  `TOKEN_TAOBAO_LOGIN`、`TOKEN_TRAVEL`、`TOKEN_REAL3D`、
  `TOKEN_CLOUD_SYNC_DIALOG`、`TOKEN_OFFLINE_DOWN_CITY`、
  `TOKEN_UPDATE_OFFLINE_MAP`、`TOKEN_DOWNLOAD_SEAR_MAP`），
  **未发现 `TOKEN_SYSTEM*` 之类的系统通知 token**。msgbox 承载的是运营/活动/
  升级/离线地图等业务消息，系统通知走 NotificationManager，不在本钩子影响面内。
- 本地已缓存消息走 DB（`getAllLocalMessages` / `retrieveLocalMessages`），
  本批只挂 `getMessages` + `retrieveRemoteMessages`，**不碰 `getAllLocalMessages`**，
  因此历史本地消息仍可读。若用户发现消息列表不刷新/为空，关掉
  `home_msg_filter` 即可完全回退。

模拟器实测（quietmap-test / Android x86_64 + berberis，高德 17.01.0.2012）：

- 冷启动 3 次（含一次模块重装后重新 enable + 加 scope）：每进程 30 个挂载点
  全部 `registered`（主进程 30 条），`skip: ` 计数 0；三键日志齐全。
- `home_msg_filter hit AjxModuleBadge#updateAppBadge` 实测命中（多次），
  证明角标红点链路被拦截；其余首页钩子在本环境无营销数据下发（vanilla
  对照同样无 Banner/运营条），`registered` 待命中观察。
- 首页功能正常（搜索框 / 金刚区 / tab / 地图渲染）；无高德 Java FATAL。
  原生 SIGSEGV 仍为已知 `XcdnEngine` 问题（见下节），与批 1/2 一致。
- 截图与日志：`E:\tmp-amap-hook\batch3-*.png`、`batch3-final-session-logcat.txt`。

### 批 2（v0.2.0）开屏一条龙

三个新开关（默认全开），共 7 个挂载点：

| 开关 | 挂载点 | 动作 |
|---|---|---|
| `splash_gate` | `BootBizDataPreloaderImpl#canShowSplash()` | return `java.lang.Boolean.FALSE`（走官方 `SplashFinishReason.NO_SPLASH` 分支） |
| `splash_request` | `SplashScreenServiceImpl#tryShowSplashView(I,String)` | 不调用（展示层兜底，skipVoid） |
| `splash_request` | `AlimamaRequestHolder#sendSplashScreen` 两个重载 | 不调用 |
| `splash_request` | `Lze5;->attemptRequestData(IZ)V`（混淆类） | 不调用；DexKit 按方法体 `"不发送请求"` 锚定，兜底类名列表 `["ze5"]` |
| `splash_shake` | `SplashButtonInfo#getShakeGuide/getShakeTip` | return `""` |
| `splash_shake` | `SplashButtonInfo#isSpeedStrict()` | return `false` |

实现要点：

- `canShowSplash` 返回类型是 **`java.lang.Boolean` 对象**，绝不能返回 null（调用方会拆箱
  NPE），一律返回装箱常量 `Boolean.FALSE`（新增 `HookKit.constValue` Hooker）。
- `Lze5` 混淆类锚定参考 cloudmusictool 的 dexkit 包模式，按 2.3.0 实测 API 重写：
  `DexKitLocator.findMethod(cl, null, "attemptRequestData", void.class, {int, boolean}, "不发送请求")`。
  实测约 1.4s 完成锚定；`libdexkit.so` 从模块 `nativeLibraryDir` 按绝对路径加载
  （`useLegacyPackaging = true`），失败即回退类名列表，再失败记 skip（fail-open）。
- 锚点消歧：`"不发送请求"` 同时出现在 `Lye5;->run()V`，故第二次全锚点查询必须用
  返回类型 `void` + 参数类型 `(int, boolean)` 强校验后才接受候选。

dexsql 与调研清单的两处出入（一律以 dexsql 为准）：

1. `sendSplashScreen` 3 参重载的 falcon 回调基类是 `com.autonavi.minimap.falcon.base.a`，
   清单写的 `com.amap.bundle.falcon.base.a` 在该 APK 里不存在（不 hook 不会崩，但会
   findClass 失败记 skip）。
2. `attemptRequestData` 的 Java 类名是默认包 `ze5`（dex 描述符 `Lze5;`），
   `Class.forName("ze5", false, cl)` 可直接命中。

### 开屏红线（批 2 起强制执行）

- 只做「不调用 / 返回常量」；**禁止 hook `com.autonavi.minimap.g`（g.o 318 指令大方法）
  和 `SplashFrequencyController.a/b`**（语义未实测，误 hook 会每次弹开屏）。
- 一律走 `skipVoid` / 常量 Hooker 模式（PROTECTIVE、匿名类、intercept 整段
  try/catch(Throwable)、proceed 至多一次、框架类黑名单、fail-open）。
- 同一方法只挂一个 hook。

### 已知环境问题（非本模块缺陷）

模拟器（x86_64 + berberis 翻译 + swiftshader）上高德 17.01.0.2012 在启动约 10-40 秒后
必报一次 `ADCrash_so: Native crash detected!!! capture signal:11`，崩溃栈位于高德自己的
`XcdnEngine`（`flat_models.cpp`）与 `SecurityGuardMainPlugin` 原生插件，**无 QuietMap /
LSPosed 帧**。已用两组对照证明与模块无关：

- 卸载模块、纯原版高德同样崩（`batch2-ctrlC-logcat.txt`，`batch2-vanilla3-logcat.txt`）；
- 关掉三个开屏键只留批 1 钩子，同样崩（`batch2-F1/F1b-logcat.txt`）。

首页功能（搜索框、金刚区、tab）实测正常，崩溃发生在后台原生线程，不影响开屏去广告结论。
复测或出正式版前建议换 arm64 镜像（原生跑，无 berberis 翻译层）再确认一次。
