package com.quietmap.app.hooks;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * notify_guide 通知权限引导净化（只拦通知权限，定位/相机等引导保持原样）。
 *
 * dexsql 实测（高德 17.01.0.2012）：
 *
 * 1) com.alipay.android.phone.mobilesdk.permission.guide.PermissionGuideFloatShow
 *    #showNotificationGuide(PermissionGuideRequest)V
 *    底部通知权限浮条。private static 方法，先 setAccessible 再 skipVoid：
 *    不展示浮条。
 *
 * 2) com.alipay.android.phone.mobilesdk.permission.guide.sys.SysPermissionGuideFloatMode
 *    #showFloatWithSysCore()V
 *    系统浮条主路径。字段 guidePermissionStr / permissionList 声明在父类
 *    SysPermissionGuideMode 上，反射读取；只有命中通知权限时才跳过，
 *    其余权限引导原样 proceed。
 *
 * 3) so2#i(Activity, JSONObject, String)V
 *    权限请求参数桥。JSON 的 permissionType 命中通知权限时，复制 JSON 并把
 *    permissionType 改写为 "location" 后 proceedWith —— 通知权限被降级为定位
 *    权限请求，页面仍走正常权限流程但不再索要通知权限；否则原样 proceed。
 *
 * isNotificationPermission：空/null 为 false；toUpperCase(Locale.ROOT) 后
 * 包含 POST_NOTIFICATIONS 或 NOTIFICATION 即命中。
 */
final class NotifyGuideHooks {

    private static final String FLOAT_SHOW =
            "com.alipay.android.phone.mobilesdk.permission.guide.PermissionGuideFloatShow";
    private static final String FLOAT_REQUEST =
            "com.alipay.android.phone.mobilesdk.permission.guide.PermissionGuideRequest";
    private static final String SYS_FLOAT_MODE =
            "com.alipay.android.phone.mobilesdk.permission.guide.sys.SysPermissionGuideFloatMode";
    private static final String SYS_GUIDE_MODE =
            "com.alipay.android.phone.mobilesdk.permission.guide.sys.SysPermissionGuideMode";

    private static final String PERMISSION_BRIDGE = "so2";
    private static final String FIELD_PERM_TYPE = "permissionType";
    private static final String PROXY_PERMISSION_TYPE = "location";
    private static final String FIELD_PERMISSION = "guidePermissionStr";
    private static final String FIELD_PERMISSION_LIST = "permissionList";

    /** 真机诊断日志限流窗口：同一 key 10 秒内最多一条。 */
    private static final long LOG_INTERVAL_MS = 10_000L;
    private static final int MAX_LOG_KEYS = 64;
    private static final Object LOG_LOCK = new Object();
    private static final ConcurrentMap<String, Long> LAST_LOG_MS = new ConcurrentHashMap<>();

