package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * home_banner 首页 Banner 断源（Banner 拉取断源，页面布局自适应留空）。
 *
 * dexsql 实测（高德 17.01.0.2012）BannerRequestHolder 的 sendList 有两条签名：
 *   1) sendList(BannerListRequest, AosResponseCallback)                —— 2 参
 *   2) sendList(BannerListRequest, com.autonavi.minimap.falcon.base.a,
 *                AosResponseCallback)                                  —— 3 参（带 falcon 回调）
 * 批 2 经验：RequestHolder 常有多参重载，两个都挂才能保证断源。
 * 另外挂 BannerManager#b(String, boolean, boolean, OnLoadBannerListener) —— 首页
 * 运营位 Banner 的加载入口，同样不调用。
 *
 * 全部 skipVoid：不调用原方法即不发起任何 Banner 网络请求，本地无缓存自动留空。
 * 任一挂载点定位失败只记 skip，其余照常挂（fail-open）。
 */
final class HomeBannerHooks {

    private static final String HOLDER = "com.autonavi.minimap.banner.BannerRequestHolder";
    private static final String REQUEST = "com.autonavi.minimap.banner.param.BannerListRequest";
    /** dexsql 实测的 falcon 回调基类（与批 2 sendSplashScreen 3 参重载同一个包路径）。 */
    private static final String FALCON = "com.autonavi.minimap.falcon.base.a";
    private static final String CALLBACK = "com.amap.bundle.aosservice.response.AosResponseCallback";

    private static final String MANAGER = "com.autonavi.bundle.banner.manager.BannerManager";
    private static final String LISTENER = "com.autonavi.bundle.banner.manager.OnLoadBannerListener";

    private HomeBannerHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HOME_BANNER;
        try {
            Class<?> holder = HookKit.findClass(module, f, cl, HOLDER);
            Class<?> request = HookKit.findClass(module, f, cl, REQUEST);
            Class<?> callback = HookKit.findClass(module, f, cl, CALLBACK);
            Class<?> falcon = HookKit.findClass(module, f, cl, FALCON);

            if (holder != null && request != null && callback != null) {
                Method send2 = HookKit.findMethod(module, f, holder,
                        "sendList", request, callback);
                HookKit.install(module, f, "BannerRequestHolder#sendList(2p)", send2,
                        HookKit.skipVoid(module, f, "BannerRequestHolder#sendList(2p)"));
            }
            if (holder != null && request != null && falcon != null && callback != null) {
                Method send3 = HookKit.findMethod(module, f, holder,
                        "sendList", request, falcon, callback);
                HookKit.install(module, f, "BannerRequestHolder#sendList(3p)", send3,
                        HookKit.skipVoid(module, f, "BannerRequestHolder#sendList(3p)"));
            }

            Class<?> manager = HookKit.findClass(module, f, cl, MANAGER);
            Class<?> listener = HookKit.findClass(module, f, cl, LISTENER);
            if (manager != null && listener != null) {
                Method b = HookKit.findMethod(module, f, manager,
                        "b", String.class, boolean.class, boolean.class, listener);
                HookKit.install(module, f, "BannerManager#b", b,
                        HookKit.skipVoid(module, f, "BannerManager#b"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
