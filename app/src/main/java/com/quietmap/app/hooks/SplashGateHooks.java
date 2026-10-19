package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * splash_gate 开屏闸门（双保险，官方 NO_SPLASH 分支 + 展示层兜底）。
 *
 * 1) BootBizDataPreloaderImpl#canShowSplash() 返回 java.lang.Boolean.FALSE ——
 *    g.o 内 invoke-interface 后与 Boolean.FALSE 比较走 SplashFinishReason.NO_SPLASH
 *    分支，产品自带的"不展示开屏"路径，最优雅。dexsql 实测该方法在实现类上直接声明，
 *    返回类型是 java.lang.Boolean 对象：绝不能返回 null（调用方会拆箱 NPE），
 *    一律返回装箱常量 Boolean.FALSE。
 * 2) SplashScreenServiceImpl#tryShowSplashView(int, String) 不调用 —— 展示层兜底，
 *    开屏展示唯一入口（接口 ISplashScreenService），失败最多不显示开屏。
 *
 * 红线：不 hook com.autonavi.minimap.g（g.o 318 指令大方法）与 SplashFrequencyController。
 */
final class SplashGateHooks {

    private static final String PRELOADER = "com.autonavi.bundle.amaphome.impl.BootBizDataPreloaderImpl";
    private static final String SPLASH_SERVICE = "com.autonavi.minimap.impl.SplashScreenServiceImpl";

    private SplashGateHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_GATE;
        try {
            Class<?> preloader = HookKit.findClass(module, f, cl, PRELOADER);
            Method canShowSplash = HookKit.findMethod(module, f, preloader, "canShowSplash");
            // 返回类型是对象 java.lang.Boolean：装箱常量 FALSE，绝不返回 null。
            HookKit.install(module, f, "BootBizDataPreloaderImpl#canShowSplash", canShowSplash,
                    HookKit.constValue(module, f, "BootBizDataPreloaderImpl#canShowSplash",
                            Boolean.FALSE));

            Class<?> service = HookKit.findClass(module, f, cl, SPLASH_SERVICE);
            Method tryShowSplashView = HookKit.findMethod(module, f, service,
                    "tryShowSplashView", int.class, String.class);
            HookKit.install(module, f, "SplashScreenServiceImpl#tryShowSplashView",
                    tryShowSplashView,
                    HookKit.skipVoid(module, f, "SplashScreenServiceImpl#tryShowSplashView"));
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
