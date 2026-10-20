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
├── SettingsFragment.java      设置页（状态卡 / 十九开关 / 诊断卡 / 页脚）
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
        HomeBannerHooks / HomeOperateBarHooks / HomeMsgFilterHooks /
        SearchHotwordHooks / H5AdLogHooks / FloatLayerHooks / MsgImageTextHooks /
        PopupCleanHooks / TrackSuppressHooks / ShakeH5Hooks
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
缺少该文件时 `assembleRelease` / `bundleRelease` 在任务执行阶段直接抛 `GradleException`，
不会产出用 debug 证书签名的“正式版”。

构建注意：`check*AarMetadata` 仅豁免 libxposed `service` / `interface` 两个坐标
（service 声明 minCompileSdk 37，而本机只能 compileSdk 36；本模块不使用 37 新 API）。
若未来改用 compileSdk 37，可删除 `app/build.gradle` 里的豁免段。

注意：本机 `D:\Android\Sdk\platforms` 里 `android-37.0` 这种目录名 AGP 解析不了，
compileSdk 只能用 36（已实测 36 可用；需要 37 时先把安装目录改名成 `android-37`）。

## 单测

纯 JVM JUnit，不碰框架 API 与设备：

```bash
./gradlew test
```

覆盖两块：`FeatureTest`（十九个开关 key ↔ 编译期默认值映射，key 一旦改动会断掉用户已存的
开关状态，测试锁死它）、`HookSwitchMatrixTest`（十九个钩子开关矩阵：每开关独立、全开、全关，
含批 2 三个开屏键、批 3 三个首页键、批 4 四个搜索/推广键与批 5 三个弹窗/统计/摇一摇键的独立项）。

## 挂载点体检

高德升级后先跑体检再谈适配（Git Bash）：

```bash
bash tools/check-mounts.sh                      # 默认 E:/download/base(4).apk
bash tools/check-mounts.sh "E:\download\base(5).apk"
```

首次会为该 APK 建 DexSQL 缓存（约 40 秒，写 `tools/.dexsql-cache/<APK sha256 前 16 位>/`，
不碰 APK 目录；换包自动换目录，不会复用旧包缓存）。查询失败（dexsql 非 0 退出或结果异常）
会直接中止，不会把错误当成 0 行结果而误报 `GONE`。脚本会读取并打印 APK 的 versionName，
与 **17.01.0.2012** 不一致时给出 WARN（继续体检）。每个挂载点输出三态：

- `OK` — 类和方法都在，完整签名没变
- `SIG` — 类在但方法签名变了（改名 / 参数变化）
- `GONE` — 类没了（混淆名变了）

混淆类挂载点（`Lze5#attemptRequestData`）额外按方法体字符串锚点复核：签名在但
`"不发送请求"` 锚点串丢了也判 `SIG`（运行时 DexKit 会定位失败），输出带 `[锚点OK]` 标记。
末尾汇总 OK/SIG/GONE 计数（共 53 项）。SIG/GONE 的项要先用 dexsql 重新定位：

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
| `yigenhuobah/quietmap` | 源码主仓库，Release 面向直接下载 | `v0.5.1` |
| `Xposed-Modules-Repo/io.github.yigenhuobah.quietmap` | 官方模块仓库，LSPosed 管理器从这里读 | `6-0.5.1`（`<versionCode>-<versionName>`） |

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
git add -A && git commit -m "v0.5.1：<一句话>"
git tag v0.5.1 && git push origin main --tags
gh release create v0.5.1 app/build/outputs/apk/release/quietmap-0.5.1.apk \
  --title "0.5.1" --notes "<更新说明>"
