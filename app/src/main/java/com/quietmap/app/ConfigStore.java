package com.quietmap.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模块 App 侧的开关读写。
 *
 * 必须写框架的 remote preferences：高德进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见。
 * 框架没绑定或没有 PROP_CAP_REMOTE 时才退回本地读取，同时 UI 会把开关置灰。
 *
 * <p>写入可靠性：remote prefs 的 commit 可能走 binder + 框架存储，不能放在主线程；
 * 所有写入统一提交到单线程执行器，并用 {@link SharedPreferences.Editor#commit()}
 * 的真实返回值判断是否成功。remote 不可用时直接返回 false，不再把本地
 * apply 当成“写成功”。
 *
 * <p>旧配置兜底：新 group {@code config} 与旧 group {@code features} 独立获取。
 * 即使新 group 绑定失败，也会尝试旧 group；读取顺序为新值 → 旧值 → 默认值。
 */
final class ConfigStore {

    private static final String TAG = "QuietMap";

    /** 旧版本可能使用的 remote prefs group；只读兜底，不再写入。 */
    static final String LEGACY_GROUP = "features";

    /**
     * 写入串行执行器：同一 key 的多次切换按提交顺序落盘，
     * 且 commit 的磁盘/binder 等待不占用主线程。
     */
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-config-write");
                t.setDaemon(true);
                return t;
            });

    /** 单个存储的读写抽象；单测可注入纯 JVM 实现。 */
    interface Store {
        /** 返回 null 表示 key 不存在或读取失败。 */
        Boolean get(String key);

        /** 只有真正 commit 成功才返回 true。 */
        boolean put(String key, boolean value);
    }

    /** 异步写入结果；在后台写线程回调。 */
    interface WriteCallback {
        void onResult(boolean committed);
    }

    private final Store mNewStore;
    private final Store mLegacyStore;
    private final boolean mRemote;

    /**
     * commit 失败后，框架的 RemotePreferences 内存 map 已被乐观更新（doUpdate 先于
     * doCommit）。这里记录最后一次确认提交的值，避免下一次 refresh 把未落盘的值
     * 当成已生效；key 从未存在时记入 {@link #mCommittedAbsent}。
     */
    private final ConcurrentMap<String, Boolean> mCommittedFallback = new ConcurrentHashMap<>();
    private final Set<String> mCommittedAbsent = ConcurrentHashMap.newKeySet();

    ConfigStore(Context context) {
        SharedPreferences remote = ServiceBridge.remotePreferences(Config.GROUP);
        // 旧 group 必须独立获取：新 group 绑定失败时，旧配置仍可能可读。
        SharedPreferences legacyRemote = ServiceBridge.remotePreferences(LEGACY_GROUP);

        Context app = context;
        if (context != null) {
            Context appContext = context.getApplicationContext();
            if (appContext != null) {
                app = appContext;
            }
        }
        SharedPreferences local = null;
        SharedPreferences legacyLocal = null;
        if (app != null) {
            try {
                local = app.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE);
            } catch (Throwable t) {
                logW("local prefs unavailable", t);
            }
            try {
                legacyLocal = app.getSharedPreferences(LEGACY_GROUP, Context.MODE_PRIVATE);
            } catch (Throwable t) {
                logW("legacy local prefs unavailable", t);
            }
        }

        mRemote = remote != null;
        mNewStore = prefsStore(remote != null ? remote : local);
        // 旧 group 的 remote/local 选择与新 group 是否可用无关。
        mLegacyStore = prefsStore(legacyRemote != null ? legacyRemote : legacyLocal);
    }

    /** 纯 JVM 测试入口：注入两个存储与 remote 可用性。 */
    ConfigStore(Store newStore, Store legacyStore, boolean remote) {
        mNewStore = newStore;
        mLegacyStore = legacyStore;
        mRemote = remote;
    }

    /** true = 写的是框架存储，目标进程能读到。 */
    boolean remote() {
        return mRemote;
    }

    /**
     * 新 group 优先 → 旧 group 兜底 → 默认值。
     * 两个存储的读取互相独立：新 group 读取失败不会跳过旧 group。
     */
    boolean get(String key, boolean def) {
        Boolean newValue = readCommitted(key);
        Boolean legacyValue = readQuietly(mLegacyStore, key);
        return resolve(newValue, legacyValue, def);
    }

    /**
     * 异步写开关。返回值只表示“是否已提交到写线程”：
     * remote 不可用、执行器拒绝任务时返回 false，调用方应立即提示并回滚 UI；
     * 真正是否落盘由 {@link WriteCallback#onResult(boolean)} 告知。
     */
    boolean put(String key, boolean value, WriteCallback callback) {
        if (!mRemote || mNewStore == null) {
            logW("config write rejected: remote prefs unavailable: " + key, null);
            return false;
        }
        try {
            WRITE_EXECUTOR.execute(() -> {
                // 先记下写前已确认的值：commit 失败时用它抵消 RemotePreferences
                // 内部 map 的乐观更新。
                Boolean before = readQuietly(mNewStore, key);
                boolean committed = false;
                try {
                    committed = mNewStore.put(key, value);
                } catch (Throwable t) {
                    logW("config write failed: " + key, t);
                }
                if (committed) {
                    mCommittedFallback.remove(key);
                    mCommittedAbsent.remove(key);
                } else {
                    if (before != null) {
                        mCommittedFallback.put(key, before);
                        mCommittedAbsent.remove(key);
                    } else {
                        mCommittedFallback.remove(key);
                        mCommittedAbsent.add(key);
                    }
                    logW("config write not committed: " + key, null);
                }
                if (callback != null) {
                    try {
                        callback.onResult(committed);
                    } catch (Throwable t) {
                        logW("config write callback failed", t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            logW("config write submit failed: " + key, t);
            return false;
        }
    }

    /** 纯决策：store 缺失或 service 换绑时必须重建。 */
    static boolean needsRebuild(boolean hasStore, boolean sameService) {
        return !hasStore || !sameService;
    }

    /** 纯决策：新值 > 旧值 > 默认值。 */
    static boolean resolve(Boolean newValue, Boolean legacyValue, boolean defaultValue) {
        if (newValue != null) {
            return newValue;
        }
        if (legacyValue != null) {
            return legacyValue;
        }
        return defaultValue;
    }

    private static Boolean readQuietly(Store store, String key) {
        if (store == null) {
            return null;
        }
        try {
            return store.get(key);
        } catch (Throwable t) {
            logW("config read failed: " + key, t);
            return null;
        }
    }

    /** 新 group 读取：commit 失败过的 key 用最后一次确认提交的值覆盖。 */
    private Boolean readCommitted(String key) {
        Boolean fallback = mCommittedFallback.get(key);
        if (fallback != null) {
            return fallback;
        }
        if (mCommittedAbsent.contains(key)) {
            return null;
        }
        return readQuietly(mNewStore, key);
    }

    private static Store prefsStore(SharedPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        return new Store() {
            @Override
            public Boolean get(String key) {
                try {
                    return prefs.contains(key) ? prefs.getBoolean(key, false) : null;
                } catch (Throwable t) {
                    logW("prefs read failed: " + key, t);
                    return null;
                }
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean put(String key, boolean value) {
                try {
                    // commit 的真实返回值是 remote 写入是否成功的唯一可靠信号。
                    // 调用方已在后台线程，不会阻塞主线程。
                    return prefs.edit().putBoolean(key, value).commit();
                } catch (Throwable t) {
                    logW("prefs write failed: " + key, t);
                    return false;
                }
            }
        };
    }

    private static void logW(String message, Throwable t) {
        try {
            if (t == null) {
                Log.w(TAG, message);
            } else {
                Log.w(TAG, message, t);
            }
        } catch (Throwable ignored) {
            // JVM 单测里 android.util.Log 是抛 Stub! 的桩，日志失败不能影响配置读写。
        }
    }
}
