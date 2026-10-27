package com.quietmap.app;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 诊断页的一次读取结果：数据来源、最后更新时间、文件路径与键值。
 *
 * 纯 Java，便于单测锁定「文件与 prefs 按 key 合并、较新且非空者优先」的
 * 选择逻辑。FILE_STALE 表示文件已超过 TTL，仅用于展示“最近一次数据 + 时间”。
 */
public final class ReportState {

    public enum Source {
        NONE,
        FILE,
        FILE_STALE,
        PROVIDER,
        SOCKET
    }

    private static final ReportState EMPTY = new ReportState(
            Source.NONE, 0L, null, Collections.emptyMap());

    private final Source source;
    private final long updatedAt;
    private final String path;
    private final Map<String, String> data;

    private ReportState(Source source, long updatedAt, String path,
                        Map<String, String> data) {
        this.source = source;
        this.updatedAt = updatedAt;
        this.path = path;
        this.data = Collections.unmodifiableMap(new TreeMap<>(
                data != null ? data : Collections.emptyMap()));
    }

    public static ReportState none() {
        return EMPTY;
    }

    public static ReportState fromFile(ReportFileStore.Entry entry) {
        if (entry == null) {
            return EMPTY;
        }
        return new ReportState(Source.FILE, entry.snapshot.updatedAt(),
                entry.file != null ? entry.file.getPath() : null, entry.snapshot.data());
    }

    /** 文件已过期，但仍展示最后一份有效快照及其时间。 */
    public static ReportState fromStaleFile(ReportFileStore.Entry entry) {
        if (entry == null) {
            return EMPTY;
        }
        return new ReportState(Source.FILE_STALE, entry.snapshot.updatedAt(),
                entry.file != null ? entry.file.getPath() : null, entry.snapshot.data());
    }

    public static ReportState fromProvider(Map<String, String> data, long updatedAt) {
        return new ReportState(Source.PROVIDER, updatedAt, null,
                ReportFileStore.sanitize(data));
    }

    /** socket 通道写入的 prefs 数据；与 Provider 数据同 schema，只区来源。 */
    public static ReportState fromSocket(Map<String, String> data, long updatedAt) {
        return new ReportState(Source.SOCKET, updatedAt, null,
                ReportFileStore.sanitize(data));
    }

    /**
     * 文件与 prefs 按 key 合并，而不是整源二选一：
     * - 两个源都有的 key：hit/entry 计数取最大值；reg/skip 取较新源的值，
     *   较新值为空时回退较旧的非空值（更完整）；
     * - 只有一个源有的 key：保留该值；
     * - 返回状态的 source/path/updatedAt 取时间较新的源。
     */
    public static ReportState pick(ReportFileStore.Entry file, ReportState provider) {
        if (file == null) {
            return provider != null && provider.hasData() ? provider : EMPTY;
        }
        if (provider == null || !provider.hasData()) {
            return fromFile(file);
        }
        boolean fileNewer = provider.updatedAt() <= 0L
                || file.effectiveTime() >= provider.updatedAt();
        Map<String, String> fileData = file.snapshot.data();
        Map<String, String> providerData = provider.data;
        Set<String> keys = new TreeSet<>(fileData.keySet());
        keys.addAll(providerData.keySet());

        Map<String, String> merged = new TreeMap<>();
        for (String key : keys) {
            String fromFile = fileData.get(key);
            String fromProvider = providerData.get(key);
            if (fromFile == null) {
                merged.put(key, fromProvider);
            } else if (fromProvider == null) {
                merged.put(key, fromFile);
            } else if (fromFile.equals(fromProvider)) {
                merged.put(key, fromFile);
            } else if (ReportProtocol.isCountKey(key)) {
                merged.put(key, newerCount(fromFile, fromProvider));
            } else if (fileNewer) {
                merged.put(key, preferNonEmpty(fromFile, fromProvider));
            } else {
                merged.put(key, preferNonEmpty(fromProvider, fromFile));
            }
        }
        Source source = fileNewer ? Source.FILE : provider.source();
        long updatedAt = fileNewer ? file.snapshot.updatedAt() : provider.updatedAt();
        String path = fileNewer && file.file != null ? file.file.getPath() : null;
        return new ReportState(source, updatedAt, path, merged);
    }

    private static String newerCount(String first, String second) {
        Long a = ReportProtocol.parseHit(first);
        Long b = ReportProtocol.parseHit(second);
        long left = a == null ? 0L : a;
        long right = b == null ? 0L : b;
        return String.valueOf(ReportProtocol.mergeHit(left, right));
    }

    /** 较新值优先；较新值为空串时用较旧的非空值，避免“较新”把已有信息清空。 */
    private static String preferNonEmpty(String preferred, String fallback) {
        if (preferred == null || preferred.isEmpty()) {
            return fallback;
        }
        return preferred;
    }

    public Source source() {
        return source;
    }

    /** 数据实际生成时间（文件内 updated_at）；0 表示未知。 */
    public long updatedAt() {
        return updatedAt;
    }

    /** 仅 Source.FILE / FILE_STALE 时非 null。 */
    public String path() {
        return path;
    }

    public boolean hasData() {
        return !data.isEmpty();
    }

    public String value(String prefix, String key) {
        return data.get(prefix + key);
    }

    public String reg(String key) {
        return value(ReportProtocol.PREFIX_REG, key);
    }

    public String skip(String key) {
        return value(ReportProtocol.PREFIX_SKIP, key);
    }

    public long hit(String key) {
        Long value = ReportProtocol.parseHit(
                data.get(ReportProtocol.PREFIX_HIT + key));
        return value == null ? 0L : Math.max(0L, value);
    }

    /** 入口计数；负数与非法值按 0 处理，与 hit 共用同一套规则。 */
    public long entry(String key) {
        Long value = ReportProtocol.parseHit(
                data.get(ReportProtocol.PREFIX_ENTRY + key));
        return value == null ? 0L : Math.max(0L, value);
    }
}
