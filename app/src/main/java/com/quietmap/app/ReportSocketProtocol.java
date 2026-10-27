package com.quietmap.app;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 诊断回传 socket 的纯 Java 帧协议：4 字节大端长度 + UTF-8 JSON。
 *
 * 不依赖 android.*，JVM 单测可覆盖正常帧、截断、超长、非法 UTF-8 等输入。
 * Android 侧的 LocalServerSocket / LocalSocket 胶水分别在
 * {@link ReportSocketServer} 与 hooks/ReportSocketSink。
 *
 * 约束：长度是 payload 字节数（不含 4 字节头）；单条 payload 上限 64KB，
 * 超长帧在解码侧直接拒绝、编码侧返回 null，绝不分配对应大小的缓冲区。
 */
public final class ReportSocketProtocol {

    /** 抽象命名空间 socket 名（不带 @ 前缀，由 LocalSocketAddress 生成）。 */
    public static final String SOCKET_NAME = "io.github.yigenhuobah.quietmap.report";

    public static final int HEADER_BYTES = 4;
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    private ReportSocketProtocol() {
    }

    /**
     * 编码为完整帧（头 + payload）。json 为 null/空、或 UTF-8 字节超过
     * {@link #MAX_FRAME_BYTES} 时返回 null，由调用方按发送失败处理。
     */
    public static byte[] encode(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length <= 0 || payload.length > MAX_FRAME_BYTES) {
            return null;
        }
        byte[] frame = new byte[HEADER_BYTES + payload.length];
        ByteBuffer.wrap(frame).putInt(payload.length);
        System.arraycopy(payload, 0, frame, HEADER_BYTES, payload.length);
        return frame;
    }

    /**
     * 解析 4 字节头里的 payload 长度。非法（不足 4 字节、0、负数、超过 64KB）
     * 返回 -1。
     */
    public static int frameLength(byte[] header) {
        if (header == null || header.length < HEADER_BYTES) {
            return -1;
        }
        long length = ((header[0] & 0xffL) << 24)
                | ((header[1] & 0xffL) << 16)
                | ((header[2] & 0xffL) << 8)
                | (header[3] & 0xffL);
        if (length <= 0L || length > MAX_FRAME_BYTES) {
            return -1;
        }
        return (int) length;
    }

    /**
     * 解完整帧。任何结构问题（不足 4 字节、长度非法、截断、尾部多余字节、
     * 非法 UTF-8）一律返回 null，不抛异常。
     */
    public static String decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_BYTES) {
            return null;
        }
        int length = frameLength(frame);
        if (length < 0 || frame.length != HEADER_BYTES + length) {
            return null;
        }
        byte[] payload = new byte[length];
        System.arraycopy(frame, HEADER_BYTES, payload, 0, length);
        return decodePayload(payload);
    }

    /**
     * 解 payload（不含头）。空、超长、非法 UTF-8 返回 null；严格 UTF-8 解码，
     * 不接受替换字符，避免损坏帧被当成合法 JSON。
     */
    public static String decodePayload(byte[] payload) {
        if (payload == null || payload.length <= 0
                || payload.length > MAX_FRAME_BYTES) {
            return null;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** socket 通道的消息体：与文件快照同一 schema，额外带 main 标记。 */
    public static String message(long updatedAt, String session, boolean mainProcess,
                                 Map<String, String> data) {
        return ReportSnapshot.toJson(updatedAt, session,
                Boolean.valueOf(mainProcess), data);
    }

    /** payload → 快照；JSON 损坏/版本不符返回 null。 */
    public static ReportSnapshot parseMessage(byte[] payload) {
        String json = decodePayload(payload);
        if (json == null) {
            return null;
        }
        return ReportSnapshot.parse(json);
    }
}
