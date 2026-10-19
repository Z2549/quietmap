# 模拟器 LSPosed 环境刷入方案（QuietMap 验证基建）

> 状态：**方案文档，未实施刷入**。主代理审阅后再动手。
> 范围：仅 AVD 自建环境；真机（myron）按需另行申请，不在本文范围。
> 撰写日期：2026-10-19。本文所有下载渠道、文件大小、哈希均为本机（Windows 10 / Git Bash）实测。

---

## 0. TL;DR

- 验证 AVD `quietmap-test` 已就绪（API 35，x86_64，4G RAM / 12G data，WHPX 加速，已进桌面）。
- **重要**：高德 17.01.0.2012 是纯 arm64-v8a 应用（72 个 so 全在 `lib/arm64-v8a/`），`default` AOSP 镜像 abilist 仅 `x86_64`，直接 `adb install` 报 `INSTALL_FAILED_NO_MATCHING_ABIS`。**必须用带 ARM 翻译（libndk_translation）的 `google_apis` x86_64 镜像**。
- 刷入路线（待批准）：**Magisk（ramdisk 修补）→ Zygisk（或 ZygiskNext）→ LSPosed（zygisk 版）**。AVD 没有独立 boot 分区，Magisk 实际是修补 `system-images\...\ramdisk.img` 文件后冷启动。
- API 35 下官方 LSPosed（v1.9.2，止步 Android 14）不够用，选 **JingMatrix/Vector v2.2**（LSPosed 维护分支，支持 Android 15/16）。
- 下载渠道实测：**GitHub 直连全通**（github.com / api.github.com / objects.githubusercontent.com，3–10s/文件）；备用镜像 **ghfast.top 通**（哈希与直连一致）；ghproxy.com 已废、gh-proxy.com 不通、mirror.ghproxy.com 已死；cdn.jsdelivr.net/gh/ 只能取 git 树文件，**不能取 release 资产**。
- 备选 LSPatch（免 root 重签名嵌入）：**有高德运行时签名校验风险**（改包会触发「应用出现异常」），只做记录不实施；LSPosed 模块路线不改安装包，天然绕过签名校验。

---

## 1. 本机环境与 AVD 现状（已实测）

| 项 | 值 |
|---|---|
| SDK | `D:\Android\Sdk`（build-tools 34/35，platform-tools 37.0.1，emulator 37.2.12） |
| AVD 注册目录 | `D:\Android\avd`（`quietmap-test.ini` 在此） |
| AVD 实体目录 | `E:\tmp-amap-hook\avd\quietmap-test.avd`（见下方磁盘坑） |
| userdata | `E:\tmp-amap-hook\avd-data\userdata-qemu.img`（12G） |
| 镜像 | `system-images;android-35;google_apis;x86_64` r9（**已换装并验证**，含 ARM 翻译，见 §2） |
| 配置 | Pixel 6 皮肤，RAM 4096M，data 12G，GPU enabled/host，x86_64，API 35 |
| 加速 | WHPX（`Windows Hypervisor Platform accelerator is operational`），emulator-check accel=0（正常，WHPX 在位） |
| 启动 | `adb devices` 见 `emulator-5554`，`sys.boot_completed=1`，冷启动约 46s，已进 AOSP 桌面 |
| 硬件属性 | `ro.boot.hardware=ranchu`，`ro.hardware=ranchu`，`ro.product.cpu.abi=x86_64`，MemTotal≈4.0G，/data 12G |

启动命令（复现用）：

```bash
export ANDROID_SDK_ROOT="D:/Android/Sdk" ANDROID_AVD_HOME="D:/Android/avd"
"D:/Android/Sdk/emulator/emulator.exe" -avd quietmap-test -no-snapshot -no-boot-anim
```

### 磁盘坑（已踩）

