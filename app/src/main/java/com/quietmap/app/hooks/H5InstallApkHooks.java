package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * h5_install_apk H5 静默下载安装断源。
 *
 * DeviceInfo#installApk(String) 不调用即断 H5 页面静默下载安装 APK。
 * dexsql 实测（高德 17.01.0.2012）签名：installApk(Ljava/lang/String;)V。
 */
final class H5InstallApkHooks {

    private static final String CLAZZ = "com.alipay.mobile.common.info.DeviceInfo";

    private H5InstallApkHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.H5_INSTALL_APK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Method m = HookKit.findMethod(module, f, cls, "installApk", String.class);
            HookKit.install(module, f, "DeviceInfo#installApk", m,
                    HookKit.skipVoid(module, f, "DeviceInfo#installApk"));
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
