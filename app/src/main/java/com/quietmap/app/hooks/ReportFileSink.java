package com.quietmap.app.hooks;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import com.quietmap.app.ReportFileStore;
import com.quietmap.app.ReportPaths;
import com.quietmap.app.ReportSnapshot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 目标进程侧的文件兜底写入。
 *
 * HyperOS 上 ContentResolver.call 会抛 Unknown authority，Provider 通道失效；
 * 这里把同一份快照写到 Android/media 下的 qm_report.json。路径按优先级尝试，
 * 第一条成功即停止：
 *   1. Android/media/&lt;模块包名&gt;/qm_report.json
 *   2. Android/media/&lt;高德包名&gt;/qm_report.json
 *   3. 高德自己的 getExternalFilesDir(null)/qm_report.json
 *
 * 写入方式：同目录 &lt;pid&gt;.tmp + rename 原子替换；多进程写同一文件时先用
 * .lock 串行化，并合并已有快照（hit 取最大、其余覆盖），语义与 StatusProvider
 * 的 prefs 合并一致。任何失败只记日志，绝不影响 Report 主循环。
 */
final class ReportFileSink {

    private static final String TAG = "QuietMap";
    private static final long LOG_INTERVAL_MS = 60_000L;

    private static volatile String sLastPath;
    private static volatile long sLastOkLog;
    private static volatile long sLastFailLog;
    private static int sFailLogs;

    private ReportFileSink() {
    }

    /** @return true 表示至少一条路径写入成功。 */
    static boolean write(Context context, String modulePackage, Map<String, String> data) {
        if (context == null || data == null || data.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        List<File> candidates;
        try {
            candidates = candidates(context, modulePackage);
        } catch (Throwable t) {
            logFailure(now, Collections.singletonList("candidates: " + describe(t)));
            return false;
        }
        List<String> failures = new ArrayList<>();
        for (File target : candidates) {
            try {
                boolean readable = writeOne(target, data, now);
                logSuccess(now, target, data.size(), readable, failures);
                return true;
            } catch (Throwable t) {
                failures.add(target + ": " + describe(t));
            }
        }
        logFailure(now, failures);
        return false;
    }

    private static List<File> candidates(Context context, String modulePackage) {
        File root = Environment.getExternalStorageDirectory();
        File targetFilesDir = null;
        try {
            targetFilesDir = context.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // 外部存储不可用：路径 3 退化为标准布局推导。
        }
        return ReportPaths.candidates(root, targetFilesDir,
                modulePackage != null ? modulePackage : ReportPaths.MODULE_PACKAGE,
                context.getPackageName());
    }

    // 诊断快照只有钩子注册/命中计数，不含敏感信息；模块 App 需要读取该文件。
    @SuppressLint("SetWorldReadable")
    private static boolean writeOne(File target, Map<String, String> data, long now)
            throws IOException {
        boolean worldReadable = false;
        File dir = target.getParentFile();
        if (dir == null) {
            throw new IOException("no parent dir");
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("mkdirs failed");
        }
        RandomAccessFile lockRaf = null;
        FileLock lock = null;
        try {
            try {
                lockRaf = new RandomAccessFile(
                        new File(dir, ReportPaths.FILE_NAME + ".lock"), "rw");
                // tryLock：另一个高德进程正在写时直接无锁继续，避免阻塞 Report 线程。
                lock = lockRaf.getChannel().tryLock();
            } catch (Throwable ignored) {
                // 锁文件不可创建/加锁时继续无锁写入：tmp+rename 本身仍是原子的。
            }
            Map<String, String> merged = data;
            ReportFileStore.Entry existing = ReportFileStore.readForMerge(target);
            if (existing != null) {
                merged = ReportSnapshot.merge(existing.snapshot.data(), data);
            }
            byte[] bytes = ReportSnapshot.toJson(now, merged)
                    .getBytes(StandardCharsets.UTF_8);
            File tmp = new File(dir, ReportPaths.FILE_NAME + "."
                    + Process.myPid() + ReportPaths.TEMP_SUFFIX);
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(tmp, false);
                out.write(bytes);
                try {
                    out.getFD().sync();
                } catch (Throwable ignored) {
                    // 某些 FUSE 不支持 fsync；rename 仍然生效。
                }
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 目标进程创建的文件默认只对同 UID 可读，模块 App 需要 world-readable。
            worldReadable = tmp.setReadable(true, false);
            if (!tmp.renameTo(target)) {
                // 少数 FUSE 实现不允许 rename 覆盖：先删除旧文件再重命名。
                boolean deleted = !target.exists() || target.delete();
                if (!deleted || !tmp.renameTo(target)) {
                    tmp.delete();
                    throw new IOException("rename failed");
                }
            }
            boolean targetReadable = target.setReadable(true, false);
            return worldReadable || targetReadable;
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (Throwable ignored) {
                }
            }
            if (lockRaf != null) {
                try {
                    lockRaf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void logSuccess(long now, File target, int keys, boolean readable,
                                   List<String> skipped) {
        String path = target.getPath();
        if (!path.equals(sLastPath) || now - sLastOkLog >= LOG_INTERVAL_MS) {
            sLastPath = path;
            sLastOkLog = now;
            StringBuilder sb = new StringBuilder("qmreport file sink write ok path=")
                    .append(path).append(" keys=").append(keys)
                    .append(" readable=").append(readable);
            if (!skipped.isEmpty()) {
                // 高优先级路径失败但降级成功时，把失败原因一并带出来。
                sb.append(" skipped=").append(join(skipped));
            }
            Log.i(TAG, sb.toString());
        }
    }

    private static void logFailure(long now, List<String> failures) {
        if (sFailLogs < 3 || now - sLastFailLog >= LOG_INTERVAL_MS) {
            sFailLogs++;
            sLastFailLog = now;
            Log.w(TAG, "qmreport file sink write failed: " + join(failures));
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
