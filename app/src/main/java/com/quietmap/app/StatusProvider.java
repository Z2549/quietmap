package com.quietmap.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 目标进程（高德）→ 模块 App 的诊断状态通道。
 *
 * 原名 ReportProvider，统一核心类命名后改为 StatusProvider；authority 仍为
 * ${applicationId}.qmreport，避免已安装版本与目标进程回传地址失效。
 *
 * 方向不能反过来：remote prefs 在目标进程只读，openRemoteFile 也是只读，
 * 所以「哪些钩子注册了 / 命中了多少次」只能由目标进程主动 call 进来。
 *
 * 安全边界：provider 必须 exported 才能被目标进程跨应用调用，因此每次 call 都校验
 * 调用方 UID 必须属于 com.autonavi.minimap；key 只接受 ReportProtocol 白名单，
 * value 有长度上限。不存任何敏感信息。
 *
 * key 形如 reg.bg_msg_fetch / hit.bg_msg_fetch / skip.bg_msg_fetch，
 * value 对应注册标签、命中计数、跳过原因。
 */
public final class StatusProvider extends ContentProvider {

    private static final String TAG = "QuietMap";

    public static final String AUTHORITY = "io.github.yigenhuobah.quietmap.qmreport";
    public static final String PREFS = "qm_report";

    /** Provider 通道最后写入时间；用于和文件兜底的 mtime 比较新旧。 */
    static final String PREFS_UPDATED_AT = ReportProtocol.PREFS_UPDATED_AT_KEY;
    /** 主进程最近一次运行的 session；变化时清理旧会话的 reg/skip/hit。 */
    static final String PREFS_SESSION = ReportProtocol.PREFS_SESSION_KEY;

    public static final String PREFIX_REG = ReportProtocol.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocol.PREFIX_HIT;
    public static final String PREFIX_SKIP = ReportProtocol.PREFIX_SKIP;

    /** 落盘时统一加 "v:" 前缀，避免与旧版本可能存在的同名键冲突。 */
    private static final String VALUE_PREFIX = "v:";

    /** 唯一允许写入诊断数据的宿主包名。 */
    private static final String TARGET_PACKAGE = "com.autonavi.minimap";

    private static final int MAX_ITEMS = 800;