```

### 3. 官方模块仓库

```bash
cd <official-repo 克隆目录>
# 换入新 APK；SUMMARY 和 README.md 同步主仓库的用户说明
cp /d/quietmap/app/build/outputs/apk/release/quietmap-0.5.1.apk .
git add -A && git commit -m "0.5.1"
git tag 6-0.5.1 && git push origin main --tags
gh release create 6-0.5.1 quietmap-0.5.1.apk --title "0.5.1" --notes "<更新说明>"
```

注意事项（与静盒一致）：

- **tag 必须是 `<versionCode>-<versionName>`**（如 `6-0.5.1`），解析失败模块不会出现在索引里。
- 官方仓库里只需要 `SUMMARY`、`README.md` 和 release 资产，不要推源码。
- 仓库 Description 只能网页设置；API 对 `maintain` 角色返回 404。
- 发版只需 `maintain` 权限；加协作者需要 admin，走 submission issue。
- 发布后 5–60 分钟官方索引重建，自查：
  `curl -s https://modules.lsposed.org/modules.json | grep -o 'io.github.yigenhuobah.quietmap'`

### 4. 收尾

更新 README 的版本行与覆盖矩阵（仓库没有 CHANGELOG.md），并核对兼容版本说明。

## 兼容性声明

仅保证高德 17.01.0.2012，其它版本尽力而为。挂载点绝大多数是非混淆类（`com.autonavi.*`
业务包 / 淘宝、支付宝 SDK 包），升级后大概率仍在，但签名可能变——先跑
`tools/check-mounts.sh` 再说。混淆挂载点有四个（`Lze5`、`La8`、`Llt1`、`Lcx1`）：`Lze5#attemptRequestData` 与
批 4 的 `La8#run`（图文素材下载任务）由 DexKit 字符串锚定兜底，锚点分别是
`"不发送请求"` 与 `"invalid download item: " + "ImageTextMessageDownloader"`；
批 3 的 `Llt1#IDeliveryDisplay.start`（IDeliveryDisplay 实现类）与批 5 的
`Lcx1#IDialogConflictMgr.addToQueue`（IDialogConflictMgr 实现类）目前按类名直挂，
升级后若类名变化会记 skip（fail-open，不影响其余钩子）。重新定位方法是用 dexsql
`class_interfaces` 按接口反查新实现类：
`Lcom/autonavi/minimap/bundle/msgbox/mainmap/IDeliveryDisplay;` 与
`Lcom/autonavi/bundle/amaphome/api/IDialogConflictMgr;`。
锚点失效时 check-mounts 会判 SIG 并提示换锚。

## GPL 红线

本项目为 MIT。**零参考 MapAdKiller / MapClean 的代码与文档**（其许可与本项目不兼容）：
没有下载、打开或摘抄它们的任何内容。所有 hook 目标签名只来自两处——任务清单的调研结论
与 `E:/tools/dexsql/dexsql.exe` 对目标 APK 的实测；实测不一致时一律以 dexsql 为准并在
报告注明。同理，调试中也不要把它们的产物放进本仓库。

## 变更记录

### 批 5（v0.5.0）全量收尾：弹窗/统计/摇一摇 + push 补挂 + 覆盖矩阵

三个新开关（默认全开）+ `bg_msg_fetch` 补挂，共 **14 个新挂载点**
（check-mounts 总数 38 → 52）：

| 开关 | 挂载点 | 动作 |
|---|---|---|
| `popup_clean` | `UpdateHintConfigDownloader#a()V` | 不调用（升级提示配置不下载） |
| `popup_clean` | `Lcx1#IDialogConflictMgr.addToQueue(DialogConflictInfo)Z` | return `false`（首页弹窗冲突队列不入队） |
| `track_suppress` | `track.b#e(...)V` | 不调用（开屏曝光/点击埋点） |
| `track_suppress` | `track.b#f(...)V` | 不调用（开屏埋点聚合 + 三方监测） |
| `track_suppress` | `SplashLogManager#a(String,String,String,String,Z)V` | 不调用（开屏日志上传） |
| `track_suppress` | `UTTracker#send(Map)V` | 不调用（UT 统计总出口） |
| `track_suppress` | `UTTracker#sendLog(Map,int,List)V` | 不调用（UT 落盘/转发入口） |
| `shake_h5` | `H5ShakePlugin#handleEvent(H5Event,H5BridgeContext)Z` | 仅 `watchShake` 返回 true，其余 action 放行 |
| `shake_h5` | `H5SensorPlugin#registerGyroscopeListener(H5Event)Z` | return `false`（不注册陀螺仪） |
| `shake_h5` | `AMapAppStartShakePoint#onInitialized()V` | 不调用（小程序摇一摇初始化） |
| `shake_h5` | `SensorManagerHelper#onSensorChanged(SensorEvent)V` | 不调用（监听回调第二道保险） |
| `bg_msg_fetch` | `MessageBoxManager#handlePush(String)V` | 不调用（推送消息处理入口） |
| `bg_msg_fetch` | `BackgroundMsgManager#a(IBackgroundMsgFetchListener)V` | 不调用（后台营销消息拉取） |
| `bg_msg_fetch` | `MessageBoxManager#fetchMessageFromMainMap(int,Z,MainMapUIUpdater)V` | 不调用（主图消息拉取包装） |

