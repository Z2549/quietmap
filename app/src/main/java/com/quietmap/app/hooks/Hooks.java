package com.quietmap.app.hooks;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.quietmap.app.Config;
import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.dexkit.DexKitLocator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.autonavi.minimap）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "QuietMap";

    /** 单个功能的安装器；与 {@link Feature} 一一对应，全部在静态块里显式注册。 */
    @FunctionalInterface
    interface Installer {
        void install(XposedModule module, ClassLoader cl);
    }

    /**
     * Feature → 安装器映射。用 EnumMap 保证 O(1) 且无哈希冲突；
     * 新增 Feature 忘记注册时 installOne 会打 ERROR 并标「未注册安装器」，不会静默漏挂。
     */
    private static final Map<Feature, Installer> INSTALLERS;

    static {
        EnumMap<Feature, Installer> m = new EnumMap<>(Feature.class);
        m.put(Feature.SPLASH_GATE, SplashGateHooks::install);
        m.put(Feature.SPLASH_REQUEST, SplashRequestHooks::install);
        m.put(Feature.SPLASH_SHAKE, SplashShakeHooks::install);
        m.put(Feature.BG_MSG_FETCH, BgMsgFetchHooks::install);
        m.put(Feature.BG_AWAKEN_REPORT, BgAwakenReportHooks::install);
        m.put(Feature.BG_AFC_TAOKE, BgAfcTaokeHooks::install);
        m.put(Feature.BG_UTDID_UPLOAD, BgUtdidUploadHooks::install);
        m.put(Feature.H5_INSTALL_APK, H5InstallApkHooks::install);
        m.put(Feature.POPUP_UPGRADE, PopupUpgradeHooks::install);
        m.put(Feature.HOME_BANNER, HomeBannerHooks::install);
        m.put(Feature.HOME_OPERATE_BAR, HomeOperateBarHooks::install);
        m.put(Feature.HOME_MSG_FILTER, HomeMsgFilterHooks::install);
        m.put(Feature.SEARCH_HOTWORD, SearchHotwordHooks::install);
        m.put(Feature.H5_AD_LOG, H5AdLogHooks::install);
        m.put(Feature.MAP_FLOAT_LAYER, FloatLayerHooks::install);
        m.put(Feature.MSG_IMAGE_TEXT, MsgImageTextHooks::install);
        m.put(Feature.POPUP_CLEAN, PopupCleanHooks::install);
        m.put(Feature.TRACK_SUPPRESS, TrackSuppressHooks::install);
        m.put(Feature.SHAKE_H5, ShakeH5Hooks::install);
        m.put(Feature.OPS_ACTIVITY, OpsActivityHooks::install);
        m.put(Feature.NOTIFY_GUIDE, NotifyGuideHooks::install);
        m.put(Feature.MSG_CENTER, MsgCenterHooks::install);
        INSTALLERS = Collections.unmodifiableMap(m);
    }

    private Hooks() {
    }

    /** 已注册安装器的 Feature 集合；包内可见，供 HooksTest 校验与枚举一一对应。 */
    static java.util.Set<Feature> installerKeys() {
        return INSTALLERS.keySet();
    }

    /**
     * 安装条件：未 blocked 且用户配置开启。blocked 功能即使配置为开启也不安装，
     * 保留编号与设置行位，但绝不在目标进程落钩。
     */
    static boolean shouldInstall(Feature f, boolean enabled) {
        return !f.blocked() && enabled;
    }

    /**
     * @param mainProcess 当前是否为主进程（进程名 == 包名）。非主进程（:locationservice、
     *                    :widgetProvider）关闭 DexKit：那里只需要类名/方法名兜底（Wave 1 已把
     *                    类名命中放在 DexKit 之前），不值得付出 native 全 dex 扫描成本。
     */
    public static void installAll(XposedModule module, ClassLoader cl, boolean mainProcess) {
        DexKitLocator.setEnabled(mainProcess);
        if (!mainProcess) {
            module.log(Log.INFO, TAG, "non-main process, dexkit disabled");
        }
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
        Report.start(modulePackage(module));
        for (Feature f : Feature.values()) {
            if (!shouldInstall(f, config.enabled(f))) {
                if (f.blocked()) {
                    module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                } else {
                    module.log(Log.INFO, TAG, f.id + " disabled");
                }
                continue;
            }
            installOne(module, cl, f);
        }
    }

    /** 模块包名决定文件兜底的 Android/media 子目录；取不到时 Report 用默认常量。 */
    private static String modulePackage(XposedModule module) {
        try {
            ApplicationInfo info = module.getModuleApplicationInfo();
            return info != null ? info.packageName : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f) {
        try {
            Installer installer = INSTALLERS.get(f);
            if (installer == null) {
                module.log(Log.ERROR, TAG, f.id + " installer missing");
                HookState.markNotFound(f, "未注册安装器");
                return;
            }
            installer.install(module, cl);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            HookState.markNotFound(f, "安装异常");
        }
    }
}
