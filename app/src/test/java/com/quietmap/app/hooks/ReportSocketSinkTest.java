package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.ReportSocketProtocol;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReportSocketSink 的失败语义：任何连接/写入异常都返回 false，不向 Report
 * 主循环抛出；编码在发送前完成，超长/空内容不会触达 transport。
 */
public class ReportSocketSinkTest {

    @Test
    public void transportThrowingReturnsFalseInsteadOfPropagating() {
        boolean result = ReportSocketSink.write("{\"v\":1}",
                frame -> {
                    throw new IOException("ENOENT");
                });
        assertFalse(result);
    }

    @Test
    public void transportReturningFalsePropagatesFalse() {
        assertFalse(ReportSocketSink.write("{\"v\":1}", frame -> false));
    }

    @Test
    public void transportReceivesCompleteLengthPrefixedFrame() {
        String json = "{\"v\":1,\"main\":true,\"data\":{\"skip.a\":\"中文\"}}";
        AtomicReference<byte[]> received = new AtomicReference<>();

        assertTrue(ReportSocketSink.write(json, frame -> {
            received.set(frame);
            return true;
        }));

        assertNotNull(received.get());
        assertEquals(json, ReportSocketProtocol.decode(received.get()));
    }

    @Test
    public void nullEmptyAndOversizedJsonNeverReachTransport() {
        AtomicBoolean called = new AtomicBoolean();
        ReportSocketSink.Transport transport = frame -> {
            called.set(true);
            return true;
        };

        assertFalse(ReportSocketSink.write(null, transport));
        assertFalse(ReportSocketSink.write("", transport));
        assertFalse(ReportSocketSink.write(
                "x".repeat(ReportSocketProtocol.MAX_FRAME_BYTES + 1), transport));
        assertFalse(ReportSocketSink.write("{\"v\":1}", null));
        assertFalse("空/超长内容不应触达 socket", called.get());
    }
}
