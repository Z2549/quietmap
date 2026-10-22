package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * home_operate_bar 首页运营活动条与地图消息气泡屏蔽。
 *
 * dexsql 实测（高德 17.01.0.2012）两个挂载点：
 *   1) OperateActivityMapWidget#refreshSplashBar(AmapMessage, String, String,
 *        ActivityEventCallback) —— 首页运营活动条（含 GIF/Lottie 运营动图）刷新入口，
 *        方法体读 AmapMessage.isAd（method_field_accesses using_type=get 实测）。
 *        不调用即不渲染运营条。
 *   2) MsgTipMapWidget#showTips(AmapMessage, int) 返回 boolean —— 地图消息气泡
 *        （跑马灯/单条蓝点提示）展示入口，同样读 AmapMessage.isAd。
 *        return false 即气泡不展示。
 *
 * 全部 skipVoid / constBool：两个都是纯展示层入口，被跳过不会崩，
 * 调用方拿到 false 走「不展示」分支。
 */
final class HomeOperateBarHooks {

    private static final String OPERATE_WIDGET =
            "com.autonavi.bundle.uitemplate.mapwidget.widget.activity.OperateActivityMapWidget";
    private static final String MSG_TIP_WIDGET =
            "com.autonavi.bundle.uitemplate.mapwidget.widget.msgtip.MsgTipMapWidget";

    private static final String AMAP_MESSAGE =
            "com.autonavi.minimap.bundle.msgbox.entity.AmapMessage";
    private static final String EVENT_CALLBACK =
            "com.autonavi.minimap.bundle.msgbox.listener.ActivityEventCallback";

    private HomeOperateBarHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HOME_OPERATE_BAR;
        try {
            Class<?> operateWidget = HookKit.findClass(module, f, cl, OPERATE_WIDGET);
            Class<?> message = HookKit.findClass(module, f, cl, AMAP_MESSAGE);
            Class<?> eventCallback = HookKit.findClass(module, f, cl, EVENT_CALLBACK);
            if (operateWidget != null && message != null && eventCallback != null) {
                Method refreshSplashBar = HookKit.findMethod(module, f, operateWidget,
                        "refreshSplashBar", message, String.class, String.class, eventCallback);
                HookKit.install(module, f, "OperateActivityMapWidget#refreshSplashBar",
                        refreshSplashBar,
                        HookKit.skipVoid(module, f, "OperateActivityMapWidget#refreshSplashBar"));
            }

            Class<?> msgTipWidget = HookKit.findClass(module, f, cl, MSG_TIP_WIDGET);
            if (msgTipWidget != null && message != null) {
                Method showTips = HookKit.findMethod(module, f, msgTipWidget,
                        "showTips", message, int.class);
                HookKit.install(module, f, "MsgTipMapWidget#showTips", showTips,
                        HookKit.constBool(module, f, "MsgTipMapWidget#showTips", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