- `D:` 只剩 8.5G：emulator 创建 userdata 前会检查 **AVD 目录所在盘** 空间（12G data 需要约 14.7G），`disk.dataPartition.path` 指到别盘**不改变这个检查位置**（实测 FATAL 依旧）。
- 解决：整个 `.avd` 目录放 `E:\tmp-amap-hook\avd\`，只把 `quietmap-test.ini`（path= 指向 E:）留在 `D:\Android\avd`，`emulator -avd quietmap-test` 照常识别。
- 另注意：本机 adb 上挂着一个**无线调试真机**（序列号 `adb-e4287ae2-…_adb-tls-connect._tcp.`，疑似 myron）。所有 adb 命令必须带 `-s emulator-5554`，禁止无差别 `adb install/shell`。

---

## 2. 关键前置：必须换 google_apis 镜像（高德 arm64-only）

实测：`adb install E:/download/base(4).apk`（17.01.0.2012，194,442,841 B）在 default 镜像上：

```
Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]
```

- APK 原生库只有 `lib/arm64-v8a/`（72 个 so）；default 镜像 `ro.product.cpu.abilist=x86_64`，无 ARM 翻译。
- **对策（已执行并验证）**：安装 `system-images;android-35;google_apis;x86_64`（含 `libndk_translation`）。实测换装后 `ro.product.cpu.abilist=x86_64,arm64-v8a`，`/system/lib64/libndk_translation.so` 在位，高德安装 **Success**、冒烟进首页无闪退。
- 换镜像操作：改 `config.ini` 的 `image.sysdir.1=system-images\android-35\google_apis\x86_64\`，删 userdata 冷启动（避免两镜像状态残留）。
- 校验命令（换完必查）：
  ```bash
  adb -s emulator-5554 shell getprop ro.product.cpu.abilist
  # 期望含 x86_64,arm64-v8a,armeabi-v7a
  ```
- 若 google_apis 仍无翻译（低概率）：退 `system-images;android-35;google_apis_playstore;x86_64`；再不行退 API 34 google_apis。
- **API 34 vs 35**：官方 LSPosed v1.9.2 官方支持到 Android 14；API 35 用 JingMatrix/Vector。若 Vector 出兼容问题，可另建 API 34 google_apis AVD 对齐官方 LSPosed（高德 17 两者都能跑）。

---

## 3. Magisk 刷入路径（AVD 无 boot 分区的现实）

### 3.1 原理

AVD 没有可 dd 的 boot 分区：启动由 emulator 直接加载 system-image 目录里的
`kernel-ranchu`（内核）+ `ramdisk.img`（ramdisk）+ `kernel_cmdline.txt`。
API 35 镜像为单 ramdisk（无 vendor_boot）。所以 **Magisk「刷 boot」= 修补 ramdisk.img 文件本身**，在宿主机文件系统完成，不需要 `fastboot flash`。

### 3.2 推荐流程（MagiskOnEmulator 式，参考 https://github.com/shakalaca/MagiskOnEmulator）

1. **备份** `D:\Android\Sdk\system-images\android-35\google_apis\x86_64\ramdisk.img`（原厂备份必须留，Magisk 更新/回滚都要用）。
2. **组装 boot.img**：用 AOSP `mkbootimg`（python 单文件）把 `kernel-ranchu` + `ramdisk.img` 打成假 boot.img：
   ```bash
   python mkbootimg.py --kernel kernel-ranchu --ramdisk ramdisk.img \
     --cmdline "$(cat kernel_cmdline.txt)" --base 0x8000 --pagesize 4096 \
     --header_version 2 -o boot.img
   ```
   （实施勘误 2026-10-19：`--header_version 2` 要求 DTB 而 AVD 无 DTB，实际用 **`--header_version 0`** 修补成功；base 0x8000 / pagesize 4096 不变。）
3. `adb -s emulator-5554 push boot.img /sdcard/`，安装 **Magisk-v30.7.apk**（已下载，见 §5），Magisk → 安装 → **选择并修补一个文件** → 选 boot.img → 产出 `/sdcard/Download/magisk_patched-*.img`。
4. `adb pull` 取回，**在模拟器 shell 里用 magiskboot 解包**：
   - magiskboot 来源：Magisk APK 内 `lib/x86_64/libmagiskboot.so`（实测 943,848 B，就是 magiskboot 可执行文件换名）。unzip 出来 push 到 `/data/local/tmp/magiskboot`，`chmod +x` 后 `./magiskboot unpack magisk_patched.img` 取出 `ramdisk`。
   - 备选：WSL/Linux 里跑同名二进制。
5. 把解出的 ramdisk 处理成 `ramdisk.img` 形态（gzip/lz4 压缩 cpio，与原厂一致；MagiskOnEmulator 的 `patch.sh pull` 已封装），**覆盖回** system-image 目录（不是 AVD 目录！）。
6. **冷启动**：`emulator -avd quietmap-test -no-snapshot`（快照会吃掉 ramdisk 改动，必须 -no-snapshot 或先 wipe snapshots）。Magisk app 显示「已安装」即成功。
7. 后续 Magisk 更新：重复 2–6（每次拿备份的原厂 ramdisk.img 重新打，别在已修补基础上叠加）。

### 3.3 `-writable-system` 与 `adb root` 的定位

- google_apis 镜像（非 Play）支持 `adb root` / `adb remount`；`emulator -writable-system` 让运行时 /system 可写。
- **但现代 Magisk（v24+）只支持 systemless（ramdisk）安装**，「直接装进 /system」已移除——`-writable-system` 不是用来装 Magisk 的。它的用途：
  - `adb root; adb remount` 后推调试文件、改 /system 配置、pull `/data/adb/`（LSPosed/模块数据）；
  - `adb disable-verity` + 重启后再 remount（部分版本需要）。
- 坑：`-writable-system` 与 save-snapshot 快照不兼容（警告/状态损坏），配合 `-no-snapshot` 用；与 Play 镜像的 AVB 校验冲突。

### 3.4 KernelSU：不走

KernelSU 需要 GKI 内核（钩子/补丁），AVD 的 `kernel-ranchu` 预编译内核不满足；除非自编译内核再 `emulator -kernel` 加载，成本远超收益。**模拟器路线统一用 Magisk。**

---

## 4. ZygiskNext + LSPosed

### 4.1 两条装配链

| 链 | 组成 | 适用 |
|---|---|---|
| **最小链**（推荐先跑通） | Magisk（设置里开 Zygisk）+ LSPosed zygisk 版 zip | 功能验证够用 |
| **完整链** | Magisk（**关**自身 Zygisk）+ ZygiskNext + LSPosed zygisk 版 | 需要 root 隐藏/对抗检测时 |

注意：ZygiskNext 与 Magisk 自带 Zygisk **二选一**，同时开会冲突。高德有环境检测可能，若后续发现检测 root/注入，再升级完整链。

### 4.2 版本选择（API 35）

| 组件 | 版本 | 说明 |
|---|---|---|
| Magisk | **v30.7**（2026-02） | 稳定版 APK |
| LSPosed | **JingMatrix/Vector v2.2-3080**（2026-08） | API 35/Android 15+ 用它；JingMatrix/LSPosed 仓库已更名 Vector |
| LSPosed（备选） | 官方 v1.9.2-7024 zygisk | 仅 Android ≤14（API 34 及以下）；仓库 2023 年后停更 |
| ZygiskNext | v1.5.2-864（2026-10） | 仅完整链需要 |
| LSPatch | v0.6-398 | 备选路线用（§6） |

### 4.3 安装步骤（Magisk 就位后）

1. Magisk → 模块 → 从存储安装 `Vector-v2.2-3080-Release.zip`（或官方 zygisk zip）→ 重启。
2. 重启后桌面出现 LSPosed Manager；`adb shell su -c 'ls /data/adb/lspd'` 可验目录。
3. 在 LSPosed 里启用 QuietMap 模块，作用域勾 `com.autonavi.minimap`，强制停止高德再进。
4. 验证：`adb -s emulator-5554 logcat | grep -iE 'lsposed|quietmap'`。

---

## 5. 下载渠道实测（2026-10-19，本机网络）

结论：**GitHub 直连可用且快，镜像只是备用**。下表文件均已实际下载到 `E:\tmp-amap-hook\downloads\`，大小与 GitHub API 报告值一致，sha256 为实算。

| 文件 | 来源 URL | 渠道 | 大小(B) | sha256（前 64 位） | 耗时 |
|---|---|---|---|---|---|
| Magisk-v30.7.apk | github.com/topjohnwu/Magisk/releases/download/v30.7/Magisk-v30.7.apk | 直连 200 | 11,613,864 | `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5` | 10s |
| LSPosed-v1.9.2-7024-zygisk-release.zip | github.com/LSPosed/LSPosed/releases/download/v1.9.2/... | 直连 200 | 2,462,055 | `0ebc6bcb465d1c4b44b7220ab5f0252e6b4eb7fe43da74650476d2798bb29622` | 3s |
| Vector-v2.2-3080-Release.zip | github.com/JingMatrix/Vector/releases/download/v2.2/... | 直连 200 | 9,316,843 | `9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc` | 10s |
| Zygisk-Next-1.5.2-864-a4a72ce-release.zip | github.com/Dr-TSNG/ZygiskNext/releases/download/v1.5.2/... | 直连 200 | 6,714,344 | `ce50b13fecb9e4456db69ef17b715f7cfbc094004dd9f30f7d6348dfab619a96` | 7s |
| LSPatch-manager-v0.6-398-release.apk | github.com/LSPosed/LSPatch/releases/download/v0.6/... | 直连 200 | 7,205,134 | `13d543f46fac47b46d6173dd062925fd7efa4f357ab62b8206d94d37c79b10f3` | 5s |
| LSPosed-v1.9.2-...-zygisk-release.zip | **经 ghfast.top 代理**（`https://ghfast.top/<原URL>`） | 200 | 2,462,055 | `0ebc6bcb…29622`（与直连**完全一致**） | — |

