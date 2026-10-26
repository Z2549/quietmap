package com.quietmap.app.hooks;

import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * msg_center 消息中心净化。
 *
 * dexsql 实测（高德 17.01.0.2012）挂载点：
 *
 * 1) com.autonavi.bundle.msgbox.ajx.MessageBoxServiceImpl#getOnlineData
 *    (IGetMessageCallback, String)V
 *    在线消息拉取入口。不执行原方法，改为反射调用回调的
 *    onDataCallback(List, List, boolean, int)，传空列表 / false / 0：
 *    页面收到「无消息」而不是错误。回调为 null 或反射失败时 fail-open proceed。
 *
 * 2) com.autonavi.minimap.bundle.msgbox.impl.MsgboxService
 *    #getNewComingUnRead_MsgNumFromDB()I
 *    未读消息数直接返回 0，角标归零。
 *
 * 3) com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager#getAllLocalMessages()
 *    本地消息列表返回空 ArrayList（返回类型实测为 java.util.ArrayList）。
 *
 * 4) com.autonavi.bundle.msgbox.ajx.ModuleMessageBox#hasNewMessage(JsFunctionCallback)V
 *    消息页 AJX JS 实际调用的「有无新消息」桥。方法本身是 void，原实现
 *    new 一个 GetMessageListener 后调 MessageBoxManager#getMessages(listener, true, xf2)，
 *    回调结果由 ModuleMessageBox$c.onFinish 以字符串 "1"/"0" 回给 JS。
 *    这里不执行原方法，直接反射调用 JsFunctionCallback#callback(Object[])
 *    并传 "0"（无新消息），避免 JS 等待一个永远不会到达的回调。
 *
 * 与 home_msg_filter 的覆盖关系：
 * - HomeMsgFilterHooks 挂的是 MessageBoxManager#getMessages 三个重载；
 *   hasNewMessage 的原实现内部正是调用其中一个重载。两个开关同时开启时，
 *   getMessages 已被 skipVoid 挡住，hasNewMessage 的 listener 不会收到数据，
 *   属于「间接覆盖」。
 * - 但两个开关独立：只开 msg_center、关掉 home_msg_filter 时，getMessages 会
 *   正常执行，hasNewMessage 仍可能把 "1" 回给 JS。因此在 hasNewMessage 上单独
 *   加挂载点是必要的，不是重复 hook 同一个方法（Xposed 同方法只能一个 hook）。
 * - 该 hook 不调用 getMessages，也不写 mHasNewMessageJsFuncCallback 字段；
 *   JS 侧只依赖 callback 的 "0"。
 *
 * 可观测性（只读日志，不改行为）：
 * - 每个挂载点被调用时打 "msg center <label> invoked"；
 * - getOnlineData 空回调成功时打 "msg center getOnlineData suppressed"；
 * - hasNewMessage 回 "0" 时打 "msg center hasNewMessage=false"；
 * - 同一 key 10 秒内最多一条，避免消息页滚动时刷屏。
 *
 * 注意：账号安全、公交动态等真实通知也会一并隐藏，设置页可单独关闭本项。
 * 临时探针见 {@link Batch6Probe}，release 默认不安装。
 */
final class MsgCenterHooks {

    private static final String MSG_BOX_SERVICE_IMPL =
            "com.autonavi.bundle.msgbox.ajx.MessageBoxServiceImpl";
    private static final String GET_MESSAGE_CALLBACK =
            "com.autonavi.bundle.msgbox.ajx.IGetMessageCallback";
    private static final String MSGBOX_SERVICE =
            "com.autonavi.minimap.bundle.msgbox.impl.MsgboxService";
    private static final String MESSAGE_BOX_MANAGER =
            "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager";
    private static final String MODULE_MESSAGE_BOX =
            "com.autonavi.bundle.msgbox.ajx.ModuleMessageBox";
    private static final String JS_FUNCTION_CALLBACK =
            "com.autonavi.minimap.ajx3.core.JsFunctionCallback";

    /** 同一 key 的观测日志最小间隔。 */
    static final long LOG_WINDOW_MS = 10_000L;

    private static final LogThrottle THROTTLE = new LogThrottle();

