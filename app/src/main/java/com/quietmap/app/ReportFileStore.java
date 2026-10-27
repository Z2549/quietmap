package com.quietmap.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * qm_report.json 的纯 Java 读取/校验层：mtime 新鲜度、JSON 结构、键白名单。
 *
 * 不依赖 android.*，JVM 单测可用临时目录覆盖「损坏 JSON 返回空」「mtime 过期」
 * 「多路径取最新」等规则。Android 侧的路径解析与 SharedPreferences 合并见
 * {@link ReportFileReader}。
 */
public final class ReportFileStore {

    /**
     * 超过 30 分钟的文件视为过期，避免展示上次运行残留的数据。
     * 目标进程每 5 分钟至少心跳重写一次，正常情况下年龄不会超过 10 分钟。
     */
    public static final long MAX_AGE_MS = 30 * 60 * 1000L;

    /** 快照正常只有十几 KB；上限防止读到异常大文件。 */
    public static final int MAX_BYTES = 64 * 1024;

    /** 允许未来 5 分钟的时钟偏差，超过则视为不可信。 */
    private static final long FUTURE_SKEW_MS = 5 * 60 * 1000L;

    private ReportFileStore() {
    }

    public static final class Entry {
        public final File file;
        public final ReportSnapshot snapshot;
        public final long mtime;

        public Entry(File file, ReportSnapshot snapshot, long mtime) {
            this.file = file;
            this.snapshot = snapshot;
            this.mtime = mtime;
        }

        /** 内容时间与文件时间的较大值，用于在多路径/Provider 之间比较新旧。 */
        public long effectiveTime() {
            return Math.max(mtime, snapshot.updatedAt());
        }
    }

    /** 单条候选路径的探测结果；entry 为 null 时 reason 说明被跳过的原因。 */
    public static final class Probe {
        public final File file;
        public final String reason;
        public final Entry entry;

        Probe(File file, String reason, Entry entry) {
            this.file = file;
            this.reason = reason;
            this.entry = entry;
        }
    }

    public static Entry read(File file, long now, long maxAgeMs) {
        return probe(file, now, maxAgeMs).entry;
    }

    /** 跨进程合并用：不检查新鲜度，但仍拒绝损坏 JSON 与空数据。 */
    public static Entry readForMerge(File file) {
        return probe(file, System.currentTimeMillis(), Long.MAX_VALUE).entry;
    }

    /**
     * 诊断页兜底用：文件已过期（超过 MAX_AGE_MS）时仍返回最后一份有效快照，
     * 让页面能显示“最近一次数据 + 时间”，而不是直接空白。
     */
    public static Entry readLastKnown(File file) {
        return readForMerge(file);
    }

    public static Entry freshest(List<File> candidates, long now, long maxAgeMs) {
        Entry best = null;
        if (candidates == null) {
            return null;
        }
        for (File file : candidates) {
            Entry entry = read(file, now, maxAgeMs);
            if (entry != null && (best == null
                    || entry.effectiveTime() > best.effectiveTime())) {
                best = entry;
            }
        }
        return best;
    }

    public static Probe probe(File file, long now, long maxAgeMs) {
        if (file == null) {
            return new Probe(null, "null path", null);
        }
        if (!file.isFile()) {
            return new Probe(file, file.exists() ? "not a regular file" : "missing", null);
        }
        long mtime = file.lastModified();
        if (mtime <= 0L) {
            return new Probe(file, "no mtime", null);
        }
        if (!fresh(now, mtime, maxAgeMs)) {
            return new Probe(file, "stale mtime " + mtime, null);
        }
        String text = readText(file);
        if (text == null) {
            return new Probe(file, "unreadable or > " + MAX_BYTES + " bytes", null);
        }
        ReportSnapshot snapshot = ReportSnapshot.parse(text);
        if (snapshot == null) {
            return new Probe(file, "invalid json", null);
        }
        if (!fresh(now, snapshot.updatedAt(), maxAgeMs)) {
            return new Probe(file, "stale updated_at " + snapshot.updatedAt(), null);
        }
        Map<String, String> data = sanitize(snapshot.data());
        if (data.isEmpty()) {
            return new Probe(file, "no valid keys", null);
        }
        ReportSnapshot clean = new ReportSnapshot(
                snapshot.updatedAt(), snapshot.session(), data);
        return new Probe(file, null, new Entry(file, clean, mtime));
    }

    /**
     * 只保留 ReportProtocol 白名单内的键与合法值；hit/entry 必须是可解析整数。
     * 文件可能被其他进程/旧版本写过，读取侧不能信任其内容。
     */
    public static Map<String, String> sanitize(Map<String, String> data) {
        Map<String, String> out = new TreeMap<>();
        if (data == null) {
            return out;
        }
        for (Map.Entry<String, String> e : data.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (!ReportProtocol.validKey(key) || !ReportProtocol.isValidValue(value)) {
                continue;
            }
            if (ReportProtocol.isCountKey(key)
                    && ReportProtocol.parseHit(value) == null) {
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    private static boolean fresh(long now, long timestamp, long maxAgeMs) {
        if (timestamp <= 0L) {
            return false;
        }
        long age = now - timestamp;
        return age <= maxAgeMs && age >= -FUTURE_SKEW_MS;
    }

    private static String readText(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    return null;
                }
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
