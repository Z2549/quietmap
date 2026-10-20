#!/usr/bin/env bash
# 挂载点体检：高德升级后跑这个，30 秒内知道十九个钩子的挂载点还活着几个。
#
# 用法（Git Bash）：
#   bash tools/check-mounts.sh [apk路径] [缓存输出目录]
#
# 例：
#   bash tools/check-mounts.sh
#   bash tools/check-mounts.sh "E:\download\base(5).apk"
#
# 依赖：E:\tools\dexsql\dexsql.exe（首次会为这个 apk 建缓存，约 40 秒）
# 缓存按 APK 内容区分：默认写仓库内 tools/.dexsql-cache/<sha256前16位>/，
# 换 APK 自动换目录，不会把旧包的结果当成新包的（也不往 APK 目录写东西）。
# 版本校验：用 D:/Android/Sdk/build-tools 下最新版本的 aapt 读 versionName，
# 与实测版本不符只打 WARN、不中断。
#
# 判定规则：
#   OK   = 类和方法都在，且方法完整签名没变，hook 大概还能挂上
#   GONE = 类不存在了（R8/升级改了名），需要重新定位
#   SIG  = 类还在但方法签名变了（改名 / 参数变化），看差异再决定
#
# 挂载点清单（共 53 条）与 app/src/main/java/com/quietmap/app/hooks/ 下的常量一一对应，
# 完整签名取自 dexsql 对高德 17.01.0.2012 的实测。
# 混淆类挂载点（Lze5#attemptRequestData、La8#run）另有方法体字符串锚点监控：
# 签名在但锚点串丢了也判 SIG（运行时 DexKit 按锚点定位会失败）。
# La8 的锚点监控与运行时 Anchors.java 保持一致：两条字符串必须同时命中。

set -u

APK="${1:-E:/download/base(4).apk}"
OUT="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEXSQL="E:/tools/dexsql/dexsql.exe"
if [ ! -x "$DEXSQL" ]; then
  DEXSQL="E:\\tools\\dexsql\\dexsql.exe"
fi
[ -f "$DEXSQL" ] || { echo "找不到 dexsql: $DEXSQL" >&2; exit 2; }
[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 2; }

# 缓存目录按 APK 内容哈希区分：换包必然换目录，不会再拿旧包的缓存查询新包。
APK_SHA=$(sha256sum "$APK" 2>/dev/null | cut -c1-16)
[ -n "$APK_SHA" ] || { echo "无法计算 APK sha256（需要 sha256sum）" >&2; exit 2; }

[ -z "$OUT" ] && OUT="$SCRIPT_DIR/.dexsql-cache/$APK_SHA"
mkdir -p "$OUT"
CACHE="$OUT/cache.dxc2"

if [ ! -f "$CACHE" ]; then
  echo "== 首次为该 apk 建 DexSQL 缓存（约 40 秒）..."
  "$DEXSQL" prepare-cache --input "$APK" --output "$OUT" >/dev/null 2>&1
  [ -f "$CACHE" ] || { echo "建缓存失败: $CACHE" >&2; exit 1; }
fi

# 查询失败（非 0 退出码）必须中止：不能把 dexsql 报错当成 0 行结果，
# 否则所有挂载点都会被误判成 GONE。
q() {
  local out rc
  out=$("$DEXSQL" query-cache --cache-file "$CACHE" --sql "$1" 2>&1)
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "dexsql 查询失败（exit $rc）: $1" >&2
    [ -n "$out" ] && printf '%s\n' "$out" >&2
    exit 3
  fi
  printf '%s\n' "$out"
}

count() {
  local out n
  out=$(q "$1") || exit 3   # q 在子 shell 里 exit，父 shell 需要再退出一次
  n=$(printf '%s\n' "$out" | grep -oE '^\| [0-9]+' | tr -d '| ' | head -1)
  # 查询成功但拿不到计数值同样视为失败（正常的 SELECT count(*) 不会为空）。
  if [ -z "$n" ]; then
    echo "dexsql 查询结果异常（无计数值）: $1" >&2
    [ -n "$out" ] && printf '%s\n' "$out" >&2
    exit 3
  fi
  printf '%s\n' "$n"
}

