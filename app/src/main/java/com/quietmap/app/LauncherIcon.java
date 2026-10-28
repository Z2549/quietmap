package com.quietmap.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 桌面图标显隐控制。
 *
 * <p>承载 LAUNCHER 入口的是 manifest 里的 activity-alias（LauncherEntry），
 * 所以「隐藏图标」只是把该 alias 置为 disabled：MainActivity 本身仍带
 * {@code de.robv.android.xposed.category.MODULE_SETTINGS} 入口，依然能从 LSPosed
 * 管理器打开设置页把图标恢复回来，不会把自己锁在外面。
 *
 * <p>状态以系统的组件状态为唯一真相源（不落 SharedPreferences）：组件启用状态由
 * PackageManagerService 持久化，清 App 数据也不会丢；用 adb 改过之后回到关于页，
 * 开关显示也和桌面实际表现一致。
 */
public final class LauncherIcon {

    private static final String TAG = "QuietMap";

    /** 与 AndroidManifest.xml 中 activity-alias 的 name 保持一致（namespace 前缀，非 applicationId）。 */
    private static final String ALIAS_CLASS = "com.quietmap.app.LauncherEntry";

    private LauncherIcon() {
    }

    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), ALIAS_CLASS);
    }

    /** 桌面图标当前是否处于隐藏状态。 */
    public static boolean isHidden(Context context) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(alias(context));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                return true;
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "read launcher alias state failed", t);
        }
        return false;
    }

    /**
     * 切换桌面图标显隐。
     *
     * <p>DONT_KILL_APP 是必需的：改组件状态默认会连带杀掉本进程，设置页会当场闪退。
     *
     * @return true 表示系统已接受新的组件状态；false 表示写入失败，调用方应回滚 UI。
     */
    public static boolean setHidden(Context context, boolean hidden) {
        int state = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        boolean ok;
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    alias(context), state, PackageManager.DONT_KILL_APP);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "set launcher alias state failed", t);
            ok = false;
        }
        return ok;
    }
}
