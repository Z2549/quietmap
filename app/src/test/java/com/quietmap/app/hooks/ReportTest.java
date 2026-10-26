package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Report 的纯 JVM 逻辑：通道顺序（socket → Provider → 文件）、去重/心跳、
 * 文件写入时机（首次立即写、变化节流 45 秒、未变化 5 分钟心跳）。
 */
public class ReportTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void firstWriteIsAlwaysDue() {
        assertTrue(Report.fileWriteDue(true, T0, 0L));
        assertTrue(Report.fileWriteDue(false, T0, 0L));
    }

    @Test
    public void changedSnapshotIsThrottledThenWritten() {
        assertFalse(Report.fileWriteDue(true,
                T0 + Report.FILE_MIN_INTERVAL_MS - 1, T0));
        assertTrue(Report.fileWriteDue(true,
                T0 + Report.FILE_MIN_INTERVAL_MS, T0));
    }

    @Test
    public void unchangedSnapshotIsRewrittenByHeartbeat() {
        assertFalse("未变化时不应跟着 45 秒节流写盘",
                Report.fileWriteDue(false,
                        T0 + Report.FILE_HEARTBEAT_MS - 1, T0));
        assertTrue("5 分钟心跳必须重写，刷新文件 mtime",
                Report.fileWriteDue(false,
                        T0 + Report.FILE_HEARTBEAT_MS, T0));
        assertFalse(Report.fileWriteDue(false,
                T0 + Report.FILE_MIN_INTERVAL_MS, T0));
    }

    @Test
    public void clockRollbackAllowsOneWrite() {
        assertTrue(Report.fileWriteDue(false, T0 - 1, T0));
    }

    // ---------------------------------------------------------------- 通道顺序

    @Test
    public void socketSuccessShortCircuitsProviderAndFile() {
        List<String> calls = new ArrayList<>();
        String used = Report.deliver(
                channel(calls, "socket", true),
                channel(calls, "provider", true),
                channel(calls, "file", true));
        assertEquals("socket", used);
        assertEquals("socket 成功后不得再调 Provider/文件",
                Collections.singletonList("socket"), calls);
    }

    @Test
    public void socketFailureFallsBackToProviderAndSkipsFile() {
        List<String> calls = new ArrayList<>();
        String used = Report.deliver(
                channel(calls, "socket", false),
                channel(calls, "provider", true),
                channel(calls, "file", true));
        assertEquals("provider", used);
        assertEquals("Provider 成功后不得再写文件",
                Arrays.asList("socket", "provider"), calls);
    }

    @Test
    public void providerFailureFallsBackToFile() {
        List<String> calls = new ArrayList<>();
        String used = Report.deliver(
                channel(calls, "socket", false),
                channel(calls, "provider", false),
                channel(calls, "file", true));
        assertEquals("file", used);
        assertEquals(Arrays.asList("socket", "provider", "file"), calls);
    }

    @Test
    public void allChannelsFailReturnsNull() {
        assertNull(Report.deliver(
                channel(new ArrayList<>(), "socket", false),
                channel(new ArrayList<>(), "provider", false),
                channel(new ArrayList<>(), "file", false)));
    }

    @Test
    public void channelExceptionIsTreatedAsFailureAndNextIsTried() {
        List<String> calls = new ArrayList<>();
        Report.Channel throwing = () -> {
            calls.add("socket");
            throw new IllegalStateException("boom");
        };
        String used = Report.deliver(throwing,
                channel(calls, "provider", true),
                channel(calls, "file", true));
        assertEquals("provider", used);
        assertEquals(Arrays.asList("socket", "provider"), calls);
    }

    // ---------------------------------------------------------------- 去重/心跳

    @Test
    public void unchangedBatchWithinWindowIsDeduplicated() {
        Map<String, String> batch = snapshot("7");
        assertFalse(Report.shouldSend(batch, new TreeMap<>(batch),
                "s1", "s1", T0, T0));
        assertFalse(Report.shouldSend(batch, new TreeMap<>(batch),
                "s1", "s1", T0 + Report.TRANSPORT_HEARTBEAT_MS - 1, T0));
    }

    @Test
    public void changedValueOrSessionForcesSend() {
        Map<String, String> batch = snapshot("7");
        Map<String, String> changed = snapshot("8");
        assertTrue(Report.shouldSend(changed, new TreeMap<>(batch),
                "s1", "s1", T0, T0));
        assertTrue("session 变化必须重发（覆盖主进程清理后的子进程数据）",
                Report.shouldSend(batch, new TreeMap<>(batch),
                        "s2", "s1", T0, T0));
        assertTrue("首次发送没有 lastSession",
                Report.shouldSend(batch, new TreeMap<>(batch),
                        "s1", null, T0, T0));
    }

    @Test
    public void heartbeatForcesSendAfterWindow() {
        Map<String, String> batch = snapshot("7");
        assertTrue(Report.shouldSend(batch, new TreeMap<>(batch),
                "s1", "s1", T0 + Report.TRANSPORT_HEARTBEAT_MS, T0));
        assertTrue("时钟回拨时允许重发", Report.shouldSend(batch,
                new TreeMap<>(batch), "s1", "s1", T0 - 1, T0));
        assertFalse(Report.shouldSend(Collections.emptyMap(),
                Collections.emptyMap(), "s1", "s1", T0, T0));
    }

    private static Map<String, String> snapshot(String hit) {
        Map<String, String> data = new TreeMap<>();
        data.put("hit.splash_gate", hit);
        return data;
    }

    private static Report.Channel channel(List<String> calls, String name,
                                          boolean result) {
        return () -> {
            calls.add(name);
            return result;
        };
    }
}
