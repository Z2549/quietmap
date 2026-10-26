package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「文件比 SharedPreferences 新才用文件」的选择逻辑与读取辅助方法。
 */
public class ReportStateTest {

    @Test
    public void fileIsUsedWhenProviderHasNoData() {
        ReportState picked = ReportState.pick(entry(1_000L, 1_000L, "reg.splash_gate", "x"),
                ReportState.none());
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("x", picked.reg("splash_gate"));
    }

    @Test
    public void fileIsUsedWhenProviderHasNoTimestamp() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "old"), 0L);
        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, "reg.splash_gate", "new"), provider);
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("new", picked.reg("splash_gate"));
    }

    @Test
    public void newerOrEqualFileWins() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "provider"), 2_000L);
        assertEquals(ReportState.Source.FILE, ReportState.pick(
                entry(2_000L, 2_000L, "reg.splash_gate", "file"), provider).source());
        assertEquals(ReportState.Source.FILE, ReportState.pick(
                entry(2_001L, 2_001L, "reg.splash_gate", "file"), provider).source());
    }

    @Test
    public void olderFileFallsBackToProvider() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "provider"), 2_000L);
        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, "reg.splash_gate", "file"), provider);
        assertEquals(ReportState.Source.PROVIDER, picked.source());
        assertEquals("provider", picked.reg("splash_gate"));
    }

    @Test
    public void bothEmptyReturnsNone() {
        assertEquals(ReportState.Source.NONE, ReportState.pick(null, ReportState.none()).source());
        assertFalse(ReportState.none().hasData());
        assertNull(ReportState.none().path());
    }

    @Test
    public void hitParsesAndClampsNegativeValues() {
        Map<String, String> data = new TreeMap<>();
        data.put("hit.splash_gate", "7");
        data.put("hit.bg_msg_fetch", "-3");
        data.put("hit.shake_h5", "bad");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals(7L, state.hit("splash_gate"));
        assertEquals(0L, state.hit("bg_msg_fetch"));
        assertEquals(0L, state.hit("shake_h5"));
        assertTrue(state.hasData());
    }

    @Test
    public void providerValuesAreSanitized() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "ok");
        data.put("unknown.key", "x");
        data.put("hit.splash_gate", "not-a-number");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals("ok", state.reg("splash_gate"));
        assertEquals(0L, state.hit("splash_gate"));
        assertNull(state.value(ReportProtocol.PREFIX_SKIP, "splash_gate"));
    }

    private static ReportFileStore.Entry entry(long mtime, long updatedAt,
                                               String key, String value) {
        ReportSnapshot snapshot = new ReportSnapshot(updatedAt,
                Collections.singletonMap(key, value));
        return new ReportFileStore.Entry(new File("qm_report.json"), snapshot, mtime);
    }
}
