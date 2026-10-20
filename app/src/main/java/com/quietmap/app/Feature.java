package com.quietmap.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致；日志里的 featureId 也用它。
 *
 * 十九个开关全部默认开启：模块定位是「装上即净」，用户只在个别场景关掉单项。
 * 枚举顺序 = hook 安装顺序：开屏三键放最前（启动路径最敏感，尽早落钩），
 * 批 3 首页三键随后（首页路径，不参与启动链路），批 5 的弹窗/统计/摇一摇
 * 三键放最后（收尾净化，不参与启动链路）。
 */
public enum Feature {

    /** 开屏闸门（canShowSplash 返回 Boolean.FALSE + tryShowSplashView 不调用，双保险）。 */
    SPLASH_GATE("splash_gate", R.string.f_splash_gate_title, R.string.f_splash_gate_summary),
    /** 开屏广告请求断源（sendSplashScreen + Lze5#attemptRequestData 不调用）。 */
    SPLASH_REQUEST("splash_request", R.string.f_splash_request_title, R.string.f_splash_request_summary),
    /** 开屏摇一摇禁用（SplashButtonInfo 摇一摇字段返回空/false）。 */
    SPLASH_SHAKE("splash_shake", R.string.f_splash_shake_title, R.string.f_splash_shake_summary),
    /**
     * 后台拉取营销消息 + 亮屏触发源（MessageBoxManager#getMessageInBackground +
     * BackgroundMsgScreenReceiver）；另挂 handlePush、BackgroundMsgManager#a、
     * fetchMessageFromMainMap 三个推送处理/主图拉取入口。
     */
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
     * IDeliveryDisplay#start 不调用（主图投放）、
     * updateAppBadge 在 count>0 时不调用、count<=0 放行（清角标），
     * setBadgeDisplay 不调用；同一策略同时覆盖 AjxModuleBadge 与
     * 启动器角标路径 BadgeSystemManager#updateAppBadge。
     */
    HOME_MSG_FILTER("home_msg_filter", R.string.f_home_msg_filter_title, R.string.f_home_msg_filter_summary),
    /** 搜索热词断源（requestPreSetWord + PreSetWordManager e/d + AJX 回填，不动 sugg）。 */
    SEARCH_HOTWORD("search_hotword", R.string.f_search_hotword_title, R.string.f_search_hotword_summary),
    /** H5 广告点击/曝光监测断源（AlimamaRequestHolder#sendH5Log 全重载）。 */
    H5_AD_LOG("h5_ad_log", R.string.f_h5_ad_log_title, R.string.f_h5_ad_log_summary),
    /** 地图运营浮层断源（FloatingLayerManager#a 返回空串，不注册浮层）。 */
    MAP_FLOAT_LAYER("map_float_layer", R.string.f_map_float_layer_title, R.string.f_map_float_layer_summary),
    /** 营销图文消息素材下载断源（La8#run 不执行；类名兜底 a8 优先，DexKit 字符串锚定仅兜底）。 */
    MSG_IMAGE_TEXT("msg_image_text", R.string.f_msg_image_text_title, R.string.f_msg_image_text_summary),
    /**
     * 弹窗净化（升级提示配置 + 首页弹窗冲突队列）：
     * UpdateHintConfigDownloader#a 不调用、IDialogConflictMgr 实现类
     * Lcx1#addToQueue 返回 false。后者中风险（可能误伤业务弹窗），可单关。
     */
    POPUP_CLEAN("popup_clean", R.string.f_popup_clean_title, R.string.f_popup_clean_summary),
    /** 统计上报净化（开屏埋点 track.b e/f + SplashLogManager + UTTracker send/sendLog）。 */
    TRACK_SUPPRESS("track_suppress", R.string.f_track_suppress_title, R.string.f_track_suppress_summary),
    /** H5/小程序摇一摇净化（H5ShakePlugin watchShake + H5SensorPlugin + 小程序扩展点）。 */
    SHAKE_H5("shake_h5", R.string.f_shake_h5_title, R.string.f_shake_h5_summary);

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
