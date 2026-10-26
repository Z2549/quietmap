package com.quietmap.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.Date;

/**
 * 诊断页：逐功能显示 registered / hit / skip。
 *
 * 主页面零诊断，所有细节集中在这里。数据由目标进程的 HookState 经
 * hooks/Report 回传到 StatusProvider，再落到 qm_report。
 * 进入页面和 onResume 时刷新，不自动轮询。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    private long mLastRefreshMs;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        build(screen);
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    /** 底栏切到诊断 tab 或框架绑定后由 MainActivity 调用；view 未就绪时安全返回。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        rebuild();
    }

    private void rebuild() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        screen.removeAll();
        build(screen);
    }

    private void build(PreferenceScreen screen) {
        mLastRefreshMs = System.currentTimeMillis();
        ReportState state = StatusProvider.readState(requireContext());

        Preference header = new Preference(requireContext());
        header.setTitle(R.string.diag_title);
        header.setSummary(headerSummary(state));
        header.setSelectable(false);
        screen.addPreference(header);

        boolean hasData = false;
        for (Feature.Group group : Feature.Group.values()) {
            boolean hasGroup = false;
            for (Feature f : Feature.values()) {
                if (f.group == group) {
                    hasGroup = true;
                    break;
                }
            }
            if (!hasGroup) {
                continue;
            }
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(groupTitle(group));
            // 先把 category 挂到 screen，它才会拿到 PreferenceManager；
            // 否则 category.addPreference() 在 getNextId() 上 NPE。
            screen.addPreference(category);
            for (Feature f : Feature.values()) {
                if (f.group != group) {
                    continue;
                }
                category.addPreference(row(f, state));
                if (hasData(f, state)) {
                    hasData = true;
                }
            }
        }

        if (!hasData) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.diag_empty_title);
            empty.setSummary(getString(R.string.diag_empty, getString(R.string.app_target_name)));
            empty.setSelectable(false);
            screen.addPreference(empty);
        }

        Preference refresh = new Preference(requireContext());
        refresh.setTitle(R.string.diag_refresh);
        refresh.setSummary(R.string.diag_refresh_summary);
        refresh.setOnPreferenceClickListener(p -> {
            rebuild();
            return true;
        });
        screen.addPreference(refresh);

        Preference copy = new Preference(requireContext());
        copy.setTitle(R.string.diag_copy);
        copy.setSummary(R.string.diag_copy_summary);
        copy.setOnPreferenceClickListener(p -> {
            copyDiagnostics();
            return true;
        });
        screen.addPreference(copy);
    }

    private Preference row(Feature f, ReportState state) {
        Preference p = new Preference(requireContext());
        p.setKey("diag_" + f.key);
        p.setTitle(f.id + "  " + getString(f.titleRes));
        p.setSummary(summary(f, state));
        p.setSelectable(false);
        return p;
    }

    /** 单功能状态：registered · hit N · skip 原因；无数据用“—”。 */
    private String summary(Feature f, ReportState state) {
        if (f.blocked()) {
            return getString(R.string.diag_blocked, f.blockedReason);
        }
        String reg = state.reg(f.key);
        long hit = state.hit(f.key);
        String skip = state.skip(f.key);

        boolean hasReg = reg != null && !reg.isEmpty();
        boolean hasSkip = skip != null && !skip.isEmpty();
        boolean hasHit = hit > 0;
        if (!hasReg && !hasSkip && !hasHit) {
            return getString(R.string.diag_no_data);
        }

        String dash = getString(R.string.diag_no_data);
        StringBuilder sb = new StringBuilder();
        sb.append(hasReg ? "registered" : dash);
        sb.append(" · hit ").append(hit);
        sb.append(" · skip ").append(hasSkip ? skip : dash);
        return sb.toString();
    }

    /** 是否有任一有效回传，用于决定是否显示空状态提示。 */
    private boolean hasData(Feature f, ReportState state) {
        if (f.blocked()) {
            return true;
        }
        String reg = state.reg(f.key);
        String skip = state.skip(f.key);
        long hit = state.hit(f.key);
        return (reg != null && !reg.isEmpty())
                || (skip != null && !skip.isEmpty())
                || hit > 0;
    }

    private String headerSummary(ReportState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_header, refreshTime()));
        sb.append('\n').append(sourceSummary(state));
        if (state.source() == ReportState.Source.FILE && state.path() != null) {
            sb.append('\n').append(getString(R.string.diag_source_path, state.path()));
        }
        sb.append('\n').append(getString(R.string.diag_hint));
        return sb.toString();
    }

    /** 真机排查关键：文件兜底是否生效、最后更新时间，以及具体走了哪条路径。 */
    private String sourceSummary(ReportState state) {
        switch (state.source()) {
            case FILE:
                return getString(R.string.diag_source_file, lastUpdated(state.updatedAt()));
            case PROVIDER:
                return getString(R.string.diag_source_provider, lastUpdated(state.updatedAt()));
            case NONE:
            default:
                return getString(R.string.diag_source_none);
        }
    }

    private String lastUpdated(long timestamp) {
        if (timestamp <= 0L) {
            return getString(R.string.diag_no_data);
        }
        Date date = new Date(timestamp);
        return DateFormat.getDateFormat(requireContext()).format(date)
                + " " + DateFormat.getTimeFormat(requireContext()).format(date);
    }

    private void copyDiagnostics() {
        ReportState state = StatusProvider.readState(requireContext());
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.app_name)).append(' ').append(versionName()).append('\n');
        sb.append(getString(R.string.diag_header, refreshTime())).append('\n');
        sb.append(sourceSummary(state)).append('\n');
        if (state.source() == ReportState.Source.FILE && state.path() != null) {
            sb.append(getString(R.string.diag_source_path, state.path())).append('\n');
        }
        for (Feature f : Feature.values()) {
            sb.append(f.id).append(' ').append(getString(f.titleRes))
                    .append(": ").append(summary(f, state)).append('\n');
        }
        try {
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()));
                Toast.makeText(requireContext(), R.string.diag_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // 剪贴板不可用不阻塞诊断页。
        }
    }

    private String refreshTime() {
        return DateFormat.getTimeFormat(requireContext()).format(new Date(mLastRefreshMs));
    }

    private int groupTitle(Feature.Group group) {
        switch (group) {
            case AD:
                return R.string.group_ad;
            case UI:
                return R.string.group_ui;
            case PV:
                return R.string.group_pv;
            case EN:
                return R.string.group_en;
            case EX:
            default:
                return R.string.group_ex;
        }
    }

    private String versionName() {
        try {
            String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            return v != null ? v : "?";
        } catch (Throwable t) {
            return "?";
        }
    }
}