dexsql 复核（高德 17.01.0.2012，一律以实测为准）：

| 复核项 | 实测结果 |
|---|---|
| `MessageBoxManager.executeAction` | 只有 **1 个**重载 `(AmapMessage)V`，118 opcodes；方法体按 `id` 分支：`appUpdate` / `downloadSeatMap` / `taobaoLogin` / 其它走 `actionUri`；**不读任何 `TOKEN_*` 字段** |
| `MessageBoxManager.executeBtnAction` | 只有 **1 个**重载 `(String)V`，27 opcodes；通用 `Intent.ACTION_VIEW` + `DoNotUseTool.startScheme` |
| `AmapMessage.TOKEN_*` | 实测值：`appUpdate`、`taobaoLogin`、`travel`、`real3d`、`cloud_sync_dialog`、`downloadSeatMap`、`offlineDownCity`、`updateOfflineMap`；`method_field_accesses` 中 **没有任何方法读取这些常量**（只用于构造/比较 id） |
| `handlePush` | `(String)V`，29 opcodes；解析 `"AmapPush-"` 前缀、写 `PUSH_MSG` prefs、post `MessageBoxManager$c` 按 id 拉取 |
| `BackgroundPushServiceImpl` | `a()V` 61、`b()Z` 34、`push()V` 284、`refreshPushMsg()V` 9；内部调用 `BackgroundMsgManager` |
| `BackgroundMsgManager` | `a(IBackgroundMsgFetchListener)V` 30 opcodes，请求 `/ws/msgbox/notice-msgs` + `IBehaviorService.customHit` |
| `fetchMessageFromMainMap` | `(IZMainMapUIUpdater)V` 12 opcodes，直接转 `getMessages(...,"1")` |
| `PushManager` / `PushBindReceiver` | `PushManager#getPushAgent()` 返回 `notification.api.IPushAgent`；`PushBindReceiver#onSuccess/onFailure` 是绑定结果回调；APK 内含小米/华为/荣耀/Heytap/Vivo 推送组件 |
| `track.b.e/f` | `e(...)` 19 参 128 opcodes、`f(...)` 20 参 74 opcodes；构建 `H5LogRequest`、发三方 GET 监测、转 UT 上报 |
| `SplashLogManager.a` | `(String,String,String,String,Z)V` 34 opcodes，纯网络上报 |
| `UTAnalytics` | 没有 `send/track` 方法；只提供 `getDefaultTracker()`。真正的上报入口是 `UTTracker#send(Map)V`（258 opcodes）与 `sendLog(Map,int,List)V`（28 opcodes） |
| `H5ShakePlugin.handleEvent` | 113 opcodes；处理 `"vibrate"` 与 `"watchShake"`，`watchShake` 内 `SensorManager.registerListener` |
| `H5SensorPlugin.registerGyroscopeListener` | 私有方法 66 opcodes；注册陀螺仪/加速度/磁场三个 sensor；调用方忽略返回值 |
| `AMapAppStartShakePoint` | `onInitialized()V` 26 opcodes，`new SensorManagerHelper` 并直接注册；`onAppStart/onAppExit` 只维护 appId→callback 的 HashMap |
| `SensorManagerHelper` | 只有 `onAccuracyChanged` / `onSensorChanged` 两个监听回调，**没有注册方法**（注册在 `onInitialized` 里完成） |
| `IDialogConflictMgr` | 纯接口；唯一实现类 `Lcx1;`（`class_interfaces`：file_id=7, class_idx=10477）；`addToQueue` 61 opcodes |
| `UpdateHintConfigDownloader.a` | `()V` 27 opcodes；方法体含 `update_hint_apk_newest_version` 等配置键 |

