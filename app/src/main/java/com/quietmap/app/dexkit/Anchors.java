package com.quietmap.app.dexkit;

/**
 * DexKit 扫描锚点定义（混淆类抗改版定位用）。
 * 严格禁止引用任何 androidx 类型。
 *
 * 锚点只取自 dexsql 对目标 APK 的实测（方法体 method_strings 反查），
 * 不来自任何第三方模块的代码或文档。
 */
public final class Anchors {

    private Anchors() {
    }

    /**
     * 开屏广告请求调度的特征字符串：Lze5;->attemptRequestData(IZ)V 方法体内
     * 含 "不发送请求：…" 系列日志串（dexsql method_strings 实测）。
     * 注意 Lye5;->run()V 也含 "不发送请求：拿不到锁" 一个同首串，
     * 所以锚定命中后必须再用方法名/参数消歧（见 DexKitLocator 的两次尝试）。
     */
    public static final String STR_SPLASH_REQ_NO_SEND = "不发送请求";
}
