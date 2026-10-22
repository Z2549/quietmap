package com.quietmap.app.hooks;

import android.content.Context;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_afc_taoke 淘宝客广告 SDK 冷启动断源。
 *
 * AfcTkManager#registerTkFlag(Context) 不调用即断淘宝客广告 SDK 的冷启动注册。
 * dexsql 实测（高德 17.01.0.2012）签名：registerTkFlag(Landroid/content/Context;)V。
 */
final class BgAfcTaokeHooks {

    private static final String CLAZZ = "com.taobao.flowcustoms.afc.taoke.AfcTkManager";

    private BgAfcTaokeHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_AFC_TAOKE;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Method m = HookKit.findMethod(module, f, cls,
                    "registerTkFlag", Context.class);
            HookKit.install(module, f, "AfcTkManager#registerTkFlag", m,
                    HookKit.skipVoid(module, f, "AfcTkManager#registerTkFlag"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
