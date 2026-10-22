package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_utdid_upload 设备指纹上传断源。
 *
 * UtdidUploadTask 三个挂载点，同一个开关：
 * 1) upload()V 与 2) uploadFromCache()V —— 不调用，断两条上传路径；
 * 3) reqServer(String)Z —— return false，断服务端交换（兜底）。
 * dexsql 实测（高德 17.01.0.2012）三个签名均一致。
 */
final class BgUtdidUploadHooks {

    private static final String CLAZZ = "com.ta.audid.upload.UtdidUploadTask";

    private BgUtdidUploadHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_UTDID_UPLOAD;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Method upload = HookKit.findMethod(module, f, cls, "upload");
            HookKit.install(module, f, "UtdidUploadTask#upload", upload,
                    HookKit.skipVoid(module, f, "UtdidUploadTask#upload"));

            Method fromCache = HookKit.findMethod(module, f, cls, "uploadFromCache");
            HookKit.install(module, f, "UtdidUploadTask#uploadFromCache", fromCache,
                    HookKit.skipVoid(module, f, "UtdidUploadTask#uploadFromCache"));

            Method reqServer = HookKit.findMethod(module, f, cls,
                    "reqServer", String.class);
            HookKit.install(module, f, "UtdidUploadTask#reqServer", reqServer,
                    HookKit.constBool(module, f, "UtdidUploadTask#reqServer", false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
