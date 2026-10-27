# Changelog

## v0.8.1 — 2026-10-28

消息中心版本（versionCode 10）：UI-06 从 blocked 恢复可用，基于真机逆向改用 AJX badge/localStorage bridge 清空消息页会话列表与消息图标角标；功能总数 22 项全部可用。

### 修复

- **UI-06 消息中心净化恢复可用**（默认开）：真机确认消息页为 `amap_bundle_messagetab/src/pages/message_tab/MessageTab.page.js`，列表来自 `AjxModuleBadge#getAllSessionList(boolean, JsFunctionCallback)` 返回的 `{"convList":[...]}`，消息图标角标来自 `AjxModuleBadge#getBadgeInfo("messagetab")`，最近消息缓存为 `AjxModuleLocalStorage#getItemSync("message_tab", "recentlyMsgList-<uid>")`。
- 新挂载点：`getAllSessionList` 命中消息页/我的页时回调 `{"convList":[]}`；`getBadgeInfo("messagetab")` 返回 `{"value":0}`；`getItemSync("message_tab", "recentlyMsgList-*")` 返回 `[]`；`setItemSync` 对同一 key 返回 `true`、不落盘。只改 Java/AJX bridge 返回值，不改 JS 资源、不伪造页面。
- 移除 0.8.0 的 4 个 `msg_center` 旧挂载点（`MessageBoxServiceImpl#getOnlineData`、`MsgboxService#getNewComingUnRead_MsgNumFromDB`、`MessageBoxManager#getAllLocalMessages`、`ModuleMessageBox#hasNewMessage`）：真机命中 `hasNewMessage=false` 后消息页列表/角标仍不变；首页消息盒/红点仍由 UI-02 覆盖。

### 变更

- 功能总数 22 项：**可用 22、blocked 0**（0.8.0 为可用 21、blocked 1）；UI-06 默认开，设置页可单独关闭。
- UI-06 只作用于消息页（`messagetab`）与我的页（`MinePage`）；首页、搜索、地图页原样放行。回调为 null、页面路径取不到、类/方法缺失时 fail-open。
- 旧 0.8.0 的 UI-06 blocked 说明保留在下方历史记录中，便于追溯。

### 验证

- 单测 159 个用例通过（0 失败 / 0 跳过）；`:app:lintDebug` 通过（21 条 warning、0 error）；`assembleDebug` / `assembleRelease` 通过。
- `tools/verify-apk.sh` PASS（18 个 `[ok]`、0 失败）；`tools/check-mounts.sh` 对高德 17.01.0.2012 共 63 项，OK 63 / SIG 0 / GONE 0。
- 真机（25102RKBEC / Android 17 / LSPosed 2.2.1 / 高德 17.01.0.2012）：UI-06 开 = 消息页“暂无新消息”空态、无 stuck loading、我的页消息角标 0；关 = 恢复真实会话列表与角标 14；无 FATAL/ANR。配置恢复为 `msg_center=true`，未清数据、未退出账号。
- UI-05 / AD-12 仍为“挂载点已注册、真实场景未验证”，不宣称生效。

APK: `quietmap-0.8.1.apk`
versionCode: `10`
大小: `4,071,144 字节`
SHA-256: `952d8876adb555d31692a3f5060d5f9cfca5d6a88e24e61f91dae1f2726df402`

## v0.8.0 — 2026-10-27

批 6 收尾版本（versionCode 9）：新增运营活动与通知权限引导两个净化开关；消息中心因真机未通过标记为 blocked；诊断回传补入口计数，并确认 Provider 通道可用。

### 新增

- **AD-12 运营活动弹窗净化**（默认开）：`AppInitServiceImpl#getOperationModuleSwitch` 返回 false，`ActivitiesService#openOpetationsActivities` 3 参/4 参重载不调用。真机 3 个挂载点全部 registered，但未触发运营弹窗场景，实际效果**未验证**。
- **UI-05 通知权限引导净化**（默认开）：`PermissionGuideFloatShow#showNotificationGuide` 与 `SysPermissionGuideFloatMode#showFloatWithSysCore` 在命中通知权限时不展示；`so2#i` 仅在单一通知权限且定位已授权时把 permissionType 改写为 `location`，否则原样放行。真机 3 个挂载点全部 registered，但 `notify guide` 无 hit，未观察到通知横条也无法归因，实际效果**未验证**。
- **入口计数（entry）诊断**：`HookState.entry` 经 `Report` 上报 `entry.<key>`，诊断页显示 `registered · entry N · hit N · skip`，用于区分“从未调用”和“调用了但未命中”。上报键由 66 个增至 88 个；Provider/文件/合并规则对 entry 与 hit 一致（非负计数取最大值）。
- **诊断回传 socket 通道**：目标进程优先走抽象命名空间 LocalSocket，失败回退 Provider，再失败写文件。真机 HyperOS 上 socket 报 `socket not created`（SELinux/系统限制），**不可用**；Provider 通道已实测可用（`transport=provider` 持续刷新），因此 socket 失败不再作为阻断条件。

### 变更

- **UI-06 消息中心净化标记 blocked**（默认关、UI 置灰且固定显示关闭、不注册 hook）：blocked 前的真机验收中 4 个挂载点全部 registered，`hasNewMessage` 已返回 `false`，但消息页仍显示完整列表、顶部角标仍为 14，未达到设计效果。保留编号与行位，待重新逆向 AJX 消息页数据链路后再启用。
- `Hooks` 不再注册 `MsgCenterHooks`；`Batch6Probe.DEBUG_PROBE` 固定为 `false`，release 与日常 debug 都不安装探针。
- 功能总数 22 项：**可用 21、blocked 1**；挂载点 63 项。
- 诊断页摘要与异常横幅统计纳入 entry；仅 entry 回传也算“已回传”。

