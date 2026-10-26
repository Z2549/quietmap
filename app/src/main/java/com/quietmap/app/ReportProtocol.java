package com.quietmap.app;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 报表通道的纯 Java 协议逻辑：键白名单、取值长度、命中计数合并。
 *
 * 不依赖任何 android.*，可直接在 JVM 单测（见 ReportProtocolTest）；
 * StatusProvider 只做 Binder / SharedPreferences 胶水，校验规则集中在这里。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = "reg.";
    public static final String PREFIX_HIT = "hit.";
    public static final String PREFIX_SKIP = "skip.";

    /** 目标进程一次运行的标识；Provider 用它判断是否需要清理旧会话数据。 */
    public static final String PREFS_UPDATED_AT_KEY = "meta.updated_at";
    public static final String PREFS_SESSION_KEY = "meta.session";
    /** 最后一次实际写入诊断数据的通道，供诊断页显示 socket/Provider。 */
    public static final String PREFS_SOURCE_KEY = "meta.source";
    public static final String SOURCE_SOCKET = "socket";
    public static final String SOURCE_PROVIDER = "provider";

    /**
     * 只有主进程在新 session 第一次回传时才允许清理旧会话数据。
     * 子进程 session 不同（每个进程各有一个 UUID），不能让它们互相清空。
     */
    public static boolean shouldResetSession(String storedSession, String incomingSession,
                                             boolean mainProcess) {
        return mainProcess
                && incomingSession != null
                && !incomingSession.isEmpty()
                && !incomingSession.equals(storedSession);
    }

    /**
     * 某个 key 的新值是否需要落盘：hit 只在新值更大时需要；其他 key 文本
     * 不同才需要。未实际变化时不更新 meta.updated_at，避免旧 skip/reg 被
     * 盖上当前时间戳后伪装成新鲜数据。
     */
    public static boolean valueChanged(String key, String existing, String incoming) {
        if (incoming == null) {
            return false;
        }
        if (key != null && key.startsWith(PREFIX_HIT)) {
            Long next = parseHit(incoming);
            if (next == null) {
                return false;
            }
            Long prev = parseHit(existing);
            long prevValue = prev == null ? 0L : prev;
            return mergeHit(prevValue, next) > prevValue;
        }
        return !incoming.equals(existing);
    }

    public static final int MAX_KEY = 128;
    public static final int MAX_VALUE = 256;

    /** 22 个 Feature × 3 个前缀 = 66 个合法键，构建后不可变。 */
    private static final Set<String> ALLOWED_KEYS = buildAllowedKeys();

    private ReportProtocol() {
    }

    private static Set<String> buildAllowedKeys() {
        Set<String> keys = new HashSet<>();
        for (Feature f : Feature.values()) {
            keys.add(PREFIX_REG + f.key);
            keys.add(PREFIX_HIT + f.key);
            keys.add(PREFIX_SKIP + f.key);
        }
        return Collections.unmodifiableSet(keys);
    }

    /** 只接受白名单键；非空、长度上限一并校验，其他一律拒绝。 */
    public static boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY
                && ALLOWED_KEYS.contains(key);
    }

    /** 值只校验非 null 与长度上限；hit 的可解析性由调用方再校验。 */
    public static boolean isValidValue(String value) {
        return value != null && value.length() <= MAX_VALUE;
    }

    /**
     * 解析 hit 计数文本；非法、空、溢出或带空白一律返回 null，由调用方跳过。
     * 协议不使用 JSON，这里等价覆盖「畸形文本」输入。
     */
    public static Long parseHit(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 命中计数合并：多进程各自回传本进程计数，历史值取最大，避免重复累加，
     * 也避免较大计数被较小值覆盖。负数（异常输入）按 0 处理。
     */
    public static long mergeHit(long prev, long next) {
        long p = prev < 0 ? 0 : prev;
        long n = next < 0 ? 0 : next;
        return Math.max(p, n);
    }
}