    private MsgCenterHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MSG_CENTER;
        try {
            installOnlineData(module, f, cl);
            installUnreadCount(module, f, cl);
            installLocalMessages(module, f, cl);
            installHasNewMessage(module, f, cl);
            Batch6Probe.install(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 在线消息：直接回调空数据，不执行原方法。 */
    private static void installOnlineData(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> impl = HookKit.findClass(module, f, cl, MSG_BOX_SERVICE_IMPL);
        Class<?> callback = HookKit.findClass(module, f, cl, GET_MESSAGE_CALLBACK);
        if (impl == null || callback == null) {
            return;
        }
        Method getOnlineData = HookKit.findMethod(module, f, impl,
                "getOnlineData", callback, String.class);
        Method onDataCallback = HookKit.findMethodQuiet(callback, "onDataCallback",
                List.class, List.class, boolean.class, int.class);
        if (onDataCallback == null) {
            HookKit.warn(module, f.id
                    + " skip: IGetMessageCallback#onDataCallback signature not found");
            return;
        }
        setAccessibleQuiet(module, f, onDataCallback);
        HookKit.install(module, f, "MessageBoxServiceImpl#getOnlineData", getOnlineData,
                emptyDataCallback(module, f, onDataCallback));
    }

    /** 未读角标：固定返回 0。 */
    private static void installUnreadCount(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> service = HookKit.findClass(module, f, cl, MSGBOX_SERVICE);
        Method unread = HookKit.findMethod(module, f, service,
                "getNewComingUnRead_MsgNumFromDB");
        HookKit.install(module, f, "MsgboxService#getNewComingUnRead_MsgNumFromDB", unread,
                zeroInt(module, f, "MsgboxService#getNewComingUnRead_MsgNumFromDB"));
    }

    /** 本地消息列表：固定返回空 ArrayList。 */
    private static void installLocalMessages(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> manager = HookKit.findClass(module, f, cl, MESSAGE_BOX_MANAGER);
        if (manager == null) {
            return;
        }
        Method allLocal = HookKit.findMethod(module, f, manager, "getAllLocalMessages");
        HookKit.install(module, f, "MessageBoxManager#getAllLocalMessages", allLocal,
                emptyArrayList(module, f, "MessageBoxManager#getAllLocalMessages"));
    }

    /**
     * AJX 消息页「有无新消息」桥：hasNewMessage(JsFunctionCallback)V。
     * 必须同时拿到回调接口的 callback(Object[]) 才能把 "0" 回给 JS；
     * 回调方法缺失时跳过并 warn，不挂一个永远不会回调的钩子。
     */
    private static void installHasNewMessage(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> moduleMessageBox = HookKit.findClass(module, f, cl, MODULE_MESSAGE_BOX);
        Class<?> jsCallback = HookKit.findClass(module, f, cl, JS_FUNCTION_CALLBACK);
        if (moduleMessageBox == null || jsCallback == null) {
            return;
        }
        Method hasNewMessage = HookKit.findMethod(module, f, moduleMessageBox,
                "hasNewMessage", jsCallback);
        Method callback = HookKit.findMethodQuiet(jsCallback, "callback", Object[].class);
        if (callback == null) {
            HookKit.warn(module, f.id
                    + " skip: JsFunctionCallback#callback(Object[]) signature not found");
            return;
        }
        setAccessibleQuiet(module, f, callback);
        HookKit.install(module, f, "ModuleMessageBox#hasNewMessage", hasNewMessage,
                noNewMessage(module, f, callback));
    }

    /**
     * setAccessible 在个别 ROM 上会抛 SecurityException；这里只记日志，
     * 方法本身是 public 时仍然可以 invoke，真失败会在 intercept 内 fail-open。
     */
    private static void setAccessibleQuiet(XposedModule module, Feature f, Method method) {
        try {
            method.setAccessible(true);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " setAccessible failed: "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
        }
    }

    /**
     * getOnlineData Hooker：取第 1 参回调，反射回调空数据后返回 null（不 proceed）；
     * 回调为 null 时放行原方法，任何异常在未 proceed 前都 fail-open。
     *
     * 回调自身抛异常（InvocationTargetException）时不再 proceed：回调可能已经
     * 执行了一半，proceed 会让原方法再回调一次，调用方收到两次结果。
     * 只有反射调用失败（参数形状/访问权限）才 fail-open。
     */
    private static XposedInterface.Hooker emptyDataCallback(final XposedModule module,
                                                            final Feature f,
                                                            final Method onDataCallback) {
        final String detail = "MessageBoxServiceImpl#getOnlineData";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(module, f, "getOnlineData");
                boolean proceeded = false;
                try {
                    Object callback = chain.getArg(0);
                    if (callback == null) {
                        proceeded = true;
                        return chain.proceed();
                    }
                    onDataCallback.invoke(callback, emptyOnlineDataArgs());
                    hitQuiet(module, f, logLine);
                    logThrottled(module, "getOnlineData:suppressed",
                            "msg center getOnlineData suppressed");
                    return null;
                } catch (InvocationTargetException t) {
                    if (proceeded) {
                        throw t;
                    }
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    HookKit.error(module, f.id + " callback threw " + detail, cause);
                    // 回调已进入业务侧，不能再执行原方法造成双重回调。
                    return null;
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

    /** 未读角标 Hooker：返回 0，不 proceed。 */
    private static XposedInterface.Hooker zeroInt(final XposedModule module,
                                                  final Feature f,
                                                  final String detail) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(module, f, "getNewComingUnRead_MsgNumFromDB");
                try {
                    hitQuiet(module, f, logLine);
                    return Integer.valueOf(0);
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 本地消息 Hooker：每次返回新的空 ArrayList。
     * 不复用同一个实例，避免调用方 add/remove 后污染下一次调用的结果。
     */
    private static XposedInterface.Hooker emptyArrayList(final XposedModule module,
                                                         final Feature f,
                                                         final String detail) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(module, f, "getAllLocalMessages");
                try {
                    hitQuiet(module, f, logLine);
                    return new ArrayList<>();
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * hasNewMessage Hooker：不执行原方法（原方法会调 getMessages 并异步回调 "1"/"0"），
     * 直接回调 JS "0"。void 方法返回 null。callback 为 null 时只抑制，不回调。
     *
     * 回调自身抛异常（InvocationTargetException）时不再 proceed：回调可能已经
     * 执行了一半，proceed 会让原方法再回调一次，JS 侧会收到两次结果。
     * 只有反射调用失败（参数形状/访问权限）才 fail-open。
     */
    private static XposedInterface.Hooker noNewMessage(final XposedModule module,
                                                       final Feature f,
                                                       final Method callback) {
        final String detail = "ModuleMessageBox#hasNewMessage";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(module, f, "hasNewMessage");
                try {
                    Object jsCallback = chain.getArg(0);
                    if (jsCallback != null) {
                        invokeNoNewMessage(callback, jsCallback);
                    }
                    hitQuiet(module, f, logLine);
                    logThrottled(module, "hasNewMessage:false",
                            "msg center hasNewMessage=false");
                    return null;
                } catch (InvocationTargetException t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    HookKit.error(module, f.id + " callback threw " + detail, cause);
                    // 回调已进入 JS 侧，不能再执行原方法造成双重回调。
                    return null;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 反射调用 JsFunctionCallback#callback(Object[])，传入单个 Object[] 实参。
     *
     * 关键：参数本身是数组，Method#invoke 的可变参数必须再包一层
     * `new Object[]{ noNewMessageCallbackArgs() }`；直接传 `Object[]{"0"}`
     * 会被当成一个 String 实参，必然抛 IllegalArgumentException。
     * 抽成包内静态方法，单测可用真实 callback(Object[]) 接口验证形状。
     */
    static void invokeNoNewMessage(Method callback, Object jsCallback)
            throws InvocationTargetException, IllegalAccessException {
        callback.invoke(jsCallback, new Object[]{noNewMessageCallbackArgs()});
    }

    /** getOnlineData 的空回调参数；每次新建列表，防止回调修改后影响下一次。 */
    static Object[] emptyOnlineDataArgs() {
        return new Object[]{
                new ArrayList<>(),
                new ArrayList<>(),
                Boolean.FALSE,
                Integer.valueOf(0)
        };
    }

    /** hasNewMessage 回调给 JS 的「无新消息」参数；实测 JS 侧收字符串 "0"/"1"。 */
    static Object[] noNewMessageCallbackArgs() {
        return new Object[]{"0"};
    }

    /** 命中计数与采样日志；诊断失败不影响返回值。 */
    private static void hitQuiet(XposedModule module, Feature f, String logLine) {
        try {
            HookKit.hit(module, f, logLine);
        } catch (Throwable ignored) {
            // 计数/日志失败不影响 hook
        }
    }

    /** 记录入口计数与 invoked 日志；任何异常都吞掉，绝不影响挂载点行为。 */
    private static void noteEntry(XposedModule module, Feature f, String label) {
        try {
            HookState.entry(f);
        } catch (Throwable ignored) {
            // 诊断计数失败不影响 hook
        }
        logThrottled(module, "invoked:" + label, "msg center " + label + " invoked");
    }

    /** 限流写日志；日志失败不影响 hook。 */
    private static void logThrottled(XposedModule module, String key, String line) {
        try {
            if (THROTTLE.allow(key, System.currentTimeMillis())) {
                module.log(Log.INFO, HookKit.TAG, line);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响 hook
        }
    }

    /**
     * 纯 JVM 可测的固定窗口限流器：同一 key 在 LOG_WINDOW_MS 内只允许一次。
     * 时钟回拨（now < previous）时允许一次并重置窗口，避免永久静默。
     */
    static final class LogThrottle {

        private final ConcurrentMap<String, AtomicLong> last = new ConcurrentHashMap<>();

        boolean allow(String key, long nowMs) {
            AtomicLong stamp = last.get(key);
            if (stamp == null) {
                AtomicLong created = new AtomicLong(Long.MIN_VALUE);
                AtomicLong previous = last.putIfAbsent(key, created);
                stamp = previous != null ? previous : created;
            }
            while (true) {
                long previous = stamp.get();
                if (previous != Long.MIN_VALUE
                        && nowMs >= previous
                        && nowMs - previous < LOG_WINDOW_MS) {
                    return false;
                }
                if (stamp.compareAndSet(previous, nowMs)) {
                    return true;
                }
            }
        }
    }
}