### 验证

- 单测 157 个用例通过（`:app:testDebugUnitTest`）；`:app:lintDebug`、`assembleDebug`、`assembleRelease` 通过。
- `tools/verify-apk.sh` 通过；`tools/check-mounts.sh` 对高德 17.01.0.2012 共 63 项，结果见下方记录。
- 真机（25102RKBEC / Android 17 / LSPosed 2.2.1 / 高德 17.01.0.2012）：
  - Provider 诊断回传通过，诊断页能取到 reg/hit/entry 数据；socket 在 HyperOS 不可用，文件通道仅作调试兜底。
  - UI-05 / AD-12：挂载点 registered，真实场景未验证（无 hit / 未触发），**不宣称生效**。
  - UI-06：真机失败，已 blocked；最终包确认设置项置灰、不注册 hook，消息页保持原始行为。
- 本轮未做 UI-05/AD-12 的完整真机视觉回归；需在通知频控重置、运营弹窗可触发时复验。

APK: `quietmap-0.8.0.apk`
versionCode: `9`
大小: `4,068,476 字节`
SHA-256: `0cc23dc18704edfe4fc9ecbf12f6beb5eec078e5d525e85db193e759d1c8d6d2`

## v0.7.0 — 2026-10-25

工程质量版本（versionCode 8）：工程基建、依赖基线与 UI 骨架升级，**无新增净化功能**。

### 新增

- **底部导航 3 tab**：设置 / 诊断 / 关于改为底部导航切换，单页承载。
- **CI 与工程脚本**：GitHub Actions 全绿流水线；`tools/preflight.sh`（环境/版本/签名预检）、`tools/verify-apk.sh`（Manifest/versionCode/签名/哈希校验）、`tools/release.sh`（一键构建→校验→归档→发版）；新增 preflight/CI/coverage/hook 注册表检查等单测。
- **发布签名强制**：release 构建要求签名配置，CI 走条件引用支持无 keystore 构建；`gradlew` 执行位与 runner 自带 SDK 的 CI 兼容修复。

### 变更

- 依赖基线升级：appcompat / material 更新，JDK baseline 对齐。
- 维护文档移出公开仓库。

### 说明

- 原计划的批 6 功能（AD-12 运营活动、UI-05 通知引导、UI-06 消息中心）经真机验证未能达到设计效果（高德 17.01.0.2012 的 AJX 动态化渲染路径与静态分析不符），**已从本版移除**，待重新逆向适配后再发布。
- 功能清单与 0.6.0 保持一致（19 项），挂载点 53 项。

## v0.6.0 — 2026-10-22

统一改造试点版本（versionCode 7）。

### 新增

- **单 Activity 导航重构**：主页面为设置页，诊断与关于改为二级页；二级页显示返回箭头，返回主页面保留滚动位置；移除点击标题弹出关于对话框的旧交互。
- **诊断页**：逐功能显示 `registered · hit N · skip 原因`，无数据显示 `—`；页首显示数据来源与刷新时间，页尾提供「刷新」「复制诊断信息」；进入页面与 `onResume` 刷新，不自动轮询。
- **关于页**：展示模块名、版本名/versionCode、目标应用与适配版本、源码链接、四模块互链、免责声明与许可证；当前模块标记「（当前）」。
- **状态卡四态**：正在连接框架… / 未激活 / 框架已连接 / 已激活；副文案显示框架 API、目标版本与 remote prefs 可用性。
- **异常横幅**：目标版本未适配，或已启用且明确失败的功能数 ≥2 时显示；不新增第五种状态。
- 功能编号统一为 AD-01～AD-11、UI-01～UI-04、PV-01～PV-04，共 19 项；设置页按 AD → UI → PV 分组。
- 默认值调整：AD 广告净化与 UI 界面精简默认开启，PV 隐私保护默认关闭（此前 19 项全部默认开启）。

### 修复

- 主页面移除内联诊断卡，不再显示逐功能 registered/hit/skip；诊断细节统一收敛到诊断页。
- 状态判定改为 service → scope → runningTargets 顺序，避免仅凭 service 绑定误报「已激活」。
- 所有用户可见文案迁移到 `strings.xml`，清理 Java 与 XML 中的中文字面量。

### 加固

- `StatusProvider` 新增 `failedFeatureCount(Context)`，按 `qm_report` 中已启用功能的 `reg`/`skip` 回传统计失败数。
- 诊断数据读取统一走 `StatusProvider` 的存储前缀，避免页面与 Provider 的键格式漂移。
- 构建基线统一为 AGP 8.13.2 / Gradle 8.13 / JDK 17 / compileSdk 37 / minSdk 29 / targetSdk 36。
- release 缺少 `keystore.properties` 时硬失败，禁止 debug 签名回退。

### 文档

- README 更新功能表编号、默认值、设置页结构、模块互链与构建工具链。
- 新增本 CHANGELOG。

### 验证

- `./gradlew test assembleDebug assembleRelease` 通过。
- 单测 43 个用例全部通过（debug/release 各执行一次，共 86 次）。
- 模拟器 UI 冒烟通过：主页面、诊断页、关于页均可打开；诊断页首次打开发现 `PreferenceCategory` 空指针，已修复并复测。未做真机验证。

APK: `quietmap-0.6.0.apk`
versionCode: `7`
大小: `3.5 MB（3,679,914 字节）`
SHA-256: `c6db27b5db35f12b170acd7b2fe6067fff0ccb4ee3488def34f6847d06c4b4e5`
