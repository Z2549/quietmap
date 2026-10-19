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
├── SettingsFragment.java      设置页（状态卡 / 六开关 / 诊断卡 / 页脚）
├── MaterialSwitchPreference.java
└── hooks/
    ├── HookKit.java           定位与 hook 的公共封装（黑名单、fail-open、匿名 Hooker）
    ├── Hooks.java             按开关注册 hook
    ├── Report.java            命中回传（每 3 秒快照上报）
    └── BgMsgFetchHooks / BgAwakenReportHooks / BgAfcTaokeHooks /
        BgUtdidUploadHooks / H5InstallApkHooks / PopupUpgradeHooks
app/src/main/resources/META-INF/xposed/   模块声明三件套
app/src/test/java/                        纯 JVM 单测（Feature 映射 / 开关矩阵）
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

覆盖两块：`FeatureTest`（六开关 key ↔ 编译期默认值映射，key 一旦改动会断掉用户已存的
开关状态，测试锁死它）、`HookSwitchMatrixTest`（六钩子开关矩阵：每开关独立、全开、全关）。

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

末尾汇总 OK/SIG/GONE 计数。SIG/GONE 的项要先用 dexsql 重新定位：

```bash
E:/tools/dexsql/dexsql.exe query --input <apk> \
  --sql "SELECT class_descriptor,name,descriptor FROM methods WHERE ..."
```

重新定位时优先找非混淆锚点（Activity、SPI 接口、字符串常量），再顺着调用链往下找，
不要凭类名相似下结论。定位结果改 hooks/ 下对应类的常量，并同步 `tools/check-mounts.sh`
的挂载点清单（完整签名与 dexsql 的 `methods.descriptor` 列同构）。

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

仅保证高德 17.01.0.2012，其它版本尽力而为。六个挂载点均非混淆类（`com.autonavi.*`
业务包 / 淘宝、支付宝 SDK 包），升级后大概率仍在，但签名可能变——先跑
`tools/check-mounts.sh` 再说。

## GPL 红线

本项目为 MIT。**零参考 MapAdKiller / MapClean 的代码与文档**（其许可与本项目不兼容）：
没有下载、打开或摘抄它们的任何内容。所有 hook 目标签名只来自两处——任务清单的调研结论
与 `E:/tools/dexsql/dexsql.exe` 对目标 APK 的实测；实测不一致时一律以 dexsql 为准并在
报告注明。同理，调试中也不要把它们的产物放进本仓库。