`executeAction` 取舍结论：**不 hook**。实测它不读 `TOKEN_*`，只按 message id 分支；
整段拦截会同时废掉 `downloadSeatMap`（离线地图下载）、`taobaoLogin`（账号）、
`appUpdate` 弹窗和所有 `actionUri` 真实消息跳转，无法在不误伤系统/业务动作的前提下
做「只拦营销 token」。营销消息源头已由批 3 `home_msg_filter`（`getMessages` +
`retrieveRemoteMessages` 断源）处理，故本批按红线选择宁可不 hook。

`popup_clean` 的中风险取舍：`Lcx1#addToQueue` 的调用方包括
`GuideVideoViewController#showGuideView`（功能引导）、`SharedPassphraseManager`
（分享口令）、`ModuleAMapHome#presentModalPage`（通用模态页）等，返回 false 会一并
吞掉这些弹窗，可能包括登录失效等重要提示。因此该项默认开启但必须可单独关闭；
关闭 `popup_clean` 后队列行为完全恢复。`UpdateHintConfigDownloader#a` 是独立低风险项。

推送通道取舍：**不 hook `PushManager` / `PushBindReceiver` / `PushAidlService` /
`MiPushActivity`**。它们是系统推送通道（APK manifest 内含小米 `XMPushService`、
华为 `HmsMsgService`、荣耀 `HonorPushMessageService`、Heytap `PushService`、
VivoPush 等），hook 会破坏 token 注册与厂商通道绑定。本批只在上层
`handlePush` / `BackgroundMsgManager#a` 做消息体净化；推送到达也会被丢弃，
但通道注册不受影响。

模拟器实测（quietmap-test / Android 15 x86_64 + berberis，高德 17.01.0.2012）：

- 构建：`./gradlew test assembleDebug assembleRelease` 成功；单测 12 个用例全绿
  （`FeatureTest` 3 + `HookSwitchMatrixTest` 9）；release 产物
  `quietmap-0.5.0.apk` 约 3.6 MB。
- 挂载点体检：`bash tools/check-mounts.sh` 共 **52 项 — OK 52 / SIG 0 / GONE 0**。
- 安装 0.5.0（覆盖 0.4.0）后冷启动 2 次：每个进程 **52 个挂载点全部 registered**
  （主进程与 `:locationservice` 各 52 条），`skip: ` 计数 0，`hook failed` 0，
  无高德 Java FATAL；19 个 featureId 全部出现在设置页诊断卡。
- 新三键：`popup_clean`、`track_suppress`、`shake_h5` 全部 registered；
  `bg_msg_fetch` 补挂的 `handlePush` / `BackgroundMsgManager#a` /
  `fetchMessageFromMainMap` 全部 registered。
- 实测命中：`track_suppress hit UTTracker#send` 257 次、
  `popup_clean hit UpdateHintConfigDownloader#a` 1 次、
  `popup_clean hit Lcx1#IDialogConflictMgr.addToQueue` 1 次（设置页诊断卡）、
  `splash_gate` 6+1 次、`splash_request hit Lze5#attemptRequestData` 2 次、
  `search_hotword` 4 次、`home_msg_filter hit AjxModuleBadge#updateAppBadge` 1 次、
  `bg_utdid_upload` 1 次、`bg_awaken_report` 1 次。
  `track.b.e/f`、`SplashLogManager`、`shake_h5`、`handlePush` /
  `BackgroundMsgManager` 本环境无对应场景，`registered` 待观察（未伪造 hit）。
- 回归：首页搜索框/金刚区/tab 正常；地图瓦片渲染、缩放/平移正常；
  消息相关钩子全部 registered，未出现 Java 异常；搜索页仍为空白（顶部两张白卡），
  与批 4 对照结论一致——模拟器无外网，AJX 资源加载失败，与模块无关。
