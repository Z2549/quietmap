package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * msg_center 消息中心净化。
 *
 * dexsql 实测（高德 17.01.0.2012）：
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

    private MsgCenterHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MSG_CENTER;
        try {
            installOnlineData(module, f, cl);
            installUnreadCount(module, f, cl);
            installLocalMessages(module, f, cl);
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
        onDataCallback.setAccessible(true);
        HookKit.install(module, f, "MessageBoxServiceImpl#getOnlineData", getOnlineData,
                emptyDataCallback(module, f, onDataCallback));
    }

    /** 未读角标：固定返回 0。 */
    private static void installUnreadCount(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> service = HookKit.findClass(module, f, cl, MSGBOX_SERVICE);
        Method unread = HookKit.findMethod(module, f, service,
                "getNewComingUnRead_MsgNumFromDB");
        HookKit.install(module, f, "MsgboxService#getNewComingUnRead_MsgNumFromDB", unread,
                HookKit.constValue(module, f,
                        "MsgboxService#getNewComingUnRead_MsgNumFromDB", 0));
    }

    /** 本地消息列表：固定返回空 ArrayList。 */
    private static void installLocalMessages(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> manager = HookKit.findClass(module, f, cl, MESSAGE_BOX_MANAGER);
        if (manager == null) {
            return;
        }
        Method allLocal = HookKit.findMethod(module, f, manager, "getAllLocalMessages");
        HookKit.install(module, f, "MessageBoxManager#getAllLocalMessages", allLocal,
                HookKit.constValue(module, f,
                        "MessageBoxManager#getAllLocalMessages", new ArrayList<>()));
    }

    /**
     * getOnlineData Hooker：取第 1 参回调，反射回调空数据后返回 null（不 proceed）；
     * 回调为 null 时放行原方法，任何异常在未 proceed 前都 fail-open。
     */
    private static XposedInterface.Hooker emptyDataCallback(final XposedModule module,
                                                            final Feature f,
                                                            final Method onDataCallback) {
        final String detail = "MessageBoxServiceImpl#getOnlineData";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    Object callback = chain.getArg(0);
                    if (callback == null) {
                        proceeded = true;
                        return chain.proceed();
                    }
                    onDataCallback.invoke(callback,
                            new ArrayList<>(), new ArrayList<>(), Boolean.FALSE, 0);
                    HookKit.hit(module, f, logLine);
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
}
