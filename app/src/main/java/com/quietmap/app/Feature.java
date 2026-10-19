package com.quietmap.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致；日志里的 featureId 也用它。
 *
 * 六个开关全部默认开启：模块定位是「装上即净」，用户只在个别场景关掉单项。
 */
public enum Feature {

    /** 后台拉取营销消息 + 亮屏触发源（MessageBoxManager + BackgroundMsgScreenReceiver）。 */
    BG_MSG_FETCH("bg_msg_fetch", R.string.f_bg_msg_fetch_title, R.string.f_bg_msg_fetch_summary),
    /** 唤起端上报（AwakenRequestHolder#sendAwakenPage）。 */
    BG_AWAKEN_REPORT("bg_awaken_report", R.string.f_bg_awaken_report_title, R.string.f_bg_awaken_report_summary),
    /** 淘宝客广告 SDK 冷启动注册（AfcTkManager#registerTkFlag）。 */
    BG_AFC_TAOKE("bg_afc_taoke", R.string.f_bg_afc_taoke_title, R.string.f_bg_afc_taoke_summary),
    /** 设备指纹上传（UtdidUploadTask upload/uploadFromCache/reqServer）。 */
    BG_UTDID_UPLOAD("bg_utdid_upload", R.string.f_bg_utdid_upload_title, R.string.f_bg_utdid_upload_summary),
    /** H5 静默下载安装 APK（DeviceInfo#installApk）。 */
    H5_INSTALL_APK("h5_install_apk", R.string.f_h5_install_apk_title, R.string.f_h5_install_apk_summary),
    /** 升级弹窗（AppUpgradeController#q）。 */
    POPUP_UPGRADE("popup_upgrade", R.string.f_popup_upgrade_title, R.string.f_popup_upgrade_summary);

    public final String key;
    public final int titleRes;
    public final int summaryRes;
    /** 编译期内置默认值：无 PROP_CAP_REMOTE 或 remote prefs 读不到时用，全部开启。 */
    public final boolean defaultEnabled = true;

    Feature(String key, int titleRes, int summaryRes) {
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
    }
}
