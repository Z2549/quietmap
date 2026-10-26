package com.quietmap.app.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.StatusProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 目标进程 → 模块 App 的诊断回传（registered / hit N / skip）。
 *
 * 照 AbReportProvider 跨进程模式：hook 侧只能主动 call 进模块的 ContentProvider
 * （remote prefs 在目标进程只读）。Context 用 ActivityThread 反射拿——
 * 框架类黑名单禁止 hook android.app.Application，不能像别处那样挂 onCreate 兜底，
 * 好在 onPackageReady 之后 Application 很快可用，拿不到就等下一轮刷新。
 *
 * 节流：后台单线程每 3 秒把 HookState 快照打平上报一次，内容没变不发；
 * provider 不可达时退避 60 秒，不刷日志。
 *
 * 文件兜底：同一份快照在 Provider 调用之外还会写目标 App 自己的 media 目录
 * （见 ReportFileSink）。常规写入节流到 45 秒；快照长时间不变时每 5 分钟
 * 心跳重写一次，保证读取侧的 30 分钟 TTL 不会把仍在运行的数据判成过期。
 * 两条通道各自记录已发送内容，Provider 失败不影响文件写入，反之亦然。
 *
 * session：每个目标进程启动时生成一个 UUID，随快照一起写入。同一 session
 * 的旧文件才合并，不同 session 直接替换，避免旧 skip/reg 被盖上当前时间戳。
 * 主进程会把 session/是否主进程一并回传 Provider，供其在新会话开始时清理
 * 旧会话数据；子进程只做 max-hit 合并，不触发清理。
 */
final class Report {

    private static final String TAG = "QuietMap";
    private static final int MAX_VALUE = 256;

    /** 快照未变化时的文件心跳周期：至少每 5 分钟重写一次。 */
    static final long FILE_HEARTBEAT_MS = 5 * 60_000L;
    /** 快照变化后的常规文件写入最小间隔，避免命中计数每 3 秒刷盘。 */
    static final long FILE_MIN_INTERVAL_MS = 45_000L;

    /** 本进程运行的唯一标识；跨进程写同一文件时用于判断是否合并。 */
    static final String SESSION = UUID.randomUUID().toString();

    private static volatile boolean sStarted;
    private static volatile boolean sMainProcess = true;
    private static volatile Context sContext;
    private static volatile Map<String, String> sLast = new TreeMap<>();
    private static volatile Map<String, String> sLastFile = new TreeMap<>();
    private static volatile long sNextTry;
    private static volatile long sNextFileTry;
    private static volatile long sLastFileWriteMs;
    private static volatile int sErrors;

    private Report() {
    }

    static synchronized void start(boolean mainProcess) {
        sMainProcess = mainProcess;
        if (sStarted) {
            return;
        }
        sStarted = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuietMap-Report");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Throwable ignored) {
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private static void flush() {
        Map<String, String> batch = snapshot();
        if (batch.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean providerDone = batch.equals(sLast);
        boolean fileChanged = !batch.equals(sLastFile);
        boolean fileDue = fileWriteDue(fileChanged, now, sLastFileWriteMs);
        if (providerDone && !fileDue) {
            return;
        }
        Context context = context();
        if (context == null) {
            return;
        }

        // 文件通道不受 Provider 退避影响：HyperOS 上 Provider 长期不可用时，
        // 诊断页仍要靠这条通道拿到数据。常规变化节流 45 秒，快照不变时
        // 每 5 分钟心跳重写；全部路径失败后退避 60 秒，避免每 3 秒重试。
        if (fileDue && now >= sNextFileTry) {
            try {
                if (ReportFileSink.write(context, batch, SESSION)) {
                    sLastFile = batch;
                    sLastFileWriteMs = now;
                } else {
                    sNextFileTry = now + 60_000L;
                }
            } catch (Throwable t) {
                sNextFileTry = now + 60_000L;
                Log.w(TAG, "qmreport file sink crashed", t);
            }
        }

        if (providerDone) {
            return;
        }
        if (now < sNextTry) {
            return;
        }
        List<String> keys = new ArrayList<>(batch.keySet());
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            values.add(batch.get(key));
        }
        Bundle extras = new Bundle();
        extras.putStringArray("keys", keys.toArray(new String[0]));
        extras.putStringArray("values", values.toArray(new String[0]));
        extras.putString("session", SESSION);
        extras.putBoolean("main", sMainProcess);
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + StatusProvider.AUTHORITY), "report", null, extras);
            if (result != null) {
                sLast = batch;
                return;
            }
            // call() 返回 null 视为 provider 暂不可达：与抛异常一样退避 60 秒，
            // 不更新 sLast，下次内容变化或退避到期后再试。
            sNextTry = System.currentTimeMillis() + 60_000L;
            if (sErrors++ < 3) {
                Log.w(TAG, "report provider returned null");
            }
        } catch (Throwable t) {
            // 模块 App 未启动、被系统拦截等情况：一分钟后再试，别每 3 秒刷日志。
            sNextTry = System.currentTimeMillis() + 60_000L;
            if (sErrors++ < 3) {
                Log.w(TAG, "report provider unavailable", t);
            }
        }
    }

    /**
     * 文件写入时机：首次立即写；快照变化后至少间隔 45 秒；未变化时每 5 分钟
     * 心跳重写。时钟回拨（now < last）时允许一次，避免永久停写。
     */
    static boolean fileWriteDue(boolean changed, long now, long lastWriteMs) {
        if (lastWriteMs <= 0L) {
            return true;
        }
        long age = now - lastWriteMs;
        if (age < 0L) {
            return true;
        }
        return age >= (changed ? FILE_MIN_INTERVAL_MS : FILE_HEARTBEAT_MS);
    }

    /** HookState 快照 → 平铺的 key/value（key 用 reg./hit./skip. 前缀，value 截断到 256）。 */
    private static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Feature f : HookState.touched()) {
            List<String> labels = HookState.labels(f);
            if (!labels.isEmpty()) {
                out.put(StatusProvider.PREFIX_REG + f.key, truncate(join(labels)));
            }
            long hits = HookState.hitCount(f);
            if (hits > 0) {
                out.put(StatusProvider.PREFIX_HIT + f.key, String.valueOf(hits));
            }
            String skip = HookState.skipReason(f);
            if (skip != null) {
                out.put(StatusProvider.PREFIX_SKIP + f.key, truncate(skip));
            }
        }
        return out;
    }

    private static String join(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String s : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE);
    }

    private static Context context() {
        Context c = sContext;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return sContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
