package com.quietmap.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * failedFeatureCount 的纯逻辑部分：FILE_STALE 不计数、未回传不计数、
 * 已开启且明确失败才计数。Context/SharedPreferences 胶水不在这里覆盖。
 */
public class StatusProviderTest {

    @Test
    public void fileStaleNeverCountsAsFailure() {
        Map<String, String> data = new TreeMap<>();
        // 在 PROVIDER 状态下这条会被判失败（有回传、无 reg、有 skip）。
        data.put("skip.splash_gate", "class not found");
        ReportState stale = ReportState.fromStaleFile(entry(data));

        assertEquals(ReportState.Source.FILE_STALE, stale.source());
        assertEquals("过期文件只用于展示，不得计入失败数", 0,
                StatusProvider.failedFeatureCount(stale, f -> true));
    }

    @Test
    public void providerStateCountsOnlyReportedAndEnabledFailures() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(data, 1L);

        assertEquals(1, StatusProvider.failedFeatureCount(provider, f -> true));
        assertEquals("用户关闭的功能即使 skip 也不计入", 0,
                StatusProvider.failedFeatureCount(provider, f -> false));
        assertEquals("没有回传的功能不计入", 0,
                StatusProvider.failedFeatureCount(ReportState.none(), f -> true));
    }

    @Test
    public void registeredFeatureWithoutSkipIsNotFailure() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "canShowSplash"), 1L);
        assertEquals(0, StatusProvider.failedFeatureCount(provider, f -> true));
    }

    private static ReportFileStore.Entry entry(Map<String, String> data) {
        return new ReportFileStore.Entry(new File("qm_report.json"),
                new ReportSnapshot(1L, "session-1", data), 1L);
    }
}