- 推送通道：logcat 见 `VivoPush.PushClientThread InitCommand` 正常初始化；
  manifest 内小米/华为/荣耀/Heytap 推送组件齐全；未发现 QuietMap 相关推送
  绑定失败。`Failed to find provider info for com.vivo.push.sdk.service.SystemPushConfig`
  是模拟器无 Vivo 系统推送服务导致的环境噪音，与模块无关。
- 原生 SIGSEGV 仍为已知环境问题：本次日志出现 5 次
  `ADCrash_so: Native crash detected!!! capture signal:11`，栈位于高德
  SecurityGuard/XcdnEngine 原生线程，**无 QuietMap / LSPosed 帧**，与批 2-4 一致。
- 截图与日志：`E:\tmp-amap-hook\batch5-settings-1.png`（状态卡）、
  `batch5-settings-popup.png`（弹窗/统计/摇一摇分组）、
  `batch5-settings-2.png` / `batch5-settings-3.png`（19 键诊断卡）、
  `batch5-home.png`（首页）、`batch5-map-zoom-pan.png`（地图缩放/平移）、
  `batch5-search.png`（搜索页空白，无外网环境限制）、
  `batch5-cold1-logcat.txt`、`batch5-cold2-logcat.txt`、`batch5-final-logcat.txt`。

全量覆盖矩阵（与 README 一致）：

