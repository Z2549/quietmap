package com.quietmap.app.hooks;

import android.util.Log;

import androidx.annotation.Nullable;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 约束（与 docs/DEVELOPMENT.md 的稳定性条款一致）：
 * 1) Hooker 一律匿名内部类，不用 lambda（规避 bootclasspath desugar 问题）。
 * 2) 定位/hook 失败只记日志并返回 null/false，绝不向外抛（HookFailedError 继承 Error，框架不兜底）。
 * 3) 每个 intercept 体整段包在 try/catch(Throwable) 内，catch 后记日志并 proceed 放行原逻辑；
 *    任何路径 chain.proceed() 至多一次：抑制类钩子命中路径 0 次（不调原方法），回退路径恰好 1 次。
 * 4) 框架类黑名单：目标类名以 android./java./dalvik./androidx./kotlin. 开头则拒绝 hook。
 * 5) 命中日志固定 "&lt;featureId&gt; hit ..."，tag 固定 QuietMap。
 */
final class HookKit {

    static final String TAG = "QuietMap";

    private HookKit() {
    }

    /** 框架类黑名单：这些类由 bootclasspath / 框架提供，hook 它们会崩目标进程。 */
    static boolean isFrameworkClass(String name) {
        if (name == null) {
            return true;
        }
        return name.startsWith("android.")
                || name.startsWith("java.")
                || name.startsWith("dalvik.")
                || name.startsWith("androidx.")
                || name.startsWith("kotlin.");
    }

    static void info(XposedModule module, String msg) {
        module.log(Log.INFO, TAG, msg);
    }

    static void warn(XposedModule module, String msg) {
        module.log(Log.WARN, TAG, msg);
    }

    static void error(XposedModule module, String msg, Throwable t) {
        module.log(Log.ERROR, TAG, msg, t);
    }

    @Nullable
    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name) {
        if (isFrameworkClass(name)) {
            warn(module, f.id + " skip: framework class refused " + name);
            HookState.markNotFound(f, "框架类黑名单 " + name);
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            warn(module, f.id + " skip: class not found " + name);
            HookState.markNotFound(f, "类未找到 " + name);
            return null;
        }
    }

    @Nullable
    static Method findMethod(XposedModule module, Feature f, @Nullable Class<?> cls, String name,
                             Class<?>... params) {
        Method m = findMethodQuiet(cls, name, params);
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f, "方法未找到 " + name);
        }
        return m;
    }

    @Nullable
    static Method findMethodQuiet(@Nullable Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean install(XposedModule module, Feature f, String label, @Nullable Method m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        if (isFrameworkClass(m.getDeclaringClass().getName())) {
            warn(module, f.id + " hook failed " + label + ": framework class refused");
            HookState.markNotFound(f, "框架类黑名单 " + m.getDeclaringClass().getName());
            return false;
        }
        try {
            module.hook(m)
                    .setId(f.id + "#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            HookState.installed(f, label);
            info(module, f.id + " registered " + label);
            return true;
        } catch (Throwable t) {
            error(module, f.id + " hook failed " + label, t);
            HookState.markNotFound(f, "hook 失败 " + label);
            return false;
        }
    }

    /**
     * 命中计数并采样打日志。
     *
     * 参数 logLine 是**已预拼接好的完整日志行**（形如 "&lt;featureId&gt; hit ..."），
     * 由各工厂在构造 Hooker 时拼好，避免每次命中重复拼串。
     * 日志采样：仅前 5 次命中与每第 100 次命中真正写 logcat，计数每次照常累加。
     */
    static void hit(XposedModule module, Feature f, String logLine) {
        long count = HookState.hit(f);
        if (count <= 5 || count % 100 == 0) {
            module.log(Log.INFO, TAG, logLine);
        }
    }

    /**
     * void 方法：不 proceed 即原方法体不执行。
     * 回退路径（catch）proceed 恰好一次，把原逻辑放行，fail-open。
     */
    static XposedInterface.Hooker skipVoid(final XposedModule module, final Feature f,
                                           final String detail) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    hit(module, f, logLine);
                    return null;
                } catch (Throwable t) {
                    error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 布尔方法：直接给常量 false，不 proceed。
     * 回退路径（catch）proceed 恰好一次放行原逻辑，fail-open。
     */
    static XposedInterface.Hooker constBool(final XposedModule module, final Feature f,
                                            final String detail, final boolean value) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    hit(module, f, logLine);
                    return value ? Boolean.TRUE : Boolean.FALSE;
                } catch (Throwable t) {
                    error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 常量方法：直接返回给定装箱常量，不 proceed。
     * 用于对象/字符串返回类型（如 java.lang.Boolean、String）——
     * 常量必须非 null：调用方可能拆箱或直接解引用。
     * 回退路径（catch）proceed 恰好一次放行原逻辑，fail-open。
     */
    static XposedInterface.Hooker constValue(final XposedModule module, final Feature f,
                                             final String detail, final Object value) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    hit(module, f, logLine);
                    return value;
                } catch (Throwable t) {
                    error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }
}
