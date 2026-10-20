package com.quietmap.app.dexkit;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * DexKit 动态扫描定位引擎（模式参考 cloudmusictool 的 dexkit 包，API 按 dexkit 2.3.0 实测签名写）。
 * 懒加载 DexKitBridge 并按 ClassLoader 缓存；当类名/方法名被混淆改名时按方法体字符串锚点定位。
 * 严格禁止引用任何 androidx 类型。
 *
 * 失败语义：任何一步失败都返回 null（调用方 fail-open skip），
 * 绝不向外抛——DexKit native 加载失败不得影响宿主进程。
 */
public final class DexKitLocator {

    private static final String TAG = "QuietMap";

    private static final Map<ClassLoader, DexKitBridge> sBridgeCache = new WeakHashMap<>();
    private static volatile String sModuleNativeDir = null;
    /** 非主进程（:locationservice/:widgetProvider）关闭 DexKit，避免 native 全 dex 扫描开销。 */
    private static volatile boolean sEnabled = true;

    private DexKitLocator() {
    }

    /** 模块 APK 的 nativeLibraryDir（libdexkit.so 解压位置），onModuleLoaded 时注入。 */
    public static void setModuleNativeDir(String dir) {
        sModuleNativeDir = dir;
    }

    /**
     * 由 {@code Hooks.installAll} 在安装前按进程设置：主进程 true，其余进程 false。
     * 必须在 {@code app.hooks} 包可见，故为 public。
     */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static synchronized DexKitBridge getBridge(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        DexKitBridge bridge = sBridgeCache.get(cl);
        if (bridge == null) {
            try {
                boolean loaded = false;
                if (sModuleNativeDir != null && !sModuleNativeDir.isEmpty()) {
                    try {
                        java.io.File soFile = new java.io.File(sModuleNativeDir, "libdexkit.so");
                        if (soFile.exists()) {
                            System.load(soFile.getAbsolutePath());
                            loaded = true;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "DexKit load from moduleNativeDir failed: " + t.getMessage());
                    }
                }
                if (!loaded) {
                    try {
                        System.loadLibrary("dexkit");
                    } catch (Throwable t) {
                        Log.w(TAG, "DexKit loadLibrary failed: " + t.getMessage());
                    }
                }
                bridge = DexKitBridge.create(cl, false);
                sBridgeCache.put(cl, bridge);
                Log.i(TAG, "DexKitBridge initialized for ClassLoader: " + cl);
            } catch (Throwable t) {
                Log.w(TAG, "DexKitBridge initialization failed: " + t.getMessage());
            }
        }
        return bridge;
    }

