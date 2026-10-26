package com.quietmap.app.hooks;

import android.util.Log;
import android.view.View;

import com.quietmap.app.Feature;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 批 6 真机验证用临时探针（默认关闭）。
 *
 * 原本位于 MsgCenterHooks#installTempProbe：遍历消息中心三个候选类的全部
 * declared methods，命中即打 "PROBE hit &lt;类简名&gt;#&lt;方法名&gt;"；另外 hook
 * android.view.View 的全部构造器，首次见到某个 View 子类时打
 * "PROBE view &lt;类名&gt;"。用于真机确认消息中心实际走的类/方法。
 *
 * release 路径不安装：DEBUG_PROBE 为编译期常量 false，install 直接返回。
 * 后续真机验证时把 DEBUG_PROBE 临时改为 true 即可恢复完整探针行为：
 * - 方法探针会跳过 {@link #ALREADY_HOOKED} 中的正式挂载点，因为 Xposed
 *   同一方法只能挂一个 hook；
 * - View 构造器探针会 hook android.view.View 的全部构造器，只用于短时定位
 *   AJX 列表/列表项的实际 View 类，定位完必须改回 false，不要留在日常构建里。
 */
final class Batch6Probe {

    private static final boolean DEBUG_PROBE = false;

    private static final String TAG = "QuietMap";

    private static final String[] TARGETS = {
            "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager",
            "com.autonavi.minimap.bundle.msgbox.impl.MsgboxService",
            "com.autonavi.bundle.msgbox.ajx.ModuleMessageBox"
    };

    /**
     * 正式钩子已经占用的方法：Xposed 同一方法只能挂一个 hook，
     * 探针必须跳过，否则 DEBUG_PROBE=true 时这些方法只会记录 hook 失败。
     * 按「类简名#方法名」匹配，覆盖所有重载。
     */
    private static final Set<String> ALREADY_HOOKED = new HashSet<>(Arrays.asList(
            "MessageBoxManager#getMessages",
            "MessageBoxManager#retrieveRemoteMessages",
            "MessageBoxManager#getAllLocalMessages",
            "MessageBoxManager#getMessageInBackground",
            "MessageBoxManager#handlePush",
            "MessageBoxManager#fetchMessageFromMainMap",
            "MsgboxService#getNewComingUnRead_MsgNumFromDB",
            "ModuleMessageBox#hasNewMessage"
    ));

    private Batch6Probe() {
    }

    static void install(XposedModule module, Feature f, ClassLoader cl) {
        if (DEBUG_PROBE) {
            installMethodProbes(module, f, cl);
            installViewCtorProbe(module, f);
            Log.i(TAG, "PROBE installed for " + Arrays.toString(TARGETS));
        }
    }

    /** 消息中心三个候选类的全部方法：命中打日志后原样 proceed。 */
    private static void installMethodProbes(XposedModule module, Feature f, ClassLoader cl) {
        for (String name : TARGETS) {
            Class<?> cls = HookKit.findClass(module, f, cl, name);
            if (cls == null) {
                continue;
            }
            for (Method m : cls.getDeclaredMethods()) {
                try {
                    if (m.getName().startsWith("PROBE")) {
                        continue;
                    }
                    String label = cls.getSimpleName() + "#" + m.getName();
                    if (ALREADY_HOOKED.contains(label)) {
                        Log.i(TAG, "PROBE skip already hooked " + label);
                        continue;
                    }
                    HookKit.install(module, f, "PROBE" + label, m, methodProbe(label));
                } catch (Throwable ignored) {
                    // 单个方法探针失败不影响其余方法
                }
            }
        }
    }

    private static XposedInterface.Hooker methodProbe(final String label) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Log.i(TAG, "PROBE hit " + label);
                } catch (Throwable ignored) {
                    // 日志失败不影响原方法
                }
                return chain.proceed();
            }
        };
    }

    /** View 构造器探针：记录首个实例化的非框架 View 子类。 */
    private static void installViewCtorProbe(XposedModule module, Feature f) {
        for (Constructor<?> ctor : View.class.getDeclaredConstructors()) {
            try {
                module.hook(ctor)
                        .setId(f.id + "#PROBE ViewCtor" + ctor.getParameterCount())
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(viewCtorProbe());
            } catch (Throwable t) {
                Log.i(TAG, "PROBE ViewCtor unavailable: " + t);
            }
        }
    }

    private static XposedInterface.Hooker viewCtorProbe() {
        return new XposedInterface.Hooker() {
            private final Set<String> seen =
                    Collections.synchronizedSet(new HashSet<String>());

            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    String cls = chain.getThisObject().getClass().getName();
                    if (!cls.startsWith("android.")
                            && !cls.startsWith("androidx.")
                            && seen.add(cls)) {
                        Log.i(TAG, "PROBE view " + cls);
                    }
                } catch (Throwable ignored) {
                    // 探针只记录，不影响 View 构造
                }
                return chain.proceed();
            }
        };
    }
}
