package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * socket 帧协议的纯 JVM 契约：4 字节大端长度、64KB 上限、严格 UTF-8、
 * 截断/超长/损坏输入一律 fail-open 返回 null。
 */
public class ReportSocketProtocolTest {

    @Test
    public void roundTripUsesBigEndianLengthPrefix() {
        String json = "{\"v\":1,\"session\":\"s1\",\"data\":{\"skip.a\":\"中文 \\\"x\\\"\"}}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = ReportSocketProtocol.encode(json);

        assertNotNull(frame);
        assertEquals(ReportSocketProtocol.HEADER_BYTES + payload.length, frame.length);
        assertEquals("长度头必须是大端", payload.length,
                ((frame[0] & 0xff) << 24) | ((frame[1] & 0xff) << 16)
                        | ((frame[2] & 0xff) << 8) | (frame[3] & 0xff));
        assertEquals(payload.length, ReportSocketProtocol.frameLength(frame));
        assertEquals(json, ReportSocketProtocol.decode(frame));
    }

    @Test
    public void rejectsTruncatedFrames() {
        byte[] frame = ReportSocketProtocol.encode("{\"v\":1}");
        assertNotNull(frame);

        assertNull(ReportSocketProtocol.decode(null));
        assertNull(ReportSocketProtocol.decode(new byte[0]));
        assertNull(ReportSocketProtocol.decode(new byte[]{0, 0, 0}));

        byte[] missingTail = new byte[frame.length - 1];
        System.arraycopy(frame, 0, missingTail, 0, missingTail.length);
        assertNull("payload 不足声明长度必须拒绝",
                ReportSocketProtocol.decode(missingTail));

        byte[] trailing = new byte[frame.length + 1];
        System.arraycopy(frame, 0, trailing, 0, frame.length);
        assertNull("尾部多余字节必须拒绝", ReportSocketProtocol.decode(trailing));

        byte[] headerOnly = new byte[ReportSocketProtocol.HEADER_BYTES];
        headerOnly[3] = 5;
        assertNull("只有头没有 payload", ReportSocketProtocol.decode(headerOnly));
    }

    @Test
    public void rejectsZeroAndOversizedFrames() {
        assertNull(ReportSocketProtocol.encode(null));
        assertNull(ReportSocketProtocol.encode(""));
        assertNull("编码侧超过 64KB 直接拒绝", ReportSocketProtocol.encode(
                "x".repeat(ReportSocketProtocol.MAX_FRAME_BYTES + 1)));

        assertNull(ReportSocketProtocol.decode(new byte[]{0, 0, 0, 0}));
        assertEquals(-1, ReportSocketProtocol.frameLength(null));
        assertLengthRejected(0);
        assertLengthRejected(-1);
        assertLengthRejected(ReportSocketProtocol.MAX_FRAME_BYTES + 1);

        // 头声明 65536 但实际 payload 为空/不足。
        byte[] declaredMax = {0x00, 0x01, 0x00, 0x00};
        assertEquals(ReportSocketProtocol.MAX_FRAME_BYTES,
                ReportSocketProtocol.frameLength(declaredMax));
        assertNull(ReportSocketProtocol.decode(declaredMax));

        assertNull(ReportSocketProtocol.decodePayload(
                new byte[ReportSocketProtocol.MAX_FRAME_BYTES + 1]));
        assertNull(ReportSocketProtocol.decodePayload(new byte[0]));
    }

    @Test
    public void rejectsInvalidUtf8Strictly() {
        // 0xC3 0x28 是非法两字节序列；默认 new String 会静默替换成 U+FFFD。
        assertNull(ReportSocketProtocol.decodePayload(
                new byte[]{(byte) 0xC3, 0x28}));
        assertNull(ReportSocketProtocol.decodePayload(
                new byte[]{(byte) 0x80}));
        assertNull(ReportSocketProtocol.decode(
                new byte[]{0, 0, 0, 2, (byte) 0xC3, 0x28}));
        assertNull(ReportSocketProtocol.decode(
                new byte[]{0, 0, 0, 3, (byte) 0xE2, (byte) 0x82, 0x28}));

        byte[] valid = "中文".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[ReportSocketProtocol.HEADER_BYTES + valid.length];
        frame[3] = (byte) valid.length;
        System.arraycopy(valid, 0, frame, ReportSocketProtocol.HEADER_BYTES,
                valid.length);
        assertEquals("中文", ReportSocketProtocol.decode(frame));
    }

    @Test
    public void messageCarriesMainFlagAndParsesBack() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("hit.splash_gate", "7");

        String main = ReportSocketProtocol.message(123L, "session-1", true, data);
        ReportSnapshot parsed = ReportSnapshot.parse(main);
        assertNotNull(parsed);
        assertEquals(123L, parsed.updatedAt());
        assertEquals("session-1", parsed.session());
        assertEquals(Boolean.TRUE, parsed.mainProcess());
        assertEquals(data, parsed.data());

        ReportSnapshot child = ReportSnapshot.parse(
                ReportSocketProtocol.message(124L, "session-2", false, data));
        assertNotNull(child);
        assertEquals(Boolean.FALSE, child.mainProcess());

        assertTrue(ReportSocketProtocol.message(1L, null, false, data)
                .contains("\"main\":false"));
    }

    @Test
    public void parseMessageRejectsBadPayloadButAcceptsValidFrame() {
        Map<String, String> data = Collections.singletonMap("reg.splash_gate", "x");
        byte[] frame = ReportSocketProtocol.encode(
                ReportSocketProtocol.message(1L, "s", true, data));
        assertNotNull(frame);
        byte[] payload = new byte[frame.length - ReportSocketProtocol.HEADER_BYTES];
        System.arraycopy(frame, ReportSocketProtocol.HEADER_BYTES, payload, 0,
                payload.length);
        assertNotNull(ReportSocketProtocol.parseMessage(payload));

        assertNull(ReportSocketProtocol.parseMessage(
                "not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(ReportSocketProtocol.parseMessage(new byte[0]));
    }

    @Test
    public void legacyFileSnapshotHasNoMainFlag() {
        ReportSnapshot parsed = ReportSnapshot.parse(
                ReportSnapshot.toJson(5L, "s", Collections.emptyMap()));
        assertNotNull(parsed);
        assertNull("文件快照不应带 main 字段", parsed.mainProcess());
        assertFalse(parsed.data().containsKey("main"));
    }

    /** 断言该长度被 frameLength/decode 拒绝。 */
    private static void assertLengthRejected(int declared) {
        byte[] header = {
                (byte) (declared >>> 24), (byte) (declared >>> 16),
                (byte) (declared >>> 8), (byte) declared
        };
        assertEquals(-1, ReportSocketProtocol.frameLength(header));
        assertNull(ReportSocketProtocol.decode(header));
    }
}
