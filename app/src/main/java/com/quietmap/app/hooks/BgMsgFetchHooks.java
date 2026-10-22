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
 * 五个挂载点，同一个开关（批 5 在原有两个之上补挂三个）：
 * 1) MessageBoxManager#getMessageInBackground(Activity, boolean) —— 亮屏时后台拉取营销消息，
 *    不调用即断「拉取」；
 * 2) BackgroundMsgScreenReceiver#onReceive(Context, Intent) —— 亮屏广播触发源，
 *    不调用即断「触发」；
 * 3) MessageBoxManager#handlePush(String) —— 推送消息处理入口：解析 "AmapPush-"
 *    前缀、写 PUSH_MSG prefs 并 post MessageBoxManager$c 拉取消息；不调用即不处理
 *    推送内容（**不影响推送通道注册**，见下）；
 * 4) BackgroundMsgManager#a(IBackgroundMsgFetchListener) —— 后台营销消息网络拉取
 *    （请求 /ws/msgbox/notice-msgs），不调用即断源；
 * 5) MessageBoxManager#fetchMessageFromMainMap(int, boolean, MainMapUIUpdater) ——
 *    主图消息拉取包装（内部转 getMessages），不调用。
 *
 * 推送通道说明（dexsql 实测）：
 * - PushManager / PushBindReceiver 属于系统推送通道（类族里同时存在小米 MiPushActivity、
 *   华为/荣耀、Heytap 等厂商 SDK）。PushManager#getPushAgent 返回 IPushAgent，
 *   PushBindReceiver 是绑定结果回调。**本批不 hook 它们**，避免影响推送 token 注册与
 *   系统通道绑定；只在上层 handlePush/拉取入口做净化。
 * - handlePush 只处理消息体，不参与通道注册；即使推送到达也只会被丢弃。
 *
 * 类或方法不存在时 fail-open 跳过（只记 skip），其余挂载点照常安装。
 */
final class BgMsgFetchHooks {

    private static final String MANAGER = "com.autonavi.minimap.bundle.msgbox.util.MessageBoxManager";
    private static final String RECEIVER = "com.autonavi.minimap.bundle.msgbox.push.BackgroundMsgScreenReceiver";
    private static final String BG_MSG_MANAGER =
            "com.autonavi.minimap.bundle.msgbox.push.BackgroundMsgManager";
    private static final String BG_FETCH_LISTENER =
            "com.autonavi.minimap.bundle.msgbox.push.IBackgroundMsgFetchListener";
    private static final String MAIN_MAP_UPDATER =
            "com.autonavi.minimap.bundle.msgbox.api.IMsgboxService$MainMapUIUpdater";

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

            installHandlePush(module, f, cl, manager);
            installBackgroundFetch(module, f, cl);
            installFetchFromMainMap(module, f, cl, manager);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 推送消息处理入口：不解析、不写 PUSH_MSG、不触发按 id 拉取。 */
    private static void installHandlePush(XposedModule module, Feature f, ClassLoader cl,
                                          Class<?> manager) {
        Method handlePush = HookKit.findMethod(module, f, manager, "handlePush", String.class);
        HookKit.install(module, f, "MessageBoxManager#handlePush", handlePush,
                HookKit.skipVoid(module, f, "MessageBoxManager#handlePush"));
    }

    /** 后台营销消息网络拉取：BackgroundMsgManager#a(listener) 不调用。 */
    private static void installBackgroundFetch(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> bg = HookKit.findClass(module, f, cl, BG_MSG_MANAGER);
        Class<?> listener = HookKit.findClass(module, f, cl, BG_FETCH_LISTENER);
        if (bg == null || listener == null) {
            return;
        }
        Method a = HookKit.findMethod(module, f, bg, "a", listener);
        HookKit.install(module, f, "BackgroundMsgManager#a", a,
                HookKit.skipVoid(module, f, "BackgroundMsgManager#a"));
    }

    /** 主图消息拉取包装：内部转 getMessages，单独断一道。 */
    private static void installFetchFromMainMap(XposedModule module, Feature f, ClassLoader cl,
                                                Class<?> manager) {
        Class<?> updater = HookKit.findClass(module, f, cl, MAIN_MAP_UPDATER);
        if (manager == null || updater == null) {
            return;
        }
        Method fetch = HookKit.findMethod(module, f, manager, "fetchMessageFromMainMap",
                int.class, boolean.class, updater);
        HookKit.install(module, f, "MessageBoxManager#fetchMessageFromMainMap", fetch,
                HookKit.skipVoid(module, f, "MessageBoxManager#fetchMessageFromMainMap"));
    }
}
