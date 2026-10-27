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
    public void countsParseAndClampNegativeValues() {
        Map<String, String> data = new TreeMap<>();
        data.put("hit.splash_gate", "7");
        data.put("hit.bg_msg_fetch", "-3");
        data.put("hit.shake_h5", "bad");
        data.put("entry.splash_gate", "4");
        data.put("entry.bg_msg_fetch", "-3");
        data.put("entry.shake_h5", "bad");
        data.put("entry.msg_center", "9223372036854775808");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals(7L, state.hit("splash_gate"));
        assertEquals(0L, state.hit("bg_msg_fetch"));
        assertEquals(0L, state.hit("shake_h5"));
        assertEquals(4L, state.entry("splash_gate"));
        assertEquals(0L, state.entry("bg_msg_fetch"));
        assertEquals(0L, state.entry("shake_h5"));
        assertEquals("溢出文本按非法值丢弃", 0L, state.entry("msg_center"));
        assertTrue(state.hasData());
    }

    @Test
    public void entryOnlyDataCountsAsData() {
        ReportState state = ReportState.fromProvider(
                Collections.singletonMap("entry.splash_gate", "2"), 1L);
        assertTrue("只有 entry 也算已回传", state.hasData());
        assertEquals(2L, state.entry("splash_gate"));
        assertEquals(0L, state.hit("splash_gate"));
    }

    @Test
    public void legacySnapshotWithoutEntryStillWorks() {
        ReportState state = ReportState.fromProvider(
                Collections.singletonMap("hit.splash_gate", "3"), 1L);
        assertEquals(3L, state.hit("splash_gate"));
        assertEquals("旧快照没有 entry 字段时按 0 处理", 0L, state.entry("splash_gate"));
        assertTrue(state.hasData());
    }

    @Test
    public void pickMergesKeysFromBothSourcesByKey() {
        Map<String, String> fileData = new TreeMap<>();
        fileData.put("reg.splash_gate", "file");
        Map<String, String> providerData = new TreeMap<>();
        providerData.put("reg.splash_gate", "provider");
        providerData.put("reg.bg_msg_fetch", "provider-only");
        ReportState provider = ReportState.fromProvider(providerData, 1_000L);

        ReportState picked = ReportState.pick(entry(2_000L, 2_000L, fileData), provider);
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("file", picked.reg("splash_gate"));
        assertEquals("provider-only", picked.reg("bg_msg_fetch"));
    }

    @Test
    public void pickTakesMaxCountsAndKeepsNonEmptyValue() {
        Map<String, String> fileData = new TreeMap<>();
        fileData.put("hit.splash_gate", "3");
        fileData.put("entry.splash_gate", "8");
        fileData.put("skip.splash_gate", "");
        Map<String, String> providerData = new TreeMap<>();
        providerData.put("hit.splash_gate", "9");
        providerData.put("entry.splash_gate", "2");
        providerData.put("skip.splash_gate", "provider skip");
        ReportState provider = ReportState.fromProvider(providerData, 1_000L);

        ReportState picked = ReportState.pick(entry(2_000L, 2_000L, fileData), provider);
        assertEquals(9L, picked.hit("splash_gate"));
        assertEquals("entry 跨来源同样取最大值", 8L, picked.entry("splash_gate"));
        assertEquals("较新文件为空时保留旧的非空值", "provider skip",
                picked.skip("splash_gate"));
    }

    @Test
    public void pickKeepsEntryPresentInOnlyOneSource() {
        Map<String, String> fileData = Collections.singletonMap(
                "entry.splash_gate", "5");
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("entry.bg_msg_fetch", "3"), 1_000L);

        ReportState picked = ReportState.pick(entry(2_000L, 2_000L, fileData), provider);
        assertEquals(5L, picked.entry("splash_gate"));
        assertEquals(3L, picked.entry("bg_msg_fetch"));
    }

    @Test
    public void staleFileStateKeepsDataAndTimestamp() {
        ReportState stale = ReportState.fromStaleFile(
                entry(1_000L, 1_000L, "reg.splash_gate", "last"));
        assertEquals(ReportState.Source.FILE_STALE, stale.source());
        assertTrue(stale.hasData());
        assertEquals("last", stale.reg("splash_gate"));
        assertEquals(1_000L, stale.updatedAt());
    }

    @Test
    public void providerValuesAreSanitized() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "ok");
        data.put("unknown.key", "x");
        data.put("hit.splash_gate", "not-a-number");
        data.put("entry.splash_gate", "not-a-number");
        data.put("entry.bg_msg_fetch", "6");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals("ok", state.reg("splash_gate"));
        assertEquals(0L, state.hit("splash_gate"));
        assertEquals(0L, state.entry("splash_gate"));
        assertEquals(6L, state.entry("bg_msg_fetch"));
        assertNull("非法 entry 值必须被 sanitize 丢弃",
                state.value(ReportProtocol.PREFIX_ENTRY, "splash_gate"));
        assertNull(state.value(ReportProtocol.PREFIX_SKIP, "splash_gate"));
    }

    @Test
    public void pickKeepsSocketSourceWhenPrefsAreNewer() {
        ReportState socket = ReportState.fromSocket(
                Collections.singletonMap("reg.splash_gate", "socket"), 2_000L);
        assertEquals(ReportState.Source.SOCKET, socket.source());

        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, "reg.splash_gate", "file"), socket);
        assertEquals("prefs 较新时必须保留 socket 来源",
                ReportState.Source.SOCKET, picked.source());
        assertEquals("socket", picked.reg("splash_gate"));

        ReportState fileWins = ReportState.pick(
                entry(3_000L, 3_000L, "reg.splash_gate", "file"), socket);
        assertEquals(ReportState.Source.FILE, fileWins.source());
        assertEquals("file", fileWins.reg("splash_gate"));
    }

    private static ReportFileStore.Entry entry(long mtime, long updatedAt,
                                               String key, String value) {
        return entry(mtime, updatedAt, Collections.singletonMap(key, value));
    }

    private static ReportFileStore.Entry entry(long mtime, long updatedAt,
                                               Map<String, String> data) {
        ReportSnapshot snapshot = new ReportSnapshot(updatedAt, data);
        return new ReportFileStore.Entry(new File("qm_report.json"), snapshot, mtime);
    }
}
