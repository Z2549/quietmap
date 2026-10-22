# Changelog

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
