package com.quietmap.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * failedFeatureCount 的纯逻辑部分：FILE_STALE、TTL、未回传不计数；
 * 已开启且（reg 为空或 crit=1）才计数，普通 skip 不再计入横幅。
 * Context/SharedPreferences 胶水不在这里覆盖。
 */
public class StatusProviderTest {

    private static final long NOW = 1_000_000_000L;

    @Test
    public void fileStaleNeverCountsAsFailure() {
        Map<String, String> data = new TreeMap<>();
        // 在 PROVIDER 状态下这条会被判失败（有回传、无 reg、有 skip）。
        data.put("skip.splash_gate", "class not found");
        ReportState stale = ReportState.fromStaleFile(entry(data));

        assertEquals(ReportState.Source.FILE_STALE, stale.source());
        assertEquals("过期文件只用于展示，不得计入失败数", 0,
                StatusProvider.failedFeatureCount(stale, f -> true, NOW));
    }

    @Test
    public void providerStateCountsOnlyReportedAndEnabledFailures() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(data, NOW);

        assertEquals(1, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
        assertEquals("用户关闭的功能即使 skip 也不计入", 0,
                StatusProvider.failedFeatureCount(provider, f -> false, NOW));
        assertEquals("没有回传的功能不计入", 0,
                StatusProvider.failedFeatureCount(ReportState.none(), f -> true, NOW));
    }

    @Test
    public void registeredFeatureWithOptionalSkipIsNotFailure() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("skip.splash_gate", "方法未找到 x");
        ReportState provider = ReportState.fromProvider(data, NOW);
        assertEquals("有成功挂载点时可选 skip 只展示，不计入横幅", 0,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void registeredFeatureWithoutSkipIsNotFailure() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "canShowSplash"), NOW);
        assertEquals(0, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void criticalFeatureCountsEvenWhenRegistered() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("crit.splash_gate", "1");
        ReportState provider = ReportState.fromProvider(data, NOW);
        assertEquals("crit=1 表示整体失效，必须计入失败", 1,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void emptyCritDoesNotCountAsFailure() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("crit.splash_gate", "");
        ReportState provider = ReportState.fromProvider(data, NOW);
        assertEquals(0, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void staleReportDataIsIgnored() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(
                data, NOW - ReportProtocol.DEFAULT_TTL_MS - 1L);
        assertEquals("超过 TTL 说明目标进程可能已退出，按未知处理", 0,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void freshReportDataAtTtlBoundaryStillCounts() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(
                data, NOW - ReportProtocol.DEFAULT_TTL_MS);
        assertEquals(1, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    private static ReportFileStore.Entry entry(Map<String, String> data) {
        return new ReportFileStore.Entry(new File("qm_report.json"),
                new ReportSnapshot(1L, "session-1", data), 1L);
    }
}
