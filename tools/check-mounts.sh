#!/usr/bin/env bash
# 挂载点体检：高德升级后跑这个，30 秒内知道六个钩子的挂载点还活着几个。
#
# 用法（Git Bash）：
#   bash tools/check-mounts.sh [apk路径] [缓存输出目录]
#
# 例：
#   bash tools/check-mounts.sh
#   bash tools/check-mounts.sh "E:\download\base(5).apk"
#
# 依赖：E:\tools\dexsql\dexsql.exe（首次会为这个 apk 建缓存，约 40 秒）
# 缓存默认写仓库内 tools/.dexsql-cache/（不往 APK 目录写东西）。
#
# 判定规则：
#   OK   = 类和方法都在，且方法完整签名没变，hook 大概还能挂上
#   GONE = 类不存在了（R8/升级改了名），需要重新定位
#   SIG  = 类还在但方法签名变了（改名 / 参数变化），看差异再决定
#
# 挂载点清单与 app/src/main/java/com/quietmap/app/hooks/ 下的常量一一对应，
# 完整签名取自 dexsql 对高德 17.01.0.2012 的实测。

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

[ -z "$OUT" ] && OUT="$SCRIPT_DIR/.dexsql-cache"
mkdir -p "$OUT"
CACHE="$OUT/cache.dxc2"

if [ ! -f "$CACHE" ]; then
  echo "== 首次为该 apk 建 DexSQL 缓存（约 40 秒）..."
  "$DEXSQL" prepare-cache --input "$APK" --output "$OUT" --trusted-input >/dev/null 2>&1
  [ -f "$CACHE" ] || { echo "建缓存失败" >&2; exit 1; }
fi

q() { "$DEXSQL" query-cache --cache-file "$CACHE" --trusted-input --sql "$1" 2>/dev/null; }

count() {
  q "$1" | grep -oE '^\| [0-9]+' | tr -d '| ' | head -1
}

# 挂载点清单：完整方法描述符|功能
# 完整描述符 = 类描述符 + ";->" + 方法名 + 参数/返回值 JNI 签名，与 dexsql 的
# methods.descriptor 列同构，直接全等比对即可判 SIG。
MOUNTS='Lcom/autonavi/minimap/bundle/msgbox/util/MessageBoxManager;->getMessageInBackground(Landroid/app/Activity;Z)V|bg_msg_fetch 后台拉取营销消息
Lcom/autonavi/minimap/bundle/msgbox/push/BackgroundMsgScreenReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V|bg_msg_fetch 亮屏触发源
Lcom/autonavi/minimap/awaken/AwakenRequestHolder;->sendAwakenPage(Lcom/autonavi/minimap/awaken/param/AwakenPageRequest;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|bg_awaken_report 唤端上报(2参)
Lcom/autonavi/minimap/awaken/AwakenRequestHolder;->sendAwakenPage(Lcom/autonavi/minimap/awaken/param/AwakenPageRequest;Lcom/autonavi/minimap/falcon/base/a;Lcom/amap/bundle/aosservice/response/AosResponseCallback;)V|bg_awaken_report 唤端上报(3参补充)
Lcom/taobao/flowcustoms/afc/taoke/AfcTkManager;->registerTkFlag(Landroid/content/Context;)V|bg_afc_taoke 淘宝客SDK注册
Lcom/ta/audid/upload/UtdidUploadTask;->upload()V|bg_utdid_upload 指纹上传
Lcom/ta/audid/upload/UtdidUploadTask;->uploadFromCache()V|bg_utdid_upload 指纹缓存上传
Lcom/ta/audid/upload/UtdidUploadTask;->reqServer(Ljava/lang/String;)Z|bg_utdid_upload 服务端交换
Lcom/alipay/mobile/common/info/DeviceInfo;->installApk(Ljava/lang/String;)V|h5_install_apk H5静默安装
Lcom/amap/bundle/appupgrade/AppUpgradeController;->q(Lcom/amap/bundle/appupgrade/e;ZZ)Z|popup_upgrade 升级弹窗'

echo
echo "APK: $APK"
echo "缓存: $CACHE"
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
  cls_hit=$(count "SELECT count(*) FROM classes WHERE descriptor='$cls'")
  if [ "${cls_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "GONE" "${full#*->}" "$feat"
    gone=$((gone + 1))
    continue
  fi
  mth_hit=$(count "SELECT count(*) FROM methods WHERE descriptor='$full'")
  if [ "${mth_hit:-0}" = "0" ]; then
    printf '%-8s %-78s %s\n' "SIG" "${full#*->}  (类在、签名变了)" "$feat"
    sig=$((sig + 1))
    continue
  fi
  printf '%-8s %-78s %s\n' "OK" "${full#*->}" "$feat"
  ok=$((ok + 1))
done <<EOF
$MOUNTS
EOF

total=$((ok + sig + gone))
echo
echo "汇总: 共 $total 项 — OK $ok / SIG $sig / GONE $gone"
if [ "$sig" -eq 0 ] && [ "$gone" -eq 0 ]; then
  echo "六钩子挂载点全部存活。"
else
  echo "OK=还在；SIG=类在但签名变了；GONE=类没了。变的项要重新用 dexsql 定位。"
fi
