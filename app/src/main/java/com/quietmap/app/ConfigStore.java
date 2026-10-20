package com.quietmap.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 模块 App 侧的开关读写。
 *
 * 必须写框架的 remote preferences：高德进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见。
 * 框架没绑定或没有 PROP_CAP_REMOTE 时才退回本地，同时 UI 会把开关置灰。
 */
final class ConfigStore {

    private static final String TAG = "QuietMap";

    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;

    ConfigStore(Context context) {
        mRemote = ServiceBridge.remotePreferences(Config.GROUP);
        mLocal = context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE);
    }

    /** true = 写的是框架存储，目标进程能读到。 */
    boolean remote() {
        return mRemote != null;
    }

    boolean get(String key, boolean def) {
        try {
            return (mRemote != null ? mRemote : mLocal).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 写开关。成功（remote 或 local 的 apply 没抛异常）返回 true；
     * 抛异常返回 false，由调用方提示并回滚 UI，不能让失败静默吞掉。
     */
    boolean put(String key, boolean value) {
        try {
            SharedPreferences sp = mRemote != null ? mRemote : mLocal;
            sp.edit().putBoolean(key, value).apply();
            if (mRemote == null) {
                Log.w(TAG, "config written to LOCAL prefs (framework not bound): " + key);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "config write failed: " + key, t);
            return false;
        }
    }
}
