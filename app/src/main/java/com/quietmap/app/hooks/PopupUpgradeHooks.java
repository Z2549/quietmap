package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * popup_upgrade 升级弹窗断源。
 *
 * AppUpgradeController#q(e, boolean, boolean) 返回 false —— 弹窗决策返回「不弹」。
 * dexsql 实测（高德 17.01.0.2012）签名：
 *   q(Lcom/amap/bundle/appupgrade/e;ZZ)Z，与任务清单一致。
 * 参数类型 e 是包内混淆类，按名从目标 ClassLoader 加载。
 */
final class PopupUpgradeHooks {

    private static final String CLAZZ = "com.amap.bundle.appupgrade.AppUpgradeController";
    private static final String PARAM = "com.amap.bundle.appupgrade.e";

    private PopupUpgradeHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.POPUP_UPGRADE;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Class<?> e = HookKit.findClass(module, f, cl, PARAM);
            if (cls != null && e != null) {
                Method q = HookKit.findMethod(module, f, cls,
                        "q", e, boolean.class, boolean.class);
                HookKit.install(module, f, "AppUpgradeController#q", q,
                        HookKit.constBool(module, f, "AppUpgradeController#q", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
