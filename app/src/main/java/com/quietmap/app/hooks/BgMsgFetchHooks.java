package com.quietmap.app.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * bg_msg_fetch 后台消息拉取净化。
 *
 * 两个挂载点，同一个开关：
 * 1) MessageBoxManager#getMessageInBackground(Activity, boolean) —— 亮屏时后台拉取营销消息，
 *    不调用即断「拉取」；
 * 2) BackgroundMsgScreenReceiver#onReceive(Context, Intent) —— 亮屏广播触发源，
 *    不调用即断「触发」。类或方法不存在时 fail-open 跳过（只记 skip）。
 */
final class BgMsgFetchHooks {

    private static final String MANAGER = "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager";
    private static final String RECEIVER = "com.autonavi.minimap.bundle.msgbox.push.BackgroundMsgScreenReceiver";

    private BgMsgFetchHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.BG_MSG_FETCH;
        try {
            Class<?> manager = HookKit.findClass(module, f, cl, MANAGER);
            Method getMessageInBackground = HookKit.findMethod(module, f, manager,
                    "getMessageInBackground", Activity.class, boolean.class);
            HookKit.install(module, f, "MessageBoxManager#getMessageInBackground",
                    getMessageInBackground,
                    HookKit.skipVoid(module, f, "MessageBoxManager#getMessageInBackground"));

            Class<?> receiver = HookKit.findClass(module, f, cl, RECEIVER);
            if (receiver != null) {
                Method onReceive = HookKit.findMethod(module, f, receiver,
                        "onReceive", Context.class, Intent.class);
                HookKit.install(module, f, "BackgroundMsgScreenReceiver#onReceive",
                        onReceive,
                        HookKit.skipVoid(module, f, "BackgroundMsgScreenReceiver#onReceive"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.key + " install aborted", t);
        }
    }
}
