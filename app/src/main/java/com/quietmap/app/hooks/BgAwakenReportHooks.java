package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_awaken_report 唤端上报断源。
 *
 * AwakenRequestHolder#sendAwakenPage 不调用即断唤起端上报。
 * dexsql 实测（高德 17.01.0.2012）该方法名下有两个重载，任务清单给的是两参版：
 *   sendAwakenPage(AwakenPageRequest, AosResponseCallback)
 *   sendAwakenPage(AwakenPageRequest, com.autonavi.minimap.falcon.base.a, AosResponseCallback)
 * 两个都挂（同一方法只挂一个 hook，两个重载算两个方法），避免三参版漏网继续上报。
 */
final class BgAwakenReportHooks {

    private static final String CLAZZ = "com.autonavi.minimap.awaken.AwakenRequestHolder";
    private static final String P0 = "com.autonavi.minimap.awaken.param.AwakenPageRequest";
    private static final String P1 = "com.amap.bundle.aosservice.response.AosResponseCallback";
    private static final String P1_ALT = "com.autonavi.minimap.falcon.base.a";

    private BgAwakenReportHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_AWAKEN_REPORT;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Class<?> p0 = HookKit.findClass(module, f, cl, P0);
            Class<?> p1 = HookKit.findClass(module, f, cl, P1);
            Class<?> p1Alt = HookKit.findClass(module, f, cl, P1_ALT);

            if (cls != null && p0 != null && p1 != null) {
                Method two = HookKit.findMethod(module, f, cls,
                        "sendAwakenPage", p0, p1);
                HookKit.install(module, f, "sendAwakenPage(2)", two,
                        HookKit.skipVoid(module, f, "sendAwakenPage(2)"));
            }
            if (cls != null && p0 != null && p1 != null && p1Alt != null) {
                Method three = HookKit.findMethod(module, f, cls,
                        "sendAwakenPage", p0, p1Alt, p1);
                HookKit.install(module, f, "sendAwakenPage(3)", three,
                        HookKit.skipVoid(module, f, "sendAwakenPage(3)"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
