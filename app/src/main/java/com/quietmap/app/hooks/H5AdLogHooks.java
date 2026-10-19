package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * h5_ad_log H5 广告监测断源（H5 页面广告点击/曝光不再上报）。
 *
 * dexsql 实测（高德 17.01.0.2012）AlimamaRequestHolder#sendH5Log 有两个重载：
 *   1) sendH5Log(H5LogRequest, AosResponseCallback)                       —— 2 参
 *   2) sendH5Log(H5LogRequest, com.autonavi.minimap.falcon.base.a,
 *                AosResponseCallback)                                     —— 3 参
 * 请求参数含 page / session_id / click / displayfail / click_third / thirdurl，
 * 是典型 H5 广告点击与曝光监测。两个重载都不调用，监测链路断源。
 *
 * 全部 skipVoid：不调用原方法；任一挂载点定位失败只记 skip（fail-open）。
 */
final class H5AdLogHooks {

    private static final String HOLDER = "com.autonavi.minimap.alimama.AlimamaRequestHolder";
    private static final String REQUEST = "com.autonavi.minimap.alimama.param.H5LogRequest";
    /** dexsql 实测的 falcon 回调基类（与批 2 sendSplashScreen 同一包路径）。 */
    private static final String FALCON = "com.autonavi.minimap.falcon.base.a";
    private static final String CALLBACK = "com.amap.bundle.aosservice.response.AosResponseCallback";

    private H5AdLogHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.H5_AD_LOG;
        try {
            Class<?> holder = HookKit.findClass(module, f, cl, HOLDER);
            Class<?> request = HookKit.findClass(module, f, cl, REQUEST);
            Class<?> callback = HookKit.findClass(module, f, cl, CALLBACK);
            Class<?> falcon = HookKit.findClass(module, f, cl, FALCON);

            if (holder != null && request != null && callback != null) {
                Method send2 = HookKit.findMethod(module, f, holder,
                        "sendH5Log", request, callback);
                HookKit.install(module, f, "AlimamaRequestHolder#sendH5Log(2p)", send2,
                        HookKit.skipVoid(module, f, "AlimamaRequestHolder#sendH5Log(2p)"));
            }
            if (holder != null && request != null && falcon != null && callback != null) {
                Method send3 = HookKit.findMethod(module, f, holder,
                        "sendH5Log", request, falcon, callback);
                HookKit.install(module, f, "AlimamaRequestHolder#sendH5Log(3p)", send3,
                        HookKit.skipVoid(module, f, "AlimamaRequestHolder#sendH5Log(3p)"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
