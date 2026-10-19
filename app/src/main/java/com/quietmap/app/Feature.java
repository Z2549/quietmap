package com.quietmap.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致；日志里的 featureId 也用它。
 *
 * 十二个开关全部默认开启：模块定位是「装上即净」，用户只在个别场景关掉单项。
 * 枚举顺序 = hook 安装顺序：开屏三键放最前（启动路径最敏感，尽早落钩），
 * 批 3 首页三键放最后（首页路径，不参与启动链路）。
 */
public enum Feature {

    /** 开屏闸门（canShowSplash 返回 Boolean.FALSE + tryShowSplashView 不调用，双保险）。 */
    SPLASH_GATE("splash_gate", R.string.f_splash_gate_title, R.string.f_splash_gate_summary),
    /** 开屏广告请求断源（sendSplashScreen + Lze5#attemptRequestData 不调用）。 */
    SPLASH_REQUEST("splash_request", R.string.f_splash_request_title, R.string.f_splash_request_summary),
    /** 开屏摇一摇禁用（SplashButtonInfo 摇一摇字段返回空/false）。 */
    SPLASH_SHAKE("splash_shake", R.string.f_splash_shake_title, R.string.f_splash_shake_summary),
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
    POPUP_UPGRADE("popup_upgrade", R.string.f_popup_upgrade_title, R.string.f_popup_upgrade_summary),
    /** 首页 Banner 断源（BannerRequestHolder#sendList 全重载 + BannerManager#b）。 */
    HOME_BANNER("home_banner", R.string.f_home_banner_title, R.string.f_home_banner_summary),
    /** 首页运营活动条与地图消息气泡（refreshSplashBar 不调用 + showTips 返回 false）。 */
    HOME_OPERATE_BAR("home_operate_bar", R.string.f_home_operate_bar_title, R.string.f_home_operate_bar_summary),
    /**
     * 首页消息红点体系（数据层断源，白名单式取舍）：
     * getMessages 全重载 + retrieveRemoteMessages 不调用（营销红点拉取断源）、
     * IDeliveryDisplay#start 不调用（主图投放）、updateAppBadge 不调用 +
     * setBadgeDisplay 强制 false（角标红点）。
     */
    HOME_MSG_FILTER("home_msg_filter", R.string.f_home_msg_filter_title, R.string.f_home_msg_filter_summary);

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