# 挂载点清单：完整方法描述符|功能
# 完整描述符 = 类描述符 + ";->" + 方法名 + 参数/返回值 JNI 签名，与 dexsql 的
# methods.descriptor 列同构，直接全等比对即可判 SIG。
#
# 批 3（首页营销三件套）说明：
# - sendList 批 2 经验的多重载全挂：2 参 + 3 参两条都列。
# - getMessages 批 3 dexsql 复核发现是**三个**重载（任务清单原写 1 个），三条全列。
# - IDeliveryDisplay#start 挂实现类 Llt1（接口层 hook 无效，ART 走 itable 分派），
#   Llt1 是混淆类，与 Lze5 同样需要「类名变更监控」——这里只查签名，
#   运行时定位失败由 fail-open 记 skip；类名变了查 class_interfaces 反查新实现类。
# - AjxModuleBadge 是 AbstractModuleBadge（抽象类）的唯一子类，
#   updateAppBadge/setBadgeDisplay 的实体实现都在它上面（smali 实测）。
MOUNTS='Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessageInBackground(Landroid/app/Activity;Z)V|bg_msg_fetch 后台拉取营销消息
Lcom/autonavi/minimap/bundle/msgbox/push/BackgroundMsgScreenReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V|bg_msg_fetch 亮屏触发源
Lcom/autonavi/minimap/awaken/AwakenRequestHolder;->sendAwakenPage(Lcom/autonavi/minimap/awaken/param/AwakenPageRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|bg_awaken_report 唤端上报(2参)
Lcom/autonavi/minimap/awaken/AwakenRequestHolder;->sendAwakenPage(Lcom/autonavi/minimap/awaken/param/AwakenPageRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|bg_awaken_report 唤端上报(3参补充)
Lcom/taobao/flowcustoms/afc/taoke/AfcTkManager;->registerTkFlag(Landroid/content/Context;)V|bg_afc_taoke 淘宝客SDK注册
Lcom/ta/audid/upload/UtdidUploadTask;->upload()V|bg_utdid_upload 指纹上传
Lcom/ta/audid/upload/UtdidUploadTask;->uploadFromCache()V|bg_utdid_upload 指纹缓存上传
Lcom/ta/audid/upload/UtdidUploadTask;->reqServer(Ljava/lang/String;)Z|bg_utdid_upload 服务端交换
Lcom/alipay/mobile/common/info/DeviceInfo;->installApk(Ljava/lang/String;)V|h5_install_apk H5静默安装
Lcom/amap/bundle/appupgrade/AppUpgradeController;->q(Lcom/amap/bundle/appupgrade/e;ZZ)Z|popup_upgrade 升级弹窗
Lcom/autonavi/bundle/amaphome/impl/BootBizDataPreloaderImpl;->canShowSplash()Ljava/lang/Boolean;|splash_gate 开屏闸门(返回Boolean.FALSE走NO_SPLASH分支)
Lcom/autonavi/minimap/impl/SplashScreenServiceImpl;->tryShowSplashView(ILjava/lang/String;)V|splash_gate 展示层兜底(不调用)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendSplashScreen(Lcom/autonavi/minimap/alimama/param/SplashScreenRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|splash_request 开屏广告请求(3参,dexsql实测falcon包名)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendSplashScreen(Lcom/autonavi/minimap/alimama/param/SplashScreenRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|splash_request 开屏广告请求(2参重载)
Lze5;->attemptRequestData(IZ)V|splash_request 开屏广告请求调度(混淆类,字符串锚定"不发送请求",SIG监控)
Lcom/autonavi/minimap/entity/SplashButtonInfo;->getShakeGuide()Ljava/lang/String;|splash_shake 摇一摇引导清空
Lcom/autonavi/minimap/entity/SplashButtonInfo;->getShakeTip()Ljava/lang/String;|splash_shake 摇一摇提示清空
Lcom/autonavi/minimap/entity/SplashButtonInfo;->isSpeedStrict()Z|splash_shake 摇一摇速度严格关
Lcom/autonavi/minimap/banner/BannerRequestHolder;->sendList(Lcom/autonavi/minimap/banner/param/BannerListRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|home_banner Banner拉取(2参重载)
Lcom/autonavi/minimap/banner/BannerRequestHolder;->sendList(Lcom/autonavi/minimap/banner/param/BannerListRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|home_banner Banner拉取(3参重载)
Lcom/autonavi/bundle/banner/manager/BannerManager;->b(Ljava/lang/String;ZZLcom/autonavi/bundle/banner/manager/OnLoadBannerListener;)V|home_banner 运营位Banner加载
Lcom/autonavi/bundle/uitemplate/mapwidget/widget/activity/OperateActivityMapWidget;->refreshSplashBar(Lcom/autonavi/minimap/bundle/msgbox/entity/AmapMessage;Ljava/lang/String;Ljava/lang/String;Lcom/autonavi/minimap/bundle/msgbox/listener/ActivityEventCallback;)V|home_operate_bar 首页运营活动条
Lcom/autonavi/bundle/uitemplate/mapwidget/widget/msgtip/MsgTipMapWidget;->showTips(Lcom/autonavi/minimap/bundle/msgbox/entity/AmapMessage;I)Z|home_operate_bar 地图消息气泡(return false)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessages(Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager$GetMessageListener;ZLxf2;)V|home_msg_filter 消息拉取(2参重载)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessages(Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager$GetMessageListener;ZZLxf2;)V|home_msg_filter 消息拉取(3参重载)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessages(Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager$GetMessageListener;ZZLxf2;Ljava/lang/String;)V|home_msg_filter 消息拉取(4参重载)
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->retrieveRemoteMessages(Ljava/util/Set;Ljava/lang/String;)V|home_msg_filter 远程营销消息拉取
Llt1;->start(Landroid/app/Activity;Lcom/autonavi/minimap/bundle/msgbox/entity/AmapMessage;)V|home_msg_filter 主图投放(接口IDeliveryDisplay实现类,混淆类)
Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge;->updateAppBadge(Ljava/lang/String;I)V|home_msg_filter 角标红点更新
Lcom/amap/bundle/badgesystem/ajxmodule/AjxModuleBadge;->setBadgeDisplay(Ljava/lang/String;Z)V|home_msg_filter 角标显示(强制false)
Lcom/autonavi/jni/badgesystem/BadgeSystemManager;->updateAppBadge(Ljava/lang/String;I)V|home_msg_filter 启动器角标
Lcom/autonavi/bundle/amaphome/components/searchbar/BaseSearchBar;->requestPreSetWord(ZLjava/lang/String;)V|search_hotword 搜索框热词请求(唯一重载)
Lcom/autonavi/bundle/uitemplate/preset/PreSetWordManager;->d(Ln55;)V|search_hotword 热词本地计数
Lcom/autonavi/bundle/uitemplate/preset/PreSetWordManager;->e(Ljava/lang/String;Ln55;)V|search_hotword 热词天擎曝光上报
Lcom/autonavi/bundle/uitemplate/ajx/ModuleSlideContainer;->responsePreSetWords(Ljava/lang/String;)V|search_hotword AJX热词回填(第二道保险)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendH5Log(Lcom/autonavi/minimap/alimama/param/H5LogRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|h5_ad_log H5广告监测(2参重载)
Lcom/autonavi/minimap/alimama/AlimamaRequestHolder;->sendH5Log(Lcom/autonavi/minimap/alimama/param/H5LogRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|h5_ad_log H5广告监测(3参重载)
Lcom/autonavi/minimap/basemap/floatinglayer/FloatingLayerManager;->a(Ljava/lang/String;Ljava/lang/String;Lorg/json/JSONObject;Lcom/autonavi/minimap/basemap/floatinglayer/FloatingLayerManager$b;)Ljava/lang/String;|map_float_layer 运营浮层注册(返回"")
La8;->run()V|msg_image_text 图文素材下载任务(混淆类,字符串锚定,SIG监控)
Lcom/amap/bundle/appupgrade/config/downloader/UpdateHintConfigDownloader;->a()V|popup_clean 升级提示配置下载
Lcx1;->addToQueue(Lcom/autonavi/bundle/amaphome/model/DialogConflictInfo;)Z|popup_clean 首页弹窗冲突队列(接口实现类,混淆类)
Lcom/autonavi/minimap/track/b;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/autonavi/minimap/entity/SplashButtonInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Le72;ZLjava/lang/String;)V|track_suppress 开屏曝光埋点
Lcom/autonavi/minimap/track/b;->f(Ljava/lang/String;Ljava/lang/String;Ljava/util/ArrayList;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Lcom/autonavi/minimap/entity/SplashButtonInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;ILjava/lang/String;Le72;ZLjava/lang/String;)V|track_suppress 开屏埋点聚合+三方监测
Lcom/autonavi/minimap/track/SplashLogManager;->a(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V|track_suppress 开屏日志上传
Lcom/ut/mini/UTTracker;->send(Ljava/util/Map;)V|track_suppress UT统计总出口
Lcom/ut/mini/UTTracker;->sendLog(Ljava/util/Map;ILjava/util/List;)V|track_suppress UT落盘/转发入口
Lcom/alipay/mobile/nebulacore/plugin/H5ShakePlugin;->handleEvent(Lcom/alipay/mobile/h5container/api/H5Event;Lcom/alipay/mobile/h5container/api/H5BridgeContext;)Z|shake_h5 H5摇一摇(只拦watchShake)
Lcom/alipay/mobile/aompdevice/shake/h5plugin/H5SensorPlugin;->registerGyroscopeListener(Lcom/alipay/mobile/h5container/api/H5Event;)Z|shake_h5 H5陀螺仪注册
Lcom/autonavi/nebulax/extensions/point/AMapAppStartShakePoint;->onInitialized()V|shake_h5 小程序摇一摇初始化
Lcom/autonavi/nebulax/utils/SensorManagerHelper;->onSensorChanged(Landroid/hardware/SensorEvent;)V|shake_h5 摇一摇监听回调
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->handlePush(Ljava/lang/String;)V|bg_msg_fetch 推送消息处理入口
Lcom/autonavi/minimap/bundle/msgbox/push/BackgroundMsgManager;->a(Lcom/autonavi/minimap/bundle/msgbox/push/IBackgroundMsgFetchListener;)V|bg_msg_fetch 后台营销消息拉取
Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->fetchMessageFromMainMap(IZLcom/autonavi/minimap/bundle/msgbox/api/IMsgboxService$MainMapUIUpdater;)V|bg_msg_fetch 主图消息拉取包装'

echo
echo "APK: $APK"
echo "SHA256(前16位): $APK_SHA"
echo "缓存: $CACHE"
# ── 版本校验：与清单实测版本比对，只警告不中断 ────────────────────
EXPECTED_VERSION="17.01.0.2012"
AAPT=""
BUILD_TOOLS="D:/Android/Sdk/build-tools"
if [ -d "$BUILD_TOOLS" ]; then
  newest_bt=$(ls -1 "$BUILD_TOOLS" 2>/dev/null | sort -V | tail -1)
  [ -n "$newest_bt" ] && [ -f "$BUILD_TOOLS/$newest_bt/aapt.exe" ] && AAPT="$BUILD_TOOLS/$newest_bt/aapt.exe"
fi
if [ -z "$AAPT" ] && command -v aapt >/dev/null 2>&1; then
  AAPT=$(command -v aapt)
fi
VERSION_NAME=""
if [ -n "$AAPT" ]; then
  VERSION_NAME=$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  echo "versionName: ${VERSION_NAME:-未知}  (aapt: $AAPT)"
  if [ "$VERSION_NAME" != "$EXPECTED_VERSION" ]; then
    echo
    echo "*********************** WARN ***********************"
    echo "  versionName=${VERSION_NAME:-未知}，与挂载点实测版本 $EXPECTED_VERSION 不一致！"
    echo "  方法签名/锚点可能已变，下面的 OK 结果仅供参考。"
    echo "****************************************************"
    echo
  fi
else
  echo "versionName: 跳过校验（找不到 aapt）"
fi
echo
printf '%-8s %-78s %s\n' "状态" "挂载点（完整签名）" "功能"
printf '%.0s-' {1..120}; echo

ok=0
sig=0
gone=0
while IFS='|' read -r full feat; do
  [ -z "$full" ] && continue
  # 完整描述符 = "<类描述符含尾分号>-><方法签名>"; 从 "->" 处劈开，
  # 注意不能用 ";->"：那个分号是类描述符自带的，劈掉就查不到类了。
  cls="${full%%->*}"
  cls_hit=$(count "SELECT count(*) FROM classes WHERE descriptor='$cls'") || exit 3
  if [ "${cls_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "GONE" "${full#*->}" "$feat"
    gone=$((gone + 1))
    continue
  fi
  mth_hit=$(count "SELECT count(*) FROM methods WHERE descriptor='$full'") || exit 3
  if [ "${mth_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (类在、签名变了)" "$feat"
    sig=$((sig + 1))
    continue
  fi
  # 混淆类字符串锚定 SIG 监控：签名在但方法体锚点丢了，运行时 DexKit 会定位失败
  anchor_note=""
  case "$full" in
    'Lze5;->attemptRequestData(IZ)V')
      anchor_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%不发送请求%'") || exit 3
      if [ "${anchor_hit:-0}" = "0" ]; then
        printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (锚点字符串丢失,需换锚)" "$feat"
        sig=$((sig + 1))
        continue
      fi
      anchor_note=" [锚点OK]"
      ;;
    'La8;->run()V')
      # 与运行时 Anchors.java 对齐：DexKit 的 addUsingString 是 AND 语义，
      # "invalid download item: "（含尾空格）与 "ImageTextMessageDownloader"
      # 两条必须同时命中；只查其中一条会把运行时挂不上的情况误判成 OK。
      anchor_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%invalid download item: %'") || exit 3
      anchor_cls_hit=$(count "SELECT count(*) FROM method_strings WHERE method='$full' AND value LIKE '%ImageTextMessageDownloader%'") || exit 3
      if [ "${anchor_hit:-0}" = "0" ] || [ "${anchor_cls_hit:-0}" = "0" ]; then
        printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (与运行时锚点不一致,需核对 Anchors.java)" "$feat"
        sig=$((sig + 1))
        continue
      fi
      anchor_note=" [锚点OK]"
      ;;
  esac
  printf '%-8s %-78s %s\n' "OK" "${full#*->}$anchor_note" "$feat"
  ok=$((ok + 1))
done <<EOF
$MOUNTS
EOF

total=$((ok + sig + gone))
echo
echo "汇总: 共 $total 项 — OK $ok / SIG $sig / GONE $gone"
if [ "$sig" -eq 0 ] && [ "$gone" -eq 0 ]; then
  echo "十九钩子挂载点全部存活。"
else
  echo "OK=还在；SIG=类在但签名/锚点变了；GONE=类没了。变的项要重新用 dexsql 定位。"
fi
