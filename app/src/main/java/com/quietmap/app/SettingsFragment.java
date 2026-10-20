package com.quietmap.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * 设置页。界面结构照 LSPosed 管理器：
 * 顶部状态卡（LSPosed 连接 / remote prefs 可用性 / 模块版本）
 * + 十九个净化开关（默认开）
 * + 诊断卡（registered / hit N / skip，数据由目标进程经 ReportProvider 回传）
 * + 页脚（兼容版本声明）。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TARGET = "com.autonavi.minimap";

    /** 两次 refresh 的最小间隔，压掉 onCreatePreferences / onResume / 绑定回调三连。 */
    private static final long REFRESH_MIN_INTERVAL_MS = 1000L;

    /**
     * 状态卡的框架查询是同步 binder（getApiVersion/getScope/getRunningTargets），
     * 放后台单线程执行，避免框架卡顿时拖住设置页首帧。
     */
    private static final ExecutorService sStatusExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-settings-status");
                t.setDaemon(true);
                return t;
            });

    private ConfigStore mStore;
    /** mStore 对应的 service 实例；同一实例内 remote prefs 不变，可复用避免重复 binder。 */
    private XposedService mStoreService;
    private long mLastRefreshMs;
    private boolean mHasRefreshed;
    private XposedService mLastRefreshService;
    /** 后台状态查询句柄：新查询提交前取消旧的，onDestroyView 取消全部。 */
    private java.util.concurrent.Future<?> mStatusFuture;
    /** versionName 在 Fragment 生命周期内不变，只读一次 PackageManager。 */
    private String mVersionName;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStoreService = ServiceBridge.get();
        mStore = new ConfigStore(requireContext());
        bindFeatures();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        // view 已销毁，后台查询的结果不再有用；cancel(true) 让阻塞中的 binder 尽快返回。
        if (mStatusFuture != null) {
            mStatusFuture.cancel(true);
            mStatusFuture = null;
        }
        super.onDestroyView();
    }

    /** 把 Feature 枚举里的标题/说明/开关接到 XML 声明的偏好项上。 */
    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p == null) {
                Log.e("QuietMap", "preference missing for " + f.key);
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    boolean ok = mStore.put(f.key, (Boolean) value);
                    if (!ok) {
                        Toast.makeText(requireContext(), "写入失败：开关未保存",
                                Toast.LENGTH_SHORT).show();
                        // 返回 false：MaterialSwitch 回滚到旧状态，避免"看着改了实际没写进去"。
                        return false;
                    }
                }
                return true;
            });
        }
        Preference footer = findPreference("footer");
        if (footer != null) {
            footer.setSummary(R.string.footer_limits);
        }
    }

    /** 重新读取框架状态与开关值。service 绑定后由 Activity 调一次。 */
    void refresh() {
        long now = SystemClock.elapsedRealtime();
        XposedService service = ServiceBridge.get();
        // 1 秒内的重复调用丢弃；但 binder 刚绑上/断开属于新信息，必须放行，
        // 否则绑定回调恰好落在窗口内会把状态卡永久卡在「连接中」。
        if (mHasRefreshed
                && now - mLastRefreshMs < REFRESH_MIN_INTERVAL_MS
                && service == mLastRefreshService) {
            return;
        }
        mHasRefreshed = true;
        mLastRefreshMs = now;
        mLastRefreshService = service;

        // 同一 service 实例内 remote prefs 不会变，复用 mStore 可省掉每次 refresh 的
        // 两次 binder（getFrameworkProperties + getRemotePreferences）；换绑/断开才重建。
        if (mStore == null || service != mStoreService) {
            mStore = new ConfigStore(requireContext());
            mStoreService = service;
        }
        boolean writable = mStore.remote();

        Preference status = findPreference("status");
        if (status != null) {
            if (service == null) {
                status.setTitle(R.string.status_connecting);
                status.setSummary(R.string.status_detail_connecting);
                appendRemoteState(status, writable);
            } else {
                loadStatusAsync(service, writable);
            }
        }

        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                sw.setCheckedValue(mStore.get(f.key, f.defaultEnabled));
                // 框架没绑定时写不进去，置灰避免"改了没用"。
                sw.setEnabled(writable);
            }
        }

        Preference diag = findPreference("diag");
        if (diag != null) {
            diag.setSummary(diagSummary());
        }
    }

    /**
     * 状态卡的 service 查询全在后台单线程跑，结果回主线程写 UI。
     * Fragment 已 detach（Activity 销毁）或 service 已换绑时整份结果丢弃。
     */
    private void loadStatusAsync(XposedService service, boolean writable) {
        final XposedService bound = service;
        final boolean remoteWritable = writable;
        final String version = versionName();
        final Activity host = requireActivity();
        // 旧查询（可能仍卡在 binder 上）先取消；新结果会覆盖状态卡，无需等待旧的。
        if (mStatusFuture != null) {
            mStatusFuture.cancel(false);
        }
        mStatusFuture = sStatusExecutor.submit(() -> {
            int api = 0;
            boolean scoped = false;
            try {
                api = bound.getApiVersion();
            } catch (Throwable ignored) {
            }
            try {
                List<String> scope = bound.getScope();
                scoped = scope != null && scope.contains(TARGET);
            } catch (Throwable ignored) {
            }
            // getRunningTargets 是 API 102 的"当前真的被注入的进程"，
            // 只有它非空才能说明模块已启用——光看 service 绑定会误报"已激活"。
            boolean injected = isTargetRunning(bound);
            final int finalApi = api;
            final boolean finalScoped = scoped;
            // Activity 已销毁：丢弃结果，不要往死 UI 上写。
            if (!isAdded() || getActivity() != host) {
                return;
            }
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                // service 已死/换绑，这份结果过期了。
                if (ServiceBridge.get() != bound) {
                    return;
                }
                Preference p = findPreference("status");
                if (p == null) {
                    return;
                }
                if (!finalScoped) {
                    p.setTitle(R.string.status_inactive);
                    p.setSummary(getString(R.string.status_detail_no_scope, finalApi, version));
                } else if (injected) {
                    p.setTitle(R.string.status_active);
                    p.setSummary(getString(R.string.status_detail_active, finalApi, version));
                } else {
                    p.setTitle(R.string.status_connected);
                    p.setSummary(getString(R.string.status_detail_not_running, finalApi, version));
                }
                p.setSummary(p.getSummary() + "\n"
                        + getString(remoteWritable
                                ? R.string.status_remote_ok
                                : R.string.status_remote_fail));
            });
        });
    }

    /** remote prefs 可用性单独挂在状态卡副标题里，抓「开关写不进框架」这个坑。 */
    private void appendRemoteState(Preference status, boolean writable) {
        status.setSummary(status.getSummary() + "\n"
                + getString(writable ? R.string.status_remote_ok : R.string.status_remote_fail));
    }

    /**
     * 诊断卡内容：每个钩子一行，registered / hit N / skip 三态拼接。
     * 数据来自 ReportProvider 收下的目标进程回传（qm_report prefs）。
     */
    private String diagSummary() {
        SharedPreferences sp = requireContext()
                .getSharedPreferences(ReportProvider.PREFS, 0);
        StringBuilder sb = new StringBuilder();
        for (Feature f : Feature.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(f.key).append("：");
            String reg = sp.getString("v:" + ReportProvider.PREFIX_REG + f.key, null);
            long hit = sp.getLong("v:" + ReportProvider.PREFIX_HIT + f.key, 0L);
            String skip = sp.getString("v:" + ReportProvider.PREFIX_SKIP + f.key, null);
            boolean any = false;
            if (reg != null) {
                sb.append("registered");
                any = true;
            }
            if (hit > 0) {
                if (any) {
                    sb.append(" · ");
                }
                sb.append("hit ").append(hit);
                any = true;
            }
            if (skip != null) {
                if (any) {
                    sb.append(" · ");
                }
                sb.append("skip ").append(skip);
                any = true;
            }
            if (!any) {
                sb.append("—");
            }
        }
        return sb.toString();
    }

    /** 判断目标进程当前是否真的被注入（API 102 getRunningTargets）。 */
    private boolean isTargetRunning(XposedService service) {
        try {
            List<HookedTarget> targets = service.getRunningTargets();
            if (targets == null) {
                return false;
            }
            for (HookedTarget t : targets) {
                String name = t.getProcessName();
                if (name != null && (name.equals(TARGET) || name.startsWith(TARGET + ":"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String versionName() {
        if (mVersionName != null) {
            return mVersionName;
        }
        try {
            String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            mVersionName = v != null ? v : "?";
        } catch (Throwable t) {
            mVersionName = "?";
        }
        return mVersionName;
    }
}
