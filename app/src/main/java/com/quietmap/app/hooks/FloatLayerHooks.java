package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * map_float_layer 地图运营浮层断源（首页地图不再注册运营挂件）。
 *
 * dexsql 实测（高德 17.01.0.2012）：
 * FloatingLayerManager#a(String, String, JSONObject, FloatingLayerManager$b)String
 * 是浮层注册入口（返回注册结果的 key）。
 *
 * 返回常量选 ""（空串）而不是 null —— 依据来自两个调用点的 smali：
 *   1) Lcom/autonavi/minimap/basemap/floatinglayer/b;->f(Activity, JSONObject)V
 *      把返回值直接 JSONObject.put("key", result) 后 JsAdapter.callJs 回传前端。
 *      org.json 的 put(name, null) 会删除该键，JS 侧拿不到 "key" 可能走异常分支；
 *      返回 "" 保持键存在、值为空串，不改变控制流。
 *   2) Lcom/autonavi/bundle/amaphome/components/appback/AppBackManager$2;->a(AppBackResponse)V
 *      完全不使用返回值（a(...) 后直接 return）。
 * 因此 "" 对两处调用点都安全。
 *
 * 单例 d() 不 hook：调用点会立即解引用（d().a(...)、d().b 等），返回 null 必 NPE。
 *
 * 只挂 a(...)：constValue 返回 ""，不 proceed；异常回退 proceed 恰好一次（fail-open）。
 */
final class FloatLayerHooks {

    private static final String MANAGER =
            "com.autonavi.minimap.basemap.floatinglayer.FloatingLayerManager";
    private static final String LISTENER =
            "com.autonavi.minimap.basemap.floatinglayer.FloatingLayerManager$b";

    private FloatLayerHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MAP_FLOAT_LAYER;
        try {
            Class<?> manager = HookKit.findClass(module, f, cl, MANAGER);
            Class<?> listener = HookKit.findClass(module, f, cl, LISTENER);
            if (manager == null || listener == null) {
                return;
            }
            Method register = HookKit.findMethod(module, f, manager, "a",
                    String.class, String.class, org.json.JSONObject.class, listener);
            HookKit.install(module, f, "FloatingLayerManager#a", register,
                    HookKit.constValue(module, f, "FloatingLayerManager#a", ""));
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