| B 报告点位 | 分类 | 批号 / 开关 | 说明 |
|---|---|---|---|
| 1.1 `SplashScreenServiceImpl.tryShowSplashView` | 已覆盖 | 批2 `splash_gate` | 不调用，展示层兜底 |
| 1.2 `BootBizDataPreloader.canShowSplash` | 已覆盖 | 批2 `splash_gate` | 返回 `Boolean.FALSE`，走官方 NO_SPLASH |
| 1.3 `g.o(I,String)` | 有意不做 | — | 318 指令启动关键方法，nop 风险高 |
| 1.4 `SplashFrequencyController.a/b` | 有意不做 | — | 语义未实测，误 hook 会每次弹开屏 |
| 1.5 `Lze5.attemptRequestData` | 已覆盖 | 批2 `splash_request` | 不调用；DexKit 字符串锚定 |
| 1.6 `AlimamaRequestHolder.sendSplashScreen` | 已覆盖 | 批2 `splash_request` | 两个重载全挂 |
| 1.7 `nowHasSplashItemsFromAlimama` | 有意不做 | — | 请求/展示已双断，单独改缓存判断无额外收益 |
| 1.8 倒计时/跳过按钮 | 有意不做 | — | 保留用户主动跳过，避免误伤 |
| 1.9 `ShakeButton` + `SplashButtonInfo` | 已覆盖 | 批2 `splash_shake` | getter 返回空串/false |
| 1.10 `g.clickAd` | 有意不做 | — | 会禁用正常广告点击行为 |
| 1.11 `track.b.e/f` + `SplashLogManager` | 已覆盖 | 批5 `track_suppress` | 不调用 |
| 2.1 `BannerRequestHolder.sendList` | 已覆盖 | 批3 `home_banner` | 2/3 参重载全挂 |
| 2.2 `BannerManager.b` | 已覆盖 | 批3 `home_banner` | 不调用 |
| 2.3 `OperateActivityMapWidget.refreshSplashBar` | 已覆盖 | 批3 `home_operate_bar` | 不调用 |
| 2.4 `MsgTipMapWidget.showTips` | 已覆盖 | 批3 `home_operate_bar` | 返回 false |
| 2.5 `MessageBoxManager.getMessages` / `retrieveRemoteMessages` | 已覆盖 | 批3 `home_msg_filter` | 3 个 getMessages 重载 + 远程拉取全断 |
| 2.5b `fetchMessageFromMainMap` | 已覆盖 | 批5 `bg_msg_fetch` | 不调用 |
| 2.6 `IDeliveryDisplay.start` | 已覆盖 | 批3 `home_msg_filter` | 挂实现类 `Llt1` |
| 2.7 `AjxModuleBadge` 角标 | 已覆盖 | 批3 `home_msg_filter` | 更新 count>0 抑制 / count<=0 放行 + 显示不调用；另挂启动器角标 `BadgeSystemManager#updateAppBadge` |
| 2.8 `AMapHomeMsgManager` | 已覆盖（间接） | 批3 `home_msg_filter` | 入口走 getMessages / IDeliveryDisplay |
| 3.1 `BaseSearchBar.requestPreSetWord` | 已覆盖 | 批4 `search_hotword` | 不调用 |
| 3.2 `PreSetWordManager.e/d` | 已覆盖 | 批4 `search_hotword` | 不调用 |
| 3.3 `ISearchService.sugg` | 有意不做 | — | 会破坏正常联想输入体验 |
| 3.4 `logOnRecommendWordResponse` | 有意不做 | — | 纯曝光上报；保留 sugg 时属正常搜索行为 |
| 3.5 `processSuggestNetWorkData` | 有意不做 | — | 过滤联想列表伤体验 |
| 4.1 `AppUpgradeController.q` | 已覆盖 | 批1 `popup_upgrade` | 返回 false |
| 4.2 `UpdateHintConfigDownloader.a` | 已覆盖 | 批5 `popup_clean` | 不调用 |
| 4.3 `IDialogConflictMgr.addToQueue` | 已覆盖 | 批5 `popup_clean` | 挂实现类 `Lcx1`，返回 false（中风险） |
| 4.4 `executeAction` token 过滤 | 有意不做 | — | 实测不读 `TOKEN_*`，只按 message id 分支，无法安全区分 |
| 4.5 `executeAction` / `executeBtnAction` no-op | 有意不做 | — | 会误伤离线地图、淘宝登录、真实消息跳转；源头已断 |
| 4.6 隐私二次弹窗 | 有意不做 | — | 合规必须 |
| 4.7 权限索取 | 有意不做 | — | 影响定位权限流 |
| 5.1 `AlimamaRequestHolder.sendH5Log` | 已覆盖 | 批4 `h5_ad_log` | 2/3 参重载全挂 |
| 5.2 H5 下载插件 / `installApk` | 部分覆盖 | 批1 `h5_install_apk` | 只拦静默安装，不阻断用户主动下载 |
| 5.3 `FloatingLayerManager.a` | 已覆盖 | 批4 `map_float_layer` | 返回空串，不注册 |
| 5.4 开屏视频/扫光挂件 | 已覆盖（间接） | 批2 `splash_gate`/`splash_request` | 开屏不展示，挂件不出现 |
| 5.5 `ImageTextMessageDownloader` 下载任务 | 已覆盖 | 批4 `msg_image_text` | 挂混淆 Runnable `La8` |
| 6.1 `ze5` / `ye5` 预加载 | 部分覆盖 | 批2 `splash_request` | 只挂 `Lze5#attemptRequestData`；`ye5` 未单独挂载（其调用路径经 Lze5 间接收敛） |
| 6.2 `AfcTkManager.registerTkFlag` | 已覆盖 | 批1 `bg_afc_taoke` | 不调用 |
| 6.3 `UtdidUploadTask` | 已覆盖 | 批1 `bg_utdid_upload` | upload/uploadFromCache/reqServer 全断 |
| 6.4 utdid 生成/读取 | 有意不做 | — | 改返回值可能 NPE 或触发风控 |
| 6.5 `UTAnalytics` 上报 | 已覆盖 | 批5 `track_suppress` | 实际总出口 `UTTracker.send/sendLog` |
| 6.6 `track.b.e/f` + `SplashLogManager` | 已覆盖 | 批5 `track_suppress` | 不调用 |
| 6.7 `BootBizDataPreloader` 其它预载 | 有意不做 | — | 只动 canShowSplash，保留首屏预载 |
| 7.1a 开屏摇一摇 | 已覆盖 | 批2 `splash_shake` | getter 数据层 |
| 7.1b `H5ShakePlugin` / `H5SensorPlugin` | 已覆盖 | 批5 `shake_h5` | 只拦 watchShake，保留 vibrate |
| 7.1c `AMapAppStartShakePoint` / `SensorManagerHelper` | 已覆盖 | 批5 `shake_h5` | onInitialized + onSensorChanged 不调用 |
| 7.2a `AwakenRequestHolder` | 已覆盖 | 批1 `bg_awaken_report` | 两个重载 |
| 7.2b `openWxMiniApp` | 有意不做 | — | 仅开屏点击链路，开屏已断 |
| 7.2c `PushAidlService` / `MiPushActivity` | 有意不做 | — | 系统推送组件，不动推送通道 |
| 7.3a 后台消息拉取 | 已覆盖 | 批1+批5 `bg_msg_fetch` | 亮屏触发/后台拉取/主图拉取全断 |
| 7.3b `MessageBoxManager.handlePush` | 已覆盖 | 批5 `bg_msg_fetch` | 不调用，不影响通道注册 |
| 7.3c `PushManager` / `PushBindReceiver` | 有意不做 | — | 小米/华为/荣耀/Heytap/Vivo 推送通道必需，hook 会破坏 token 注册 |
| 7.4 个性化推荐 | 技术不可行 | — | 云控/动态模板，无本地总开关；已从消息/搜索数据入口近似收敛 |
| AJX 金刚区/结果页推广卡 | 技术不可行 | — | 模板包渲染，dex 只有桥接/数据入口 |
| 画布 / WebView 广告 | 技术不可行 | — | 需 AJX/WebView 逆向，Java 层无稳定挂载点 |

