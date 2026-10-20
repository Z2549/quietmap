# QuietMap

让高德地图安静下来的 LSPosed 模块：后台净化、数据层断源、收敛弹窗与统计上报。

- 目标：高德地图 **17.01.0.2012**（`com.autonavi.minimap`）
- 框架：LSPosed / libxposed API 102
- 版本：0.5.1（versionCode 6）
- 许可：MIT

> 本模块与高德地图官方无任何关联，仅供学习、研究和个人设备使用。使用可能违反目标应用的服务条款，风险由使用者自行承担。

## 功能

19 个开关，全部默认开启。装上即净，遇到个别场景可在模块设置里单独关闭。

| # | 分组 | key | 功能 |
|---|---|---|---|
| 1 | 开屏净化 | `splash_gate` | 开屏广告拦截（`canShowSplash` 返回 FALSE + 展示层兜底） |
| 2 | 开屏净化 | `splash_request` | 开屏广告请求断源（`sendSplashScreen` 全重载 + 混淆调度类） |
| 3 | 开屏净化 | `splash_shake` | 摇一摇开屏禁用（清空引导/提示，关闭速度严格判定） |
| 4 | 后台净化 | `bg_msg_fetch` | 后台营销消息拉取净化（亮屏触发、后台拉取、推送处理、主图拉取） |
| 5 | 后台净化 | `bg_awaken_report` | 唤端上报断源 |
| 6 | 数据层断源 | `bg_afc_taoke` | 淘宝客广告 SDK 冷启动注册断源 |
| 7 | 数据层断源 | `bg_utdid_upload` | 设备指纹上传与服务端交换断源 |
| 8 | 数据层断源 | `h5_install_apk` | H5 静默下载安装 APK 断源 |
| 9 | 界面 | `popup_upgrade` | 升级弹窗屏蔽 |
| 10 | 首页净化 | `home_banner` | 首页 Banner 拉取与加载断源 |
| 11 | 首页净化 | `home_operate_bar` | 首页运营活动条与地图消息气泡屏蔽 |
| 12 | 首页净化 | `home_msg_filter` | 首页消息/红点数据层断源、主图投放与角标红点净化（`AjxModuleBadge` + 启动器角标 `BadgeSystemManager#updateAppBadge`，count>0 抑制 / count<=0 放行） |
| 13 | 搜索与推广净化 | `search_hotword` | 搜索热词净化（不影响输入联想） |
| 14 | 搜索与推广净化 | `h5_ad_log` | H5 广告点击/曝光监测断源 |
| 15 | 搜索与推广净化 | `map_float_layer` | 地图运营浮层挂件不注册 |
| 16 | 搜索与推广净化 | `msg_image_text` | 营销图文消息素材下载断源 |
| 17 | 弹窗与统计净化 | `popup_clean` | 升级提示配置不下载、首页弹窗冲突队列不入队 |
| 18 | 弹窗与统计净化 | `track_suppress` | 开屏埋点、开屏日志与 UT 统计上报断源 |
| 19 | 摇一摇 | `shake_h5` | H5/小程序摇一摇传感器不注册（保留页面正常振动） |

`popup_clean` 属于中风险项：它拦截的是首页通用弹窗冲突队列，可能连带吞掉功能引导、分享口令等业务弹窗。遇到异常弹窗缺失时单独关闭这一项即可。

## 安装与使用

1. 在已安装 LSPosed（libxposed API 102）的设备上安装 `quietmap-0.5.1.apk`。
2. 在 LSPosed 管理器里启用 QuietMap，作用域勾选 **高德地图**（`com.autonavi.minimap`）。
3. 强制停止高德后重新打开（或重启设备）。
4. 打开 QuietMap 设置页：
   - 顶部状态卡显示「已激活」表示模块已注入高德进程；
   - 「remote prefs：可用」表示开关改动能被高德进程读到；
   - 诊断卡按功能列出 `registered` / `hit N` / `skip`，用于确认钩子是否生效。