    /** Provider.onCreate 在主线程；目录/占位文件 I/O 放到单线程后台初始化。 */
    private static final ExecutorService FILE_INIT_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-report-init");
                t.setDaemon(true);
                return t;
            });

    @Override
    public boolean onCreate() {
        // 模块 App 进程启动时后台预创建 Android/media/<模块包名>/qm_report.json。
        // 失败只记日志，不影响 Provider；onCreate 不阻塞主线程。
        final Context context = getContext();
        if (context != null) {
            try {
                FILE_INIT_EXECUTOR.execute(() -> {
                    try {
                        ReportFileReader.ensureSharedFile(context);
                    } catch (Throwable t) {
                        Log.w(TAG, "qmreport file sink ensure failed", t);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "qmreport file sink init submit failed", t);
            }
        }
        return true;
    }

    /**
     * synchronized：高德 3 个目标进程的 binder 线程可能并发进来，
     * hit 的 getLong→compare→putLong 必须串行，否则较大计数会被较小值覆盖（P2-2）。
     */
    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        if (!isAllowedCaller(context, Binder.getCallingUid())) {
            return null;
        }
        String[] keys = extras.getStringArray("keys");
        String[] values = extras.getStringArray("values");
        if (keys == null || values == null || keys.length != values.length) {
            return null;
        }
        if (keys.length > MAX_ITEMS) {
            return null;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, 0);
        SharedPreferences.Editor editor = sp.edit();
        String session = extras.getString("session");
        boolean mainProcess = extras.getBoolean("main", false);
        boolean reset = ReportProtocol.shouldResetSession(
                sp.getString(PREFS_SESSION, null), session, mainProcess);
        if (reset) {
            // 目标 App 主进程已重启：旧会话的 reg/skip/hit 全部作废，
            // 避免上一轮运行的 skip 永久保留并伪装成新数据。
            editor.clear();
        }
        boolean changed = false;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            String value = values[i];
            if (!ReportProtocol.validKey(key) || !ReportProtocol.isValidValue(value)) {
                continue;
            }
            if (key.startsWith(PREFIX_HIT)) {
                // 命中计数取历史最大值：高德多进程各自回传，取 max 不会重复累加，
                // 也不会被另一进程的低计数覆盖回去。新会话从 0 开始。
                long prev = reset ? 0L : sp.getLong(VALUE_PREFIX + key, 0L);
                Long next = ReportProtocol.parseHit(value);
                if (next == null) {
                    continue;
                }
                long merged = ReportProtocol.mergeHit(prev, next);
                if (merged > prev) {
                    editor.putLong(VALUE_PREFIX + key, merged);
                    changed = true;
                }
            } else {
                String existing = reset ? null
                        : sp.getString(VALUE_PREFIX + key, null);
                if (ReportProtocol.valueChanged(key, existing, value)) {
                    editor.putString(VALUE_PREFIX + key, value);
                    changed = true;
                }
            }
        }
        if (mainProcess && session != null && !session.isEmpty()) {
            editor.putString(PREFS_SESSION, session);
        }
        // 只有实际写入（含会话切换清理）才刷新时间戳；否则旧值不能被“盖上”
        // 一个当前时间戳而显得新鲜。
        if (changed) {
            editor.putLong(PREFS_UPDATED_AT, System.currentTimeMillis());
        }
        if (changed || reset || (mainProcess && session != null && !session.isEmpty())) {
            editor.apply();
        }
        return new Bundle();
    }

    /**
     * 诊断数据统一读入口：文件优先，Provider/SharedPreferences 兜底。
     * 文件比 prefs 新（或 prefs 没有时间戳）时使用文件。
     */
    public static ReportState readState(Context context) {
        return ReportFileReader.read(context);
    }

    /** 只读 SharedPreferences，供 ReportFileReader 做新旧比较与兜底。 */
    static ReportState providerState(Context context) {
        if (context == null) {
            return ReportState.none();
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            Map<String, String> data = new TreeMap<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (key == null || !key.startsWith(VALUE_PREFIX)) {
                    continue;
                }
                Object value = e.getValue();
                String plain = key.substring(VALUE_PREFIX.length());
                if (value instanceof String) {
                    data.put(plain, (String) value);
                } else if (value instanceof Long) {
                    data.put(plain, String.valueOf(value));
                }
            }
            long updatedAt = 0L;
            Object timestamp = all.get(PREFS_UPDATED_AT);
            if (timestamp instanceof Long) {
                updatedAt = (Long) timestamp;
            }
            return ReportState.fromProvider(data, updatedAt);
        } catch (Throwable t) {
            return ReportState.none();
        }
    }

    /** 读取一个回传字符串（reg/skip）；没有数据或读取失败返回 null。 */
    public static String reportString(Context context, String prefix, String key) {
        if (context == null) {
            return null;
        }
        return readState(context).value(prefix, key);
    }

    /** 读取命中计数；没有数据或读取失败返回 0。 */
    public static long reportHit(Context context, String key) {
        if (context == null) {
            return 0L;
        }
        return readState(context).hit(key);
    }

    /**
     * 统计“已启用且明确失败”的功能数，供异常横幅使用。
     *
     * 失败判定同时满足：用户配置为开启；目标进程有回传；reg 为空或 skip 非空。
     * 没有回传的功能不计入，避免刚装上或目标应用未启动时误报。
     * 只在主进程统计，子进程未装 hook 的 skip 不会被误算（Report 只在主进程回传）。
     */
    public static int failedFeatureCount(Context context) {
        if (context == null) {
            return 0;
        }
        ConfigStore store;
        try {
            store = new ConfigStore(context);
        } catch (Throwable t) {
            store = null;
        }
        int failed = 0;
        ReportState state = readState(context);
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            boolean enabled = store != null
                    ? store.get(f.key, f.defaultEnabled) : f.defaultEnabled;
            if (!enabled) {
                continue;
            }
            String reg = state.reg(f.key);
            String skip = state.skip(f.key);
            long hit = state.hit(f.key);
            boolean reported = (reg != null && !reg.isEmpty())
                    || (skip != null && !skip.isEmpty())
                    || hit > 0;
            if (!reported) {
                continue;
            }
            if (reg == null || reg.isEmpty() || (skip != null && !skip.isEmpty())) {
                failed++;
            }
        }
        return failed;
    }

    /** 只放行高德宿主 UID；模块自身与其他任意应用一律拒绝，不写盘也不抛异常。 */
    private static boolean isAllowedCaller(Context context, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (TARGET_PACKAGE.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
