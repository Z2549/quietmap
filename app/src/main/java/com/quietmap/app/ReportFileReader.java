package com.quietmap.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 模块 App 侧的文件兜底读入口。
 *
 * HyperOS 上目标进程 call() StatusProvider 会抛 Unknown authority，Provider 通道
 * 完全不可用；目标进程的 {@code hooks/ReportFileSink} 会把同一份快照写到
 * Android/media 下的 qm_report.json，这里按与写入侧相同的优先级读取：
 *   文件有效且不比 prefs 旧 → 用文件；否则回退 StatusProvider/SharedPreferences。
 *
 * 读侧只做尽力而为：任何路径/权限/解析失败都只打日志，不阻塞诊断页。
 */
public final class ReportFileReader {

    private static final String TAG = "QuietMap";

    private ReportFileReader() {
    }

    /** 文件优先，Provider/SharedPreferences 兜底。 */
    public static ReportState read(Context context) {
        if (context == null) {
            return ReportState.none();
        }
        ReportState provider = StatusProvider.providerState(context);
        long now = System.currentTimeMillis();
        ReportFileStore.Entry best = null;
        List<File> candidates = candidates(context);
        for (File file : candidates) {
            ReportFileStore.Probe probe;
            try {
                probe = ReportFileStore.probe(file, now, ReportFileStore.MAX_AGE_MS);
            } catch (Throwable t) {
                Log.w(TAG, "qmreport file sink read error path=" + file, t);
                continue;
            }
            if (probe.entry != null) {
                Log.i(TAG, "qmreport file sink read ok path=" + file
                        + " age=" + (now - probe.entry.mtime) + "ms");
                if (best == null || probe.entry.effectiveTime() > best.effectiveTime()) {
                    best = probe.entry;
                }
            } else if (file.exists()) {
                // 存在但不可用（权限/过期/损坏）：真机排查时这条最关键。
                Log.w(TAG, "qmreport file sink read skip path=" + file
                        + " reason=" + probe.reason);
            } else {
                Log.d(TAG, "qmreport file sink read skip path=" + file
                        + " reason=" + probe.reason);
            }
        }
        ReportState picked = ReportState.pick(best, provider);
        if (!picked.hasData()) {
            Log.i(TAG, "qmreport file sink no data source=none");
        }
        return picked;
    }

    /** 与写入侧一致的候选路径；模块读侧读不到目标 App 的 Android/data，仅尽力尝试。 */
    public static List<File> candidates(Context context) {
        File root = null;
        try {
            root = Environment.getExternalStorageDirectory();
        } catch (Throwable ignored) {
            // 外部存储未挂载：只剩空列表，调用方回退 Provider。
        }
        return ReportPaths.candidates(root, null,
                context.getPackageName(), ReportPaths.TARGET_PACKAGE);
    }

    /**
     * 模块 App 启动时预创建 Android/media/&lt;模块包名&gt;/qm_report.json，
     * 并尽力放开目录/文件权限，让目标进程可以创建 .tmp 并读取旧文件做合并。
     *
     * HyperOS / Android 11+ 的 scoped storage 可能忽略 chmod，此时写入侧会
     * 自动退到 b/c 路径；本方法所有失败都只记日志。
     */
    // 诊断文件不含敏感信息，world-readable/writable 是跨 UID 兜底通道的前提；
    // 系统仍可能因 scoped storage 拒绝，写入侧会 fail-open 到其他路径。
    @SuppressLint({"SetWorldReadable", "SetWorldWritable"})
    public static void ensureSharedFile(Context context) {
        if (context == null) {
            return;
        }
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root == null) {
                Log.w(TAG, "qmreport file sink ensure: no external storage");
                return;
            }
            File dir = new File(new File(root, "Android/media"), context.getPackageName());
            boolean created = dir.isDirectory() || dir.mkdirs();
            boolean dirReadable = dir.setReadable(true, false);
            boolean dirWritable = dir.setWritable(true, false);
            boolean dirExecutable = dir.setExecutable(true, false);

            File file = new File(dir, ReportPaths.FILE_NAME);
            boolean fileCreated = false;
            if (!file.exists()) {
                File tmp = new File(dir, ReportPaths.FILE_NAME + "."
                        + Process.myPid() + ReportPaths.TEMP_SUFFIX);
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(tmp, false);
                    out.write(ReportSnapshot.emptyJson().getBytes(StandardCharsets.UTF_8));
                    try {
                        out.getFD().sync();
                    } catch (Throwable ignored) {
                        // sync 失败不影响占位文件本身。
                    }
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (tmp.renameTo(file)) {
                    fileCreated = true;
                } else {
                    tmp.delete();
                }
            }
            boolean fileReadable = file.setReadable(true, false);
            boolean fileWritable = file.setWritable(true, false);
            Log.i(TAG, "qmreport file sink ensure dir=" + dir
                    + " created=" + created
                    + " r=" + dirReadable + " w=" + dirWritable + " x=" + dirExecutable
                    + " file=" + file
                    + " created=" + fileCreated + " exists=" + file.exists()
                    + " r=" + fileReadable + " w=" + fileWritable
                    + " canRead=" + file.canRead() + " canWrite=" + file.canWrite());
        } catch (Throwable t) {
            Log.w(TAG, "qmreport file sink ensure failed", t);
        }
    }
}