5. 按需关闭单项开关；开关改动在下一次高德进程启动后生效（部分钩子随进程生命周期安装）。

## 兼容性

**仅保证高德地图 17.01.0.2012。** 其它版本尽力而为：大多数挂载点是非混淆业务类，升级后大概率仍在，但方法签名可能变化。升级高德后先跑挂载点体检：

```bash
bash tools/check-mounts.sh "E:\path\to\amap.apk"
```

输出 `OK` / `SIG` / `GONE` 三态，共 **53 项**。DexSQL 缓存按 APK sha256 前 16 位隔离（`tools/.dexsql-cache/<sha256>/`），换包不会复用旧缓存；查询失败（dexsql 非 0 退出或结果异常）会直接中止，不会把错误结果当成 0 行而误报 `GONE`。脚本会打印 APK 的 versionName，非 **17.01.0.2012** 时给出 WARN（只提示、不中断）。混淆挂载点（`Lze5#attemptRequestData`、`La8#run`、`Lcx1#addToQueue`、`Llt1#IDeliveryDisplay.start`）随版本改名的风险最高，失效时模块只记 `skip`，不会影响其余钩子。

## 稳定性设计（摘要）

- 所有 Hooker 使用匿名内部类（不用 lambda），规避 bootclasspath desugar 问题。
- 每个 `intercept` 整段 `try/catch(Throwable)`，异常时 fail-open 放行原逻辑。
- 抑制类钩子命中路径不调用原方法；回退路径 `chain.proceed()` 恰好一次，禁止双重执行。
- 统一 `PROTECTIVE` 异常模式 + 稳定 hook id；同一方法只挂一个 hook。
- 框架类黑名单：`android.` / `java.` / `dalvik.` / `androidx.` / `kotlin.` 开头的类拒绝 hook。
- 定位失败只记 `<featureId> skip: ...`，绝不向外抛；安装整段再包一层 `try/catch(Throwable)`。
- 只做「不调用 / 返回常量」，不修改复杂业务状态；开关只认 remote prefs。

## 已知限制

- `libxposed` 的 `XposedProvider` 在 `SendBinder` 调用上没有调用方认证，属框架约定风险（不影响本模块自身逻辑）。
- 诊断 Provider 仅接受高德 UID：`Binder.getCallingUid` + `getPackagesForUid` 必须命中 `com.autonavi.minimap`。
- DexKit 扫描仅在主进程启用；非主进程（`:locationservice`、`:widgetProvider`）走类名/方法名兜底。
- release 缺少 `keystore.properties` 时构建直接失败（`assembleRelease` / `bundleRelease` 抛 `GradleException`），避免产出 debug 签名的正式包。

## 全量覆盖矩阵

对照调研报告 `B-hook点.md` 的 40+ 点位，逐条给出结论。批号与开关名对应实现；「有意不做」附理由；「技术不可行」指 dex 层没有可安全落地的本地实现。

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

## 从源码构建

```bash
cd quietmap
./gradlew test assembleDebug assembleRelease
bash tools/check-mounts.sh
```

工具链：AGP 8.12.2 + Gradle wrapper 8.13 + JDK 21，compileSdk 36 / minSdk 29 / targetSdk 36。
release 产物：`app/build/outputs/apk/release/quietmap-0.5.1.apk`（R8 minify + shrinkResources）。
签名读取仓库根目录 `keystore.properties`；缺少该文件时 release 构建直接失败，不会产出用 debug 证书签名的包。

## GPL 红线

本项目为 MIT。**零参考 MapAdKiller / MapClean 的代码与文档**（其许可与本项目不兼容）：
没有下载、打开或摘抄它们的任何内容。所有 hook 目标签名只来自调研结论与
`dexsql` 对目标 APK 的实测；实测不一致时一律以 dexsql 为准。调试产物也不进入本仓库。

## License

MIT，见 [LICENSE](LICENSE)。
