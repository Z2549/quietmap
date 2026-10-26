package com.quietmap.app.hooks;

import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import com.quietmap.app.ReportFileStore;
import com.quietmap.app.ReportPaths;
import com.quietmap.app.ReportSnapshot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 目标进程侧的文件兜底写入。
 *
 * HyperOS 上 ContentResolver.call 会抛 Unknown authority，Provider 通道失效；
 * 这里把同一份快照写到目标 App 自己能写的 Android/media 目录。路径按优先级尝试，
 * 第一条写入并实际读回成功即停止：
 *   1. Android/media/&lt;高德包名&gt;/qm_report.json   （目标 App 自己的 media 目录）
 *   2. getExternalFilesDir(null)/qm_report.json     （Android/data/&lt;高德包名&gt;/files）
 *
 * 不再写 Android/media/&lt;模块包名&gt;/：那是模块 App 的目录，只由模块 App 自己
 * 维护。也绝不放宽文件权限（不调用 setReadable/setWritable）：如果模块 App
 * 读不到 path b，读侧回退 Provider/SharedPreferences，而不是把文件设成
 * world-readable/writable 绕过 Provider 的 UID 校验。
 *
 * 写入方式：同目录 &lt;pid&gt;.tmp + Files.move(REPLACE_EXISTING) 原子替换；
 * 多进程写同一文件时先用 .lock 串行化。只有同一 session 才与旧文件合并
 * （hit 取最大、其余覆盖）；不同 session 直接替换，避免旧 skip/reg 被
 * 盖上当前时间戳。写入后必须用 FileInputStream 读回前几个字节才算成功；
 * 不可读则继续尝试下一条路径。任何失败只记日志，绝不影响 Report 主循环。
 */
final class ReportFileSink {

    private static final String TAG = "QuietMap";
    private static final long LOG_INTERVAL_MS = 60_000L;
    private static final int VERIFY_BYTES = 16;

    private static volatile String sLastPath;
    private static volatile long sLastOkLog;
    private static volatile long sLastFailLog;
    private static int sFailLogs;

    private ReportFileSink() {
    }

    /** @return true 表示至少一条路径写入且读回校验成功。 */
    static boolean write(Context context, Map<String, String> data, String session) {
        if (context == null || data == null || data.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        List<File> candidates;
        try {
            candidates = candidates(context);
        } catch (Throwable t) {
            logFailure(now, Collections.singletonList("candidates: " + describe(t)));
            return false;
        }
        return writeCandidates(candidates, data, now, session,
                String.valueOf(Process.myPid()));
    }

    private static List<File> candidates(Context context) {
        File root = Environment.getExternalStorageDirectory();
        File targetFilesDir = null;
        try {
            targetFilesDir = context.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // 外部存储不可用：路径 2 退化为标准布局推导。
        }
        return ReportPaths.writeCandidates(root, targetFilesDir, context.getPackageName());
    }

    /**
     * 纯 JVM 可测的写入循环：逐条尝试，换路径不影响后续路径；
     * 第一条写入并读回成功即返回 true。任何路径失败只加入 failures 日志。
     */
    static boolean writeCandidates(List<File> candidates, Map<String, String> data,
                                   long now, String session, String tmpTag) {
        if (candidates == null || data == null || data.isEmpty()) {
            return false;
        }
        List<String> failures = new ArrayList<>();
        for (File target : candidates) {
            try {
                writeOne(target, data, now, session, tmpTag);
                logSuccess(now, target, data.size(), failures);
                return true;
            } catch (Throwable t) {
                // 每条路径都带 canRead/canWrite，真机上可直接判断是权限还是内容问题。
                failures.add(target + " canRead=" + target.canRead()
                        + " canWrite=" + target.canWrite() + ": " + describe(t));
            }
        }
        logFailure(now, failures);
        return false;
    }

    /**
     * 写单条路径：session 相同才合并旧快照，不同 session 直接替换；
     * 写入后实际读回前 {@link #VERIFY_BYTES} 个字节，不可读视为失败。
     */
    static void writeOne(File target, Map<String, String> data, long now,
                         String session, String tmpTag) throws IOException {
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
                // 锁文件不可创建/加锁时继续无锁写入：tmp + move 本身仍是原子的。
            }
            Map<String, String> merged = data;
            ReportFileStore.Entry existing = ReportFileStore.readForMerge(target);
            if (existing != null
                    && sameSession(session, existing.snapshot.session())) {
                merged = ReportSnapshot.merge(existing.snapshot.data(), data);
            }
            byte[] bytes = ReportSnapshot.toJson(now, session, merged)
                    .getBytes(StandardCharsets.UTF_8);
            String suffix = tmpTag != null ? tmpTag : String.valueOf(Process.myPid());
            File tmp = new File(dir, ReportPaths.FILE_NAME + "." + suffix
                    + ReportPaths.TEMP_SUFFIX);
            boolean moved = false;
            try {
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(tmp, false);
                    out.write(bytes);
                    try {
                        out.getFD().sync();
                    } catch (Throwable ignored) {
                        // 某些 FUSE 不支持 fsync；move 仍然生效。
                    }
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                moveReplacing(tmp, target);
                moved = true;
                if (!verifyReadable(target)) {
                    throw new IOException("written file is not readable");
                }
            } finally {
                // 任何异常路径都清理 .tmp；rename 失败时绝不先删除旧文件。
                if (!moved && tmp.exists() && !tmp.delete()) {
                    Log.w(TAG, "qmreport file sink tmp cleanup failed: " + tmp);
                }
            }
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

    /**
     * 原子替换；不支持 ATOMIC_MOVE 的 FUSE/文件系统退化为 REPLACE_EXISTING。
     * 任何失败都不先删除旧文件，交给调用方清理 .tmp 后继续下一条路径。
     */
    private static void moveReplacing(File tmp, File target) throws IOException {
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 写入成功后真正打开文件读前几个字节；解析器读不了的文件不能算写入成功。
     * 空文件、目录、缺失文件都返回 false。
     */
    static boolean verifyReadable(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[VERIFY_BYTES];
            return in.read(buffer) > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 只有非空且完全相同的 session 才合并；旧文件无 session 时按不同会话替换。 */
    static boolean sameSession(String first, String second) {
        return first != null && !first.isEmpty() && first.equals(second);
    }

    private static void logSuccess(long now, File target, int keys, List<String> skipped) {
        String path = target.getPath();
        if (!path.equals(sLastPath) || now - sLastOkLog >= LOG_INTERVAL_MS) {
            sLastPath = path;
            sLastOkLog = now;
            StringBuilder sb = new StringBuilder("qmreport file sink write ok path=")
                    .append(path).append(" keys=").append(keys)
                    .append(" canRead=").append(target.canRead())
                    .append(" canWrite=").append(target.canWrite())
                    .append(" verify=ok");
            if (!skipped.isEmpty()) {
                // 高优先级路径失败但降级成功时，把失败原因一并带出来。
                sb.append(" skipped=").append(join(skipped));
            }
            logInfo(sb.toString());
        }
    }

    private static void logFailure(long now, List<String> failures) {
        if (sFailLogs < 3 || now - sLastFailLog >= LOG_INTERVAL_MS) {
            sFailLogs++;
            sLastFailLog = now;
            logWarn("qmreport file sink write failed: " + join(failures));
        }
    }

    /** 单测环境没有 Android Log 实现；日志失败绝不影响写入结果。 */
    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private static void logWarn(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {
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