    private NotifyGuideHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.NOTIFY_GUIDE;
        try {
            installBottomFloat(module, f, cl);
            installSysFloat(module, f, cl);
            installPermissionBridge(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 底部通知权限浮条：不展示。 */
    private static void installBottomFloat(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> show = HookKit.findClass(module, f, cl, FLOAT_SHOW);
        Class<?> request = HookKit.findClass(module, f, cl, FLOAT_REQUEST);
        if (show == null || request == null) {
            return;
        }
        Method guide = HookKit.findMethod(module, f, show,
                "showNotificationGuide", request);
        setAccessibleQuiet(guide);
        HookKit.install(module, f, "PermissionGuideFloatShow#showNotificationGuide", guide,
                skipVoidLogged(module, f,
                        "PermissionGuideFloatShow#showNotificationGuide"));
    }

    /** 系统浮条：只在通知权限引导时跳过，其余权限原样放行。 */
    private static void installSysFloat(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> mode = HookKit.findClass(module, f, cl, SYS_FLOAT_MODE);
        Class<?> base = HookKit.findClass(module, f, cl, SYS_GUIDE_MODE);
        if (mode == null || base == null) {
            return;
        }
        Field permission = fieldQuiet(base, FIELD_PERMISSION);
        Field permissionList = fieldQuiet(base, FIELD_PERMISSION_LIST);
        if (permission == null && permissionList == null) {
            HookKit.warn(module, f.id
                    + " skip: SysPermissionGuideMode permission fields not found");
            return;
        }
        Method core = HookKit.findMethod(module, f, mode, "showFloatWithSysCore");
        setAccessibleQuiet(core);
        HookKit.install(module, f, "SysPermissionGuideFloatMode#showFloatWithSysCore", core,
                notificationOnlySkip(module, f, permission, permissionList));
    }

    /** 权限请求参数桥：通知权限改写为定位权限。 */
    private static void installPermissionBridge(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> bridge = HookKit.findClass(module, f, cl, PERMISSION_BRIDGE);
        if (bridge == null) {
            return;
        }
        Method query = HookKit.findMethod(module, f, bridge, "i",
                Activity.class, JSONObject.class, String.class);
        HookKit.install(module, f, "so2#i", query,
                permissionTypeRewrite(module, f));
    }

    /**
     * so2#i 参数重写：仅 3 参且第 2 参为 JSONObject 时生效；
     * permissionType 命中通知权限才复制 JSON 并改写，否则原样 proceed。
     */
    private static XposedInterface.Hooker permissionTypeRewrite(final XposedModule module,
                                                                final Feature f) {
        final String detail = "so2#i";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    HookState.entry(f);
                    List<Object> args = chain.getArgs();
                    if (args != null && args.size() == 3
                            && args.get(1) instanceof JSONObject) {
                        JSONObject request = (JSONObject) args.get(1);
                        String type = request.optString(FIELD_PERM_TYPE, "");
                        if (isNotificationPermission(type)) {
                            JSONObject patched = rewriteNotificationPermission(request);
                            Object[] newArgs = new Object[]{args.get(0), patched, args.get(2)};
                            logThrottled("rewrite:" + type,
                                    "notify guide rewrite permissionType=" + type
                                            + " -> location");
                            HookKit.hit(module, f, logLine);
                            proceeded = true;
                            return chain.proceedWith(newArgs);
                        }
                        logThrottled("pass:" + type,
                                "notify guide pass permissionType=" + type);
                    } else {
                        logThrottled("pass:<no-json>",
                                "notify guide pass permissionType=<no-json>");
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * showFloatWithSysCore 的条件跳过 Hooker：读取父类字段判定是否通知权限引导，
     * 命中则不调用原方法；否则 proceed 恰好一次。
     */
    private static XposedInterface.Hooker notificationOnlySkip(final XposedModule module,
                                                               final Feature f,
                                                               final Field permission,
                                                               final Field permissionList) {
        final String detail = "SysPermissionGuideFloatMode#showFloatWithSysCore";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    Object self = chain.getThisObject();
                    boolean notification = isNotificationGuide(self, permission, permissionList);
                    logThrottled("fallback:" + detail,
                            "notify guide fallback " + detail
                                    + " invoked notification=" + notification);
                    if (notification) {
                        HookKit.hit(module, f, logLine);
                        return null;
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /** guidePermissionStr 或 permissionList 中任一权限命中通知权限即视为通知引导。 */
    private static boolean isNotificationGuide(Object self, Field permission,
                                               Field permissionList) {
        if (self == null) {
            return false;
        }
        if (permission != null) {
            try {
                Object value = permission.get(self);
                if (value instanceof String
                        && isNotificationPermission((String) value)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 字段读不到：继续检查 permissionList
            }
        }
        if (permissionList != null) {
            try {
                Object value = permissionList.get(self);
                if (value instanceof String[]) {
                    for (String item : (String[]) value) {
                        if (isNotificationPermission(item)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 字段读不到：按非通知引导处理
            }
        }
        return false;
    }

    /** 空/null 不命中；大写后包含 POST_NOTIFICATIONS 或 NOTIFICATION 即命中。 */
    static boolean isNotificationPermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            return false;
        }
        String upper = permission.toUpperCase(Locale.ROOT);
        return upper.contains("POST_NOTIFICATIONS") || upper.contains("NOTIFICATION");
    }

    /**
     * 复制 JSON 并把 permissionType 改写为 location；返回新对象，不改入参。
     * 纯函数，hooker 与单测共用。
     */
    static JSONObject rewriteNotificationPermission(JSONObject request) throws JSONException {
        if (request == null) {
            return null;
        }
        JSONObject patched = new JSONObject(request.toString());
        patched.put(FIELD_PERM_TYPE, PROXY_PERMISSION_TYPE);
        return patched;
    }

    /**
     * 底部浮条兜底挂载点：行为与 HookKit.skipVoid 一致（不调用原方法），
     * 额外打一条限流日志，便于真机判断到底走了哪条路径。
     */
    private static XposedInterface.Hooker skipVoidLogged(final XposedModule module,
                                                          final Feature f,
                                                          final String detail) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    logThrottled("fallback:" + detail,
                            "notify guide fallback " + detail + " invoked");
                    HookKit.hit(module, f, logLine);
                    return null;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 真机诊断日志限流：同一 key 10 秒内最多一条。
     * 日志/时钟异常一律吞掉，绝不影响 hook 行为。
     */
    private static void logThrottled(String key, String message) {
        try {
            long now = SystemClock.elapsedRealtime();
            synchronized (LOG_LOCK) {
                Long last = LAST_LOG_MS.get(key);
                if (last != null && now - last < LOG_INTERVAL_MS) {
                    return;
                }
                if (LAST_LOG_MS.size() >= MAX_LOG_KEYS) {
                    LAST_LOG_MS.clear();
                }
                LAST_LOG_MS.put(key, now);
            }
            Log.i(HookKit.TAG, message);
        } catch (Throwable ignored) {
            // 日志失败不影响 hook 行为
        }
    }

    private static Field fieldQuiet(Class<?> cls, String name) {
        try {
            Field field = cls.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setAccessibleQuiet(Method method) {
        if (method == null) {
            return;
        }
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
            // 个别 ROM 拒绝 setAccessible；仍按原方法尝试 hook
        }
    }
}
