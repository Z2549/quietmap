package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 快照 JSON 的纯 JVM 契约：往返、转义、损坏输入 fail-open、跨进程合并。
 */
public class ReportSnapshotTest {

    @Test
    public void roundTripPreservesTimestampAndValues() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash|tryShowSplashView");
        data.put("hit.bg_msg_fetch", "42");
        data.put("skip.shake_h5", "中文 跳过 \"原因\" \\ 反斜杠\n换行\t制表\u0001控制符");
        long updatedAt = 1_700_000_000_123L;

        String json = ReportSnapshot.toJson(updatedAt, data);
        ReportSnapshot parsed = ReportSnapshot.parse(json);

        assertNotNull(parsed);
        assertEquals(updatedAt, parsed.updatedAt());
        assertEquals(data, parsed.data());
    }

    @Test
    public void emptyJsonIsValidButHasNoData() {
        ReportSnapshot parsed = ReportSnapshot.parse(ReportSnapshot.emptyJson());
        assertNotNull(parsed);
        assertEquals(0L, parsed.updatedAt());
        assertTrue(parsed.data().isEmpty());
    }

    @Test
    public void toJsonIsStableAndSortsKeys() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("skip.b", "2");
        data.put("hit.a", "1");
        String first = ReportSnapshot.toJson(10L, data);
        String second = ReportSnapshot.toJson(10L, data);
        assertEquals(first, second);
        assertTrue(first.indexOf("hit.a") < first.indexOf("skip.b"));
    }

    @Test
    public void parseRejectsCorruptOrIncompleteJson() {
        assertNull(ReportSnapshot.parse(null));
        assertNull(ReportSnapshot.parse(""));
        assertNull(ReportSnapshot.parse("not json"));
        assertNull(ReportSnapshot.parse("{"));
        assertNull(ReportSnapshot.parse("{\"v\":1}"));
        assertNull(ReportSnapshot.parse("{\"v\":2,\"updated_at\":1,\"data\":{}}"));
        assertNull(ReportSnapshot.parse("{\"v\":1,\"updated_at\":1}"));
        assertNull(ReportSnapshot.parse("{\"v\":1,\"updated_at\":\"1\",\"data\":{}}"));
        assertNull(ReportSnapshot.parse("{\"v\":1,\"updated_at\":1,\"data\":[]}"));
        assertNull(ReportSnapshot.parse(
                "{\"v\":1,\"updated_at\":1,\"data\":{\"reg.a\":1}}"));
        assertNull(ReportSnapshot.parse(
                "{\"v\":1,\"updated_at\":1,\"data\":{\"reg.a\":\"x\"}} trailing"));
        assertNull(ReportSnapshot.parse(
                "{\"v\":1,\"updated_at\":1.5,\"data\":{}}"));
        assertNull(ReportSnapshot.parse("{\"v\":1,\"updated_at\":-1,\"data\":{}}"));
    }

    @Test
    public void parseSkipsUnknownFieldsForForwardCompatibility() {
        ReportSnapshot parsed = ReportSnapshot.parse(
                "{\"v\":1,\"updated_at\":5,\"future\":{\"a\":[1,2,true,null]},"
                        + "\"data\":{\"hit.a\":\"3\"}}");
        assertNotNull(parsed);
        assertEquals(5L, parsed.updatedAt());
        assertEquals("3", parsed.data().get("hit.a"));
    }

    @Test
    public void parseRejectsExcessiveNesting() {
        StringBuilder sb = new StringBuilder("{\"v\":1,\"updated_at\":1,\"data\":{},\"x\":");
        for (int i = 0; i < 40; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 40; i++) {
            sb.append(']');
        }
        sb.append('}');
        assertNull(ReportSnapshot.parse(sb.toString()));
    }

    @Test
    public void mergeTakesMaxHitAndOverwritesStrings() {
        Map<String, String> existing = new TreeMap<>();
        existing.put("hit.a", "5");
        existing.put("reg.a", "old");
        existing.put("skip.keep", "keep");

        Map<String, String> incoming = new TreeMap<>();
        incoming.put("hit.a", "3");
        incoming.put("reg.a", "new");
        incoming.put("hit.b", "7");

        Map<String, String> merged = ReportSnapshot.merge(existing, incoming);
        assertEquals("5", merged.get("hit.a"));
        assertEquals("new", merged.get("reg.a"));
        assertEquals("keep", merged.get("skip.keep"));
        assertEquals("7", merged.get("hit.b"));

        Map<String, String> higher = new TreeMap<>();
        higher.put("hit.a", "9");
        assertEquals("9", ReportSnapshot.merge(existing, higher).get("hit.a"));
    }

    @Test
    public void mergeIgnoresMalformedHitAndNullValues() {
        Map<String, String> existing = Collections.singletonMap("hit.a", "5");
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("hit.a", "not-a-number");
        incoming.put("reg.a", null);
        Map<String, String> merged = ReportSnapshot.merge(existing, incoming);
        assertEquals("5", merged.get("hit.a"));
        assertFalse(merged.containsKey("reg.a"));
    }
}
