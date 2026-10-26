package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * ops_activity 运营活动弹窗净化。
 *
 * dexsql 实测（高德 17.01.0.2012）：
 *
 * 1) com.amap.bundle.cloudconfig.appinit.AppInitServiceImpl#getOperationModuleSwitch(Z)Z
 *    运营活动模块总开关。返回常量 false，模块不启用，打车页 88VIP 等运营弹窗
 *    不再加载。接口 IAppInitService 上也有同名方法，只挂实现类。
 *
 * 2) com.autonavi.minimap.bundle.activities.ActivitiesService#openOpetationsActivities
 *    活动打开入口，3 参与 4 参（末尾 boolean）两个重载都挂。命中时不调用原方法
 *    （skipVoid），活动页不再打开；异常回退 proceed 恰好一次（fail-open）。
 *
 * 只动业务层入口，不 hook 框架类。会连带屏蔽导航结束页的加油/停车活动，
 * 因此设置页可单独关闭本项。
 */
final class OpsActivityHooks {

    private static final String APP_INIT_IMPL =
            "com.amap.bundle.cloudconfig.appinit.AppInitServiceImpl";
    private static final String ACTIVITIES_SERVICE =
            "com.autonavi.minimap.bundle.activities.ActivitiesService";
    private static final String ABSTRACT_BASE_PAGE =
            "com.autonavi.map.fragmentcontainer.page.AbstractBasePage";

    private OpsActivityHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.OPS_ACTIVITY;
        try {
            installModuleSwitch(module, f, cl);
            installOpenActivities(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 运营活动模块总开关：固定返回 false。 */
    private static void installModuleSwitch(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> impl = HookKit.findClass(module, f, cl, APP_INIT_IMPL);
        Method switchMethod = HookKit.findMethod(module, f, impl,
                "getOperationModuleSwitch", boolean.class);
        HookKit.install(module, f, "AppInitServiceImpl#getOperationModuleSwitch", switchMethod,
                HookKit.constBool(module, f,
                        "AppInitServiceImpl#getOperationModuleSwitch", false));
    }

    /** 活动打开入口：3 参与 4 参重载都拦截，不执行原方法。 */
    private static void installOpenActivities(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> service = HookKit.findClass(module, f, cl, ACTIVITIES_SERVICE);
        Class<?> page = HookKit.findClass(module, f, cl, ABSTRACT_BASE_PAGE);
        if (service == null || page == null) {
            return;
        }
        Method open3 = HookKit.findMethod(module, f, service,
                "openOpetationsActivities", page, String.class, String.class);
        HookKit.install(module, f, "ActivitiesService#openOpetationsActivities(3p)", open3,
                HookKit.skipVoid(module, f,
                        "ActivitiesService#openOpetationsActivities(3p)"));
        Method open4 = HookKit.findMethod(module, f, service,
                "openOpetationsActivities", page, String.class, String.class, boolean.class);
        HookKit.install(module, f, "ActivitiesService#openOpetationsActivities(4p)", open4,
                HookKit.skipVoid(module, f,
                        "ActivitiesService#openOpetationsActivities(4p)"));
    }
}
