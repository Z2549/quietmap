package com.quietmap.app.hooks;

import android.util.Log;

import com.quietmap.app.Config;
import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.autonavi.minimap）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "QuietMap";

    private Hooks() {
    }

    public static void installAll(XposedModule module, ClassLoader cl) {
        Config config;
        try {
            config = Config.load(module);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "config load failed", t);
            return;
        }
        if (!config.anyEnabled()) {
            module.log(Log.INFO, TAG, "all features disabled, nothing to hook");
            return;
        }
        Report.start();
        for (Feature f : Feature.values()) {
            if (!config.enabled(f)) {
                module.log(Log.INFO, TAG, f.key + " disabled");
                continue;
            }
            HookState.enable(f);
            installOne(module, cl, f);
        }
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f) {
        try {
            switch (f) {
                case SPLASH_GATE:
                    SplashGateHooks.install(module, cl);
                    break;
                case SPLASH_REQUEST:
                    SplashRequestHooks.install(module, cl);
                    break;
                case SPLASH_SHAKE:
                    SplashShakeHooks.install(module, cl);
                    break;
                case BG_MSG_FETCH:
                    BgMsgFetchHooks.install(module, cl);
                    break;
                case BG_AWAKEN_REPORT:
                    BgAwakenReportHooks.install(module, cl);
                    break;
                case BG_AFC_TAOKE:
                    BgAfcTaokeHooks.install(module, cl);
                    break;
                case BG_UTDID_UPLOAD:
                    BgUtdidUploadHooks.install(module, cl);
                    break;
                case H5_INSTALL_APK:
                    H5InstallApkHooks.install(module, cl);
                    break;
                case POPUP_UPGRADE:
                    PopupUpgradeHooks.install(module, cl);
                    break;
                case HOME_BANNER:
                    HomeBannerHooks.install(module, cl);
                    break;
                case HOME_OPERATE_BAR:
                    HomeOperateBarHooks.install(module, cl);
                    break;
                case HOME_MSG_FILTER:
                    HomeMsgFilterHooks.install(module, cl);
                    break;
                case SEARCH_HOTWORD:
                    SearchHotwordHooks.install(module, cl);
                    break;
                case H5_AD_LOG:
                    H5AdLogHooks.install(module, cl);
                    break;
                case MAP_FLOAT_LAYER:
                    FloatLayerHooks.install(module, cl);
                    break;
                case MSG_IMAGE_TEXT:
                    MsgImageTextHooks.install(module, cl);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.key + " install threw", t);
            HookState.markNotFound(f, "安装异常");
        }
    }
}
