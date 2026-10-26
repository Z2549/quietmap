package com.quietmap.app;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 诊断页的一次读取结果：数据来源、最后更新时间、文件路径与键值。
 *
 * 纯 Java，便于单测锁定「文件比 prefs 新才用文件」的选择逻辑。
 */
public final class ReportState {

    public enum Source {
        NONE,
        FILE,
        PROVIDER
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

    public static ReportState fromProvider(Map<String, String> data, long updatedAt) {
        return new ReportState(Source.PROVIDER, updatedAt, null,
                ReportFileStore.sanitize(data));
    }

    /**
     * 文件优先，但只在文件有效且不比 SharedPreferences 旧时使用；
     * prefs 没有时间戳（旧版本写入）时同样让位给有效文件。
     */
    public static ReportState pick(ReportFileStore.Entry file, ReportState provider) {
        if (file == null) {
            return provider != null && provider.hasData() ? provider : EMPTY;
        }
        if (provider == null || !provider.hasData()) {
            return fromFile(file);
        }
        if (provider.updatedAt() <= 0L) {
            return fromFile(file);
        }
        return file.effectiveTime() >= provider.updatedAt()
                ? fromFile(file) : provider;
    }

    public Source source() {
        return source;
    }

    /** 数据实际生成时间（文件内 updated_at）；0 表示未知。 */
    public long updatedAt() {
        return updatedAt;
    }

    /** 仅 Source.FILE 时非 null。 */
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
}
