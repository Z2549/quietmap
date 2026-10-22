package com.quietmap.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 目标进程内的诊断状态：每个钩子的注册标签、命中次数、跳过原因。
 *
 * 真正带细节的日志由 HookKit 打进 logcat（tag QuietMap）；这里只存计数快照，
 * 由 hooks 包的 Report 定期回传给模块 App 的 StatusProvider，供设置页诊断卡展示。
 *
 * 线程安全：命中可能来自任意线程（含 binder 线程），全部用并发容器。
 */
public final class HookState {

    private static final ConcurrentMap<Feature, State> STATES = new ConcurrentHashMap<>();

    private HookState() {
    }

    private static final class State {
        final Set<String> labels = ConcurrentHashMap.newKeySet();
        final AtomicLong hits = new AtomicLong();
        volatile String skip;

        void markSkip(String reason) {
            if (reason != null) {
                skip = reason;
            }
        }
    }

    private static State state(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            s = new State();
            State prev = STATES.putIfAbsent(f, s);
            if (prev != null) {
                s = prev;
            }
        }
        return s;
    }

    public static void installed(Feature f, String label) {
        state(f).labels.add(label);
    }

    public static void markNotFound(Feature f, String reason) {
        state(f).markSkip(reason);
    }

    /** 命中计数 +1，返回累加后的次数（供 HookKit 做日志采样）。 */
    public static long hit(Feature f) {
        return state(f).hits.incrementAndGet();
    }

    // ---- 快照（供 Report 回传） ----

    public static List<Feature> touched() {
        return new ArrayList<>(STATES.keySet());
    }

    public static List<String> labels(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(s.labels);
        Collections.sort(out);
        return out;
    }

    public static long hitCount(Feature f) {
        State s = STATES.get(f);
        return s == null ? 0L : s.hits.get();
    }

    public static String skipReason(Feature f) {
        State s = STATES.get(f);
        return s == null ? null : s.skip;
    }
}
