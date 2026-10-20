package com.quietmap.app.hooks;

import android.hardware.SensorEvent;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * shake_h5 H5 / 小程序摇一摇净化。
 *
 * dexsql 实测（高德 17.01.0.2012）：
 *
 * 1) com.alipay.mobile.nebulacore.plugin.H5ShakePlugin#handleEvent(H5Event,H5BridgeContext)Z
 *    同时处理 "vibrate"（振动反馈）与 "watchShake"（注册加速度传感器）。
 *    本钩子**只拦 watchShake**：命中时直接 return true（消费事件、不注册传感器），
 *    其余 action 原样 proceed —— 保留 H5 页面正常的振动能力。
 *    若整段 skipVoid 会连 vibrate 一起废掉，故用带参数的过滤 Hooker。
 *
 * 2) com.alipay.mobile.aompdevice.shake.h5plugin.H5SensorPlugin#registerGyroscopeListener(H5Event)Z
 *    私有注册方法，方法体从 SensorManager 取陀螺仪/加速度/磁场三个 sensor 并
 *    registerListener。返回常量 false（调用方 handleEvent 忽略返回值），不注册即无摇一摇。
 *
 * 3) com.autonavi.nebulax.extensions.point.AMapAppStartShakePoint#onInitialized()V
 *    小程序摇一摇扩展点的初始化：new SensorManagerHelper 并直接
 *    SensorManager.registerListener(...)。不调用即不创建/注册监听器。
 *    （dexsql 实测 onAppStart/onAppExit 只维护 appId→callback 的 HashMap，
 *    真正的传感器注册在 onInitialized，故只挂这一处。）
 *
 * 4) com.autonavi.nebulax.utils.SensorManagerHelper#onSensorChanged(SensorEvent)V
 *    监听回调本体，作为第二道保险不调用（SensorManagerHelper 本身没有
 *    "注册方法"，注册是 onInitialized 里直接调 SensorManager 完成的）。
 *
 * 红线：**不 hook android.hardware.SensorManager 框架类**（HookKit 框架类黑名单
 * 也会拒绝），只动业务层的注册/回调入口。
 */
final class ShakeH5Hooks {

    private static final String H5_SHAKE_PLUGIN =
            "com.alipay.mobile.nebulacore.plugin.H5ShakePlugin";
    private static final String H5_EVENT = "com.alipay.mobile.h5container.api.H5Event";
    private static final String H5_BRIDGE_CONTEXT =
            "com.alipay.mobile.h5container.api.H5BridgeContext";

    private static final String H5_SENSOR_PLUGIN =
            "com.alipay.mobile.aompdevice.shake.h5plugin.H5SensorPlugin";

    private static final String SHAKE_POINT =
            "com.autonavi.nebulax.extensions.point.AMapAppStartShakePoint";
    private static final String SENSOR_HELPER =
            "com.autonavi.nebulax.utils.SensorManagerHelper";

    /**
     * H5Event#getAction() 反射结果缓存（按运行时具体类）。
     * 空 Optional 是负缓存（该类没有/拿不到 getAction），避免每次事件都反复反射找方法。
     * 有界性：H5Event 具体子类在单个进程内通常唯一/极少，缓存规模可忽略。
     */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GET_ACTION =
            new ConcurrentHashMap<>();

    private static final Function<Class<?>, Optional<Method>> LOOKUP_GET_ACTION =
            new Function<Class<?>, Optional<Method>>() {
                @Override
                public Optional<Method> apply(Class<?> type) {
                    try {
                        return Optional.of(type.getMethod("getAction"));
                    } catch (Throwable t) {
                        return Optional.empty();
                    }
                }
            };

    private ShakeH5Hooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SHAKE_H5;
        try {
            installH5ShakePlugin(module, f, cl);
            installH5SensorPlugin(module, f, cl);
            installMiniProgramShake(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }

    /** H5 容器摇一摇：只吞 watchShake，保留 vibrate。 */
    private static void installH5ShakePlugin(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> plugin = HookKit.findClass(module, f, cl, H5_SHAKE_PLUGIN);
        Class<?> event = HookKit.findClass(module, f, cl, H5_EVENT);
        Class<?> bridge = HookKit.findClass(module, f, cl, H5_BRIDGE_CONTEXT);
        if (plugin == null || event == null || bridge == null) {
            return;
        }
        Method handleEvent = HookKit.findMethod(module, f, plugin,
                "handleEvent", event, bridge);
        HookKit.install(module, f, "H5ShakePlugin#handleEvent(watchShake)", handleEvent,
                watchShakeFilter(module, f, "H5ShakePlugin#handleEvent"));
    }

    /**
     * 过滤 Hooker：读取 H5Event#getAction()，命中 "watchShake" 时返回 true
     * （事件已消费、不执行原注册逻辑），其余 action 原样放行。
     * proceed 只在函数末尾调用一次；反射失败按未知 action 处理，fail-open。
     */
    private static XposedInterface.Hooker watchShakeFilter(final XposedModule module,
                                                           final Feature f,
                                                           final String label) {
        final String hitLine = f.key + " hit " + label + " watchShake";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object action = null;
                    try {
                        Object event = chain.getArg(0);
                        if (event != null) {
                            Optional<Method> getAction = GET_ACTION.computeIfAbsent(
                                    event.getClass(), LOOKUP_GET_ACTION);
                            if (getAction.isPresent()) {
                                action = getAction.get().invoke(event);
                            }
                        }
                    } catch (Throwable ignored) {
                        // 拿不到 action 就当普通事件放行
                    }
                    if ("watchShake".equals(action)) {
                        HookKit.hit(module, f, hitLine);
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.key + " intercept error " + label, t);
                }
                return chain.proceed();
            }
        };
    }

    /** 陀螺仪监听注册：不注册（返回 false，调用方不检查返回值）。 */
    private static void installH5SensorPlugin(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> plugin = HookKit.findClass(module, f, cl, H5_SENSOR_PLUGIN);
        Class<?> event = HookKit.findClass(module, f, cl, H5_EVENT);
        if (plugin == null || event == null) {
            return;
        }
        Method register = HookKit.findMethod(module, f, plugin,
                "registerGyroscopeListener", event);
        HookKit.install(module, f, "H5SensorPlugin#registerGyroscopeListener", register,
                HookKit.constBool(module, f,
                        "H5SensorPlugin#registerGyroscopeListener", false));
    }

    /** 小程序摇一摇扩展点：onInitialized 不调用 + 监听回调不调用。 */
    private static void installMiniProgramShake(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> point = HookKit.findClass(module, f, cl, SHAKE_POINT);
        if (point != null) {
            Method onInitialized = HookKit.findMethod(module, f, point, "onInitialized");
            HookKit.install(module, f, "AMapAppStartShakePoint#onInitialized", onInitialized,
                    HookKit.skipVoid(module, f, "AMapAppStartShakePoint#onInitialized"));
        }

        Class<?> helper = HookKit.findClass(module, f, cl, SENSOR_HELPER);
        if (helper != null) {
            Method onSensorChanged = HookKit.findMethod(module, f, helper,
                    "onSensorChanged", SensorEvent.class);
            HookKit.install(module, f, "SensorManagerHelper#onSensorChanged", onSensorChanged,
                    HookKit.skipVoid(module, f, "SensorManagerHelper#onSensorChanged"));
        }
    }
}
