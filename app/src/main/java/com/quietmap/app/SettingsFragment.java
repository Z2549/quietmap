package com.quietmap.app;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * 设置页。界面结构照 LSPosed 管理器：
 * 顶部状态卡（LSPosed 连接 / remote prefs 可用性 / 模块版本）
 * + 六个净化开关（默认开）
 * + 诊断卡（registered / hit N / skip，数据由目标进程经 ReportProvider 回传）
 * + 页脚（兼容版本声明）。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TARGET = "com.autonavi.minimap";

    private ConfigStore mStore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStore = new ConfigStore(requireContext());
        bindFeatures();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** 把 Feature 枚举里的标题/说明/开关接到 XML 声明的偏好项上。 */
    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p == null) {
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    mStore.put(f.key, (Boolean) value);
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
        mStore = new ConfigStore(requireContext());
        boolean writable = mStore.remote();

        Preference status = findPreference("status");
        if (status != null) {
            XposedService service = ServiceBridge.get();
            if (service == null) {
                status.setTitle(R.string.status_connecting);
                status.setSummary(R.string.status_detail_connecting);
            } else {
                int api = 0;
                boolean scoped = false;
                try {
                    api = service.getApiVersion();
                } catch (Throwable ignored) {
                }
                try {
                    List<String> scope = service.getScope();
                    scoped = scope != null && scope.contains(TARGET);
                } catch (Throwable ignored) {
                }
                // getRunningTargets 是 API 102 的"当前真的被注入的进程"，
                // 只有它非空才能说明模块已启用——光看 service 绑定会误报"已激活"。
                boolean injected = isTargetRunning(service);
                if (!scoped) {
                    status.setTitle(R.string.status_inactive);
                    status.setSummary(getString(R.string.status_detail_no_scope, api, versionName()));
                } else if (injected) {
                    status.setTitle(R.string.status_active);
                    status.setSummary(getString(R.string.status_detail_active, api, versionName()));
                } else {
                    status.setTitle(R.string.status_connected);
                    status.setSummary(getString(R.string.status_detail_not_running, api, versionName()));
                }
            }
            // remote prefs 可用性单独挂在状态卡副标题里，抓「开关写不进框架」这个坑。
            if (writable) {
                status.setSummary(status.getSummary() + "\n"
                        + getString(R.string.status_remote_ok));
            } else {
                status.setSummary(status.getSummary() + "\n"
                        + getString(R.string.status_remote_fail));
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
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