    /**
     * 按方法体字符串锚点定位方法。两次尝试：
     *  第一次：declaredClass(classHint) + name(methodName) + anchors；
     *  第二次（只要第一次未选出方法就执行）：只按 anchors 查询，不限制类名/方法名，
     *    但对候选做 returnType + paramTypes 强校验（锚点串可能被多个方法共用，
     *    例如 "不发送请求" 同时出现在 Lye5;->run()V，必须靠签名消歧）。
     *  第二次必须保留：第一次带 methodName 过滤，目标方法被改名后，第一次可能只查到
     *  旧名字下的无关方法（有候选但签名不符）；真正的目标不在第一次结果里，只能由
     *  不带 name 过滤的第二次全量扫描命中。因此第一次签名不符时也必须继续第二次。
     *  第一次不限制类名（classHint 为空），但仍会按 methodName + anchors 查询。
     */
    public static Method findMethod(ClassLoader cl, String classHint, String methodName,
                                    Class<?> returnType, Class<?>[] paramTypes, String... anchors) {
        if (!sEnabled) {
            // 非主进程直接跳过：调用方此前已尝试类名/方法名直取，DexKit 只是最后的兜底。
            Log.w(TAG, "dexkit disabled in non-main process");
            return null;
        }
        if (cl == null || anchors == null || anchors.length == 0) {
            return null;
        }
        boolean hasValidAnchor = false;
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                hasValidAnchor = true;
                break;
            }
        }
        if (!hasValidAnchor) {
            return null;
        }

        DexKitBridge bridge = getBridge(cl);
        if (bridge == null) {
            return null;
        }

        // 第一次：类名 + 方法名 + 锚点（当前版本类名还在时最准）
        try {
            FindMethod query1 = FindMethod.create();
            MethodMatcher matcher1 = MethodMatcher.create();
            if (classHint != null && !classHint.isEmpty()) {
                matcher1.declaredClass(classHint, StringMatchType.Equals, false);
            }
            if (methodName != null && !methodName.isEmpty()) {
                matcher1.name(methodName, StringMatchType.Equals, false);
            }
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher1.addUsingString(anchor, StringMatchType.Contains, false);
                }
            }
            query1.matcher(matcher1);
            MethodDataList list1 = bridge.findMethod(query1);
            if (list1 != null && !list1.isEmpty()) {
                Method m = selectMatchingMethod(cl, list1, returnType, paramTypes,
                        "attempt1", classHint, methodName);
                if (m != null) {
                    return m;
                }
                // 有候选但签名不符不能直接放弃：第一次带 name 过滤，改名后的目标方法
                // 不会出现在该候选集里，必须继续 attempt2（不带 name 过滤）才能兜住。
                Log.w(TAG, "DexKit findMethod attempt 1: " + list1.size()
                        + " candidate(s) for anchors on " + classHint + "#" + methodName
                        + " but none matched signature; rescanning without name filter");
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findMethod attempt 1 error: " + t.getMessage());
        }

        // 第二次：第一次未选出方法（查询为空 / 异常 / 签名不符）时按锚点全量查询。
        // 不能加 name/class 过滤：方法或类被混淆改名后，这是唯一的兜底路径。
        try {
            FindMethod query2 = FindMethod.create();
            MethodMatcher matcher2 = MethodMatcher.create();
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher2.addUsingString(anchor, StringMatchType.Contains, false);
                }
            }
            query2.matcher(matcher2);
            MethodDataList list2 = bridge.findMethod(query2);
            if (list2 != null && !list2.isEmpty()) {
                Method m = selectMatchingMethod(cl, list2, returnType, paramTypes,
                        "attempt2", classHint, methodName);
                if (m != null) {
                    return m;
                }
            } else {
                Log.w(TAG, "DexKit findMethod attempt 2: 0 candidates for anchors on "
                        + classHint + "#" + methodName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findMethod attempt 2 error: " + t.getMessage());
        }

        return null;
    }

    private static Method selectMatchingMethod(ClassLoader cl, MethodDataList list,
                                               Class<?> returnType, Class<?>[] paramTypes,
                                               String attemptLabel, String targetClass,
                                               String targetMethod) {
        int count = list.size();
        for (int i = 0; i < count; i++) {
            MethodData data = list.get(i);
            try {
                Method m = data.getMethodInstance(cl);
                if (returnType != null && !isReturnTypeCompatible(returnType, m.getReturnType())) {
                    Log.w(TAG, "DexKit " + attemptLabel + " candidate " + i + " ("
                            + m.getDeclaringClass().getName() + "#" + m.getName()
                            + ") returnType mismatch: expected " + returnType.getName()
                            + ", got " + m.getReturnType().getName() + "; discarded");
                    continue;
                }
                if (paramTypes != null && !Arrays.equals(paramTypes, m.getParameterTypes())) {
                    Log.w(TAG, "DexKit " + attemptLabel + " candidate " + i + " ("
                            + m.getDeclaringClass().getName() + "#" + m.getName()
                            + ") paramTypes mismatch: expected " + Arrays.toString(paramTypes)
                            + ", got " + Arrays.toString(m.getParameterTypes()) + "; discarded");
                    continue;
                }
                Log.i(TAG, "DexKit " + attemptLabel + " chosen (candidates=" + count + ", chosen="
                        + m.getDeclaringClass().getName() + "#" + m.getName()
                        + ", targetHint=" + targetClass + "#" + targetMethod + ")");
                return m;
            } catch (Throwable t) {
                Log.w(TAG, "DexKit " + attemptLabel + " getMethodInstance error: " + t.getMessage());
            }
        }
        return null;
    }

    private static boolean isReturnTypeCompatible(Class<?> expected, Class<?> actual) {
        if (expected == null || actual == null) {
            return true;
        }
        if (expected == actual || expected.equals(actual)) {
            return true;
        }
        if (expected == boolean.class && actual == Boolean.class) return true;
        if (expected == Boolean.class && actual == boolean.class) return true;
        if (expected == int.class && actual == Integer.class) return true;
        if (expected == Integer.class && actual == int.class) return true;
        if (expected == long.class && actual == Long.class) return true;
        if (expected == Long.class && actual == long.class) return true;
        return false;
    }
}