遗留问题与已知风险：

1. `popup_clean` 的 `addToQueue` 是通用弹窗队列，可能吞掉功能引导/分享口令等业务
   弹窗；已实测命中 1 次，默认开、可单关。若用户反馈登录失效等提示消失，先关此项。
2. `track_suppress` 是 UT 统计总出口一刀切，个别依赖上报的「任务/积分」活动可能
   异常；可单独关闭。
3. `shake_h5` 与 `bg_msg_fetch` 的 `handlePush` / `BackgroundMsgManager` 在本模拟器
   没有自然触发场景，只验证到 `registered`；需要在有网/有 H5 页面/有真实推送的
   环境补测 hit。
4. `Lcx1` 是混淆类名，高德升级后可能改名；check-mounts 会判 GONE，按
   `class_interfaces` 反查新实现类即可。
5. 原生 SIGSEGV 为模拟器 + berberis 的已知环境问题，换 arm64 真机复测前不要据此
   判定模块稳定性。

### 批 4（v0.4.0）搜索热词 + H5 广告监测 + 地图浮层 + 图文消息素材

四个新开关（默认全开），共 8 个挂载点（check-mounts 总数 30 → 38）：

| 开关 | 挂载点 | 动作 |
|---|---|---|
| `search_hotword` | `BaseSearchBar#requestPreSetWord(Z,String)` | 不调用（热词请求入口） |
| `search_hotword` | `PreSetWordManager#d(n55)` | 不调用（本地热词计数） |
| `search_hotword` | `PreSetWordManager#e(String,n55)` | 不调用（天擎曝光上报） |
| `search_hotword` | `ModuleSlideContainer#responsePreSetWords(String)` | 不调用（AJX 回填，第二道保险） |
| `h5_ad_log` | `AlimamaRequestHolder#sendH5Log` 2 参 + 3 参重载 | 不调用（H5 点击/曝光监测断源） |
| `map_float_layer` | `FloatingLayerManager#a(String,String,JSONObject,b)` | return `""`（不注册浮层） |
| `msg_image_text` | `La8;->run()V`（混淆下载任务） | 不调用（图文素材下载断源） |

dexsql 复核与任务清单的出入（一律以 dexsql 为准）：

1. `BaseSearchBar#requestPreSetWord` 实测只有 **1 个**重载 `(Z,String)V`，与清单一致。
   方法体把 sceneData 交给 AJX/JS，真正的热词请求在前端发起。
2. `PreSetWordManager` 实测方法：`a(String)Z`（本地限额）、`b()`（单例）、
   `c(JSONObject)String`（构造 sceneData）、`d(n55)V`（本地计数）、
   `e(String,n55)V`（天擎曝光上报）。网络回调类 `$a`、`$2`、`$3` 均存在，
   `$b` 是本地 prefs 持有者；`n55` 是默认包混淆类（描述符 `Ln55;`）。
