package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * splash_shake 开屏摇一摇禁用（数据层，按钮随开屏一并消失）。
 *
 * SplashButtonInfo 的摇一摇字段全部返回空常量：
 *   getShakeGuide() → ""（引导文案/素材链接）
 *   getShakeTip()   → ""（摇一摇提示文案）
 *   isSpeedStrict() → false（摇一摇"速度严格"反作弊）
 * 服务端字段控制的按钮拿不到任何引导素材，摇一摇无从触发。
 * （可选的 getSpeedStrictThreshold()F 不挂：isSpeedStrict=false 已断严格模式，
 *   阈值改 0 语义反而可能降低触发门槛。）
 */
final class SplashShakeHooks {

    private static final String INFO = "com.autonavi.minimap.entity.SplashButtonInfo";

    private SplashShakeHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_SHAKE;
        try {
            Class<?> info = HookKit.findClass(module, f, cl, INFO);
            if (info == null) {
                return;
            }
            Method shakeGuide = HookKit.findMethod(module, f, info, "getShakeGuide");
            HookKit.install(module, f, "SplashButtonInfo#getShakeGuide", shakeGuide,
                    HookKit.constValue(module, f, "SplashButtonInfo#getShakeGuide", ""));

            Method shakeTip = HookKit.findMethod(module, f, info, "getShakeTip");
            HookKit.install(module, f, "SplashButtonInfo#getShakeTip", shakeTip,
                    HookKit.constValue(module, f, "SplashButtonInfo#getShakeTip", ""));

            Method speedStrict = HookKit.findMethod(module, f, info, "isSpeedStrict");
            HookKit.install(module, f, "SplashButtonInfo#isSpeedStrict", speedStrict,
                    HookKit.constBool(module, f, "SplashButtonInfo#isSpeedStrict", false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