渠道逐一实测记录：

| 渠道 | 结果 | 备注 |
|---|---|---|
| `github.com`（release 页/资产直下） | **通**（200） | objects.githubusercontent.com 跟随重定向正常 |
| `api.github.com` | **通**（200） | 查版本/资产列表用它最稳 |
| `cdn.jsdelivr.net/gh/` | 通但**无用** | 只服务 git 树内文件（README 等 200），Magisk/LSPosed 的 apk/zip 是 release 资产，jsdelivr 取不到 |
| `ghproxy.com` | **废** | 资产 URL 返回 200 但只有 1,797 B 的引导页（JS 跳转），curl 拿不到真文件 |
| `gh-proxy.com` | **不通**（连接失败 000） | — |
| `mirror.ghproxy.com` | **死**（530） | — |
| `ghfast.top` | **通**（备用首选） | `https://ghfast.top/<原URL>`，实测哈希与直连一致 |
| `dl.google.com`（sdkmanager） | 通但**慢**（约 1.2MB/s） | system-image zip 1,738,815,903 B，走 sdkmanager 预计 25min+ |
| `mirrors.cloud.tencent.com/AndroidSDK/` | **通且快**（23.9MB/s） | `…/sys-img/google_apis/x86_64-35_r09.zip` 与官方**同尺寸同内容**，72s 下完；sha256 `c67b9ba0ff5bc0eb6d046871bfa228af14d4d47b02f0cdae94f048e511b7566e`。**system-image 下载首选**。手动 unzip 到 `system-images\android-35\google_apis\` 即可（zip 顶层是 `x86_64/`，含 source.properties，avdmanager/emulator 可识别；只是 sdkmanager --list_installed 不显示，无碍） |
| `mirrors.tuna.tsinghua.edu.cn` / `mirrors.aliyun.com`（AndroidSDK） | 无此路径（404） | 不含 sys-img |

获取版本列表的推荐命令：

```bash
curl -sL https://api.github.com/repos/topjohnwu/Magisk/releases/latest   # jq 取 assets[].browser_download_url
```

---

## 6. 备选路线：LSPatch（免 root 嵌入重签名）——只记风险，不实施

LSPatch（LSPosed/LSPatch v0.6，manager APK 7.2MB + jar 10.9MB）原理：把 LSPosed 注入逻辑打进目标 APK 并**重签名**，免 root 运行模块。

**风险（高德特有）**：

- 高德**有运行时签名校验**（实证：改包后启动报「应用出现异常」；车机版校验在混淆类 `p90.d(Context)`，手机版改包需 ApkSignatureKillerEx 类方案配合）。LSPatch 必然重签名 → **大概率触发校验**。
- 反证面：竞品 MapAdKiller 的免 root 内置版（同为 LSPatch 嵌入思路）确实发布过 v2.1.4，证明**存在可行的绕法**（可能配合了签名校验绕过或版本差异），但细节不明、版本绑定强。
- 其他成本：重签名丢官方签名（登录态/支付/部分云控可能异常）、杀软报红、侵权风险、每次高德升版要重打。

**对比结论**：

| | Magisk+LSPosed（主路线） | LSPatch（备选） |
|---|---|---|
| 签名校验 | 不改安装包，天然绕过 | 重签名，高危触发 |
| root 需求 | 需要（AVD 自建） | 免 root（真机可用） |
| 高德升级 | 模块不用动 | 每版重打 |
| 稳定性 | 模块路线成熟（MapAdKiller 主形态） | 版本绑定、易翻车 |
| 本方案采用 | **是** | 否（仅真机无 root 且无计可施时再评估） |

---

## 7. 步骤清单与耗时预估

| # | 步骤 | 预估耗时 | 状态 |
|---|---|---|---|
| 1 | AVD 现状就绪（quietmap-test，WHPX，桌面） | — | **已完成**（冷启动 46s） |
| 2 | 装 `system-images;android-35;google_apis;x86_64` | 2min（腾讯镜像 24MB/s） | **已完成** |
| 3 | 切镜像 + 清 userdata + 冷启动 + 验 abilist 含 arm64 | 5min | **已完成**（abilist=x86_64,arm64-v8a，冷启动 59s） |
| 4 | `adb install` 高德 + `am start` 冒烟（不闪退进首页） | 5min | **已完成**（见 §7.1） |
| 5 | 备份 ramdisk.img + 组装 boot.img（mkbootimg） | 10min | 待批准 |
| 6 | Magisk app 修补 boot.img + magiskboot 回填 ramdisk | 15–20min | 待批准 |
| 7 | 冷启动验 Magisk + 装 LSPosed（Vector zygisk）模块 | 10min | 待批准 |
| 8 | LSPosed 启用 QuietMap 模块 + 高德联调 | 视模块进度 | 待批准 |
| 9 | （可选）ZygiskNext 完整链 / root 隐藏调优 | 20min+ | 按需 |

高德冒烟启动命令（无 root）：

```bash
adb -s emulator-5554 install "E:/download/base(4).apk"
adb -s emulator-5554 shell am start -n com.autonavi.minimap/com.autonavi.map.activity.SplashActivity
# 无该组件名时用: adb shell cmd package resolve-activity --brief com.autonavi.minimap
```

### 7.1 高德冒烟实测记录（2026-10-19，17.01.0.2012 @ quietmap-test）

- 安装：`Success`（default 镜像下同命令报 `INSTALL_FAILED_NO_MATCHING_ABIS`，换 google_apis 后通过）。
- 启动：`SplashActivity` 正常拉起；首启依次出现隐私协议（Welcome to Amap）→ 定位权限 → 通知权限 → 桌面小组件添加询问（launcher AddItemActivity），逐项放行后进入首页。
- 稳定性：全程无 FATAL / am_crash / ANR；`com.autonavi.minimap` 主进程 + `:locationservice` / `:widgetProvider` 多进程稳定存活。
- 首页状态：3D 地球地图渲染正常，搜索框「查找地点、公交、地铁」、功能宫格（打车/订酒店/驾车/公交/车主服务…）、底部 tab（首页/探索/对话/打车/未登录）、天气组件均就位。截图存 `E:\tmp-amap-hook\amap-smoke1..5.png`。
- 注意：首启弹窗顺序为 隐私 → 定位 → 通知 → 小组件，自动化冒烟时建议用 `uiautomator dump` 取按钮坐标（文本会随系统语言变化，本机模拟器是英文系统）。

## 8. 已知坑汇总

1. **D 盘空间**：userdata 检查看 AVD 目录所在盘；`.avd` 要放大盘（见 §1）。
2. **arm64-only 高德**：default/AOSP 镜像装不上，必须 google_apis（ARM 翻译）。
3. **快照吞改动**：改 ramdisk.img 后必须 `-no-snapshot` 冷启动，否则快照回放旧状态。
4. **ramdisk 位置**：补丁打在 `system-images\<pkg>\x86_64\ramdisk.img`，不在 AVD 目录；两处都有 img 别搞混。
5. **Zygisk 二选一**：Magisk 自带 Zygisk 与 ZygiskNext 不能同开。
6. **LSPosed 版本与 API 绑定**：API 35 用 JingMatrix/Vector；官方 v1.9.2 最多 API 34。
7. **magiskboot 是 Linux ELF**：Windows 上不能直接跑，用模拟器 shell 或 WSL；APK 里 `lib/x86_64/libmagiskboot.so` 直接 push 即可。
8. **多设备**：adb 上有无线真机，命令一律 `-s emulator-5554`。
9. **Magisk 更新叠加**：每次从原厂 ramdisk.img 重打，勿在已修补镜像上二次修补。
10. **`-writable-system` 与快照不兼容**，且不是装 Magisk 的手段（见 §3.3）。
11. **magiskd 两雷（实施实测）**：① `magisk --sqlite 'PRAGMA ...'` 会令 magiskd SIGSEGV（简单 UPDATE/SELECT 安全，PRAGMA 禁用）；② 手动 `magisk --service` 拉起的 daemon 会因 stub.apk 被首个 daemon 读取后删除而误判 "APK signature mismatch" 并**自动卸载 Magisk app**——恢复=重启模拟器（magiskinit 重新展开 stub.xz）+ 重装 app。别手动重启 magiskd。
12. **su 授权**：首次超时自动拒绝 uid 2000，需 `su -c "magisk --sqlite 'UPDATE policies SET policy=2 WHERE uid=2000'"` 常驻放行。
13. **Vector Manager 不自装**：包名 `org.matrix.vector.manager`（不叫 org.lsposed），刷完模块需手动从 zip 内 manager.apk 安装。
14. **ramdisk 压缩格式勘误**：API 35 google_apis 原厂 ramdisk 是 **lz4_lg（magic `02214c18`）**，不是通用 lz4 frame（04224d18）；magiskboot `unpack -n` repack 保留原格式即可，别手动重压。
15. **AVD 的 initrd 是派生产物**（= system-image ramdisk.img 前段 + bootconfig 尾），每次启动自动再生成；补丁目标永远是 `system-images\android-35\google_apis\x86_64\ramdisk.img`。

---

## 9. 参考

- MagiskOnEmulator（AVD ramdisk 修补流程）：https://github.com/shakalaca/MagiskOnEmulator
- Magisk releases：https://github.com/topjohnwu/Magisk/releases （api.github.com/repos/topjohnwu/Magisk/releases/latest）
- LSPosed 官方（停更于 v1.9.2）：https://github.com/LSPosed/LSPosed/releases
- JingMatrix/Vector（API 35+ 维护分支）：https://github.com/JingMatrix/Vector/releases
- ZygiskNext：https://github.com/Dr-TSNG/ZygiskNext/releases
- LSPatch：https://github.com/LSPosed/LSPatch/releases
- 高德签名校验情报：`E:\tmp-amap-hook\C-外部情报.md`（p90.d 车机版校验、「应用出现异常」、ApkSignatureKillerEx、MapAdKiller 免 root 内置版）