3. `sendH5Log` 实测 **2 个**重载（清单只写 2 参）：
   `(H5LogRequest,AosResponseCallback)` 与
   `(H5LogRequest,com.autonavi.minimap.falcon.base.a,AosResponseCallback)`，两条全挂。
4. `FloatingLayerManager#a` 签名与清单完全一致；`d()` 单例确认存在但**不 hook**
   （调用点会立即解引用，返回 null 必 NPE）。返回常量取 `""`：调用点
   `floatinglayer/b#f` 把返回值 `JSONObject.put("key", result)` 后 `callJs`，
   `org.json` 的 `put(name, null)` 会删除键，返回 `""` 保持键存在；
   另一个调用点 `AppBackManager$2#a` 完全忽略返回值。
5. `ImageTextMessageDownloader` 实测只有静态 `a(File)Z`（递归删除缓存），
   **不是下载入口**。真正的入口是混淆 Runnable `La8;->run()V`（方法体含
   `ImageTextMessageDownloader`、`start`、`invalid download item: `），
   构造 `DownloadRequest` + `FinishCallback` 后调 `IHttpService.download`。
   与批 2 的 `Lze5` 同样用 DexKit 字符串锚定（`invalid download item: ` +
   `ImageTextMessageDownloader`，void()V 签名强校验），兜底类名 `a8`。
6. `ModuleSlideContainer#responsePreSetWords(String)` 存在（实体子类覆写抽象基类
   `AbstractModuleSlidecontainer` 的同名方法），Java 侧无调用点，由 AJX bridge
   反射调用；作为热词第二道保险一起断。

红线：本批**不动** `ISearchService.sugg`（联想词），搜索输入体验保持原样；
不动 `g.o` / `SplashFrequencyController` / AJX 模板类。

模拟器实测（quietmap-test / Android 15 x86_64 + berberis，高德 17.01.0.2012）：

- 冷启动 2 次（最终版）：每个进程 38 个挂载点全部 `registered`（主进程 38 条），
  `skip: ` 计数 0，无高德 Java FATAL。16 个 featureId 全部出现在诊断卡。
- `search_hotword hit BaseSearchBar#requestPreSetWord` 实测命中（首页刷新与进入
  搜索页各命中）；`h5_ad_log` / `map_float_layer` / `msg_image_text` 本环境无
  对应广告数据下发，`registered` 待命中观察（未伪造 hit）。
- 首页搜索框 placeholder 为普通提示词「查找地点、公交、地铁」，无轮播热词。
- 地图缩放/平移正常；首页无运营浮层挂件（与 vanilla 对照一致）。
- 搜索页在本模拟器上打开后为空白（顶部两个白卡、其余灰底）。**对照实验**：
  关闭 `search_hotword` 后重启高德，搜索页同样空白 —— 与模块无关。
  根因是模拟器无外网（host 有网，但 guest 出站 TCP 超时、DNS 可达），
  该 AJX 页面无法完成资源加载；因此联想词(sugg)的交互回归无法在本环境验证，
  只能从代码层确认未 hook `ISearchService.sugg`。换有网环境需复测搜索页。
- 截图与日志：`E:\tmp-amap-hook\batch4-final-home.png`、
  `batch4-search-final.png`、`batch4-map-zoom-pan.png`、
  `batch4-final-logcat.txt`。

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
| `home_msg_filter` | `AjxModuleBadge#updateAppBadge(String,int)` | count>0 不调用 / count<=0 放行（角标红点更新） |
| `home_msg_filter` | `AjxModuleBadge#setBadgeDisplay(String,boolean)` | 不调用（角标显示广播不发出） |

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

三个新开关（默认全开），共 8 个挂载点：

| 开关 | 挂载点 | 动作 |
|---|---|---|
| `splash_gate` | `BootBizDataPreloaderImpl#canShowSplash()` | return `java.lang.Boolean.FALSE`（走官方 `SplashFinishReason.NO_SPLASH` 分支） |
| `splash_gate` | `SplashScreenServiceImpl#tryShowSplashView(I,String)` | 不调用（展示层兜底，skipVoid） |
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
