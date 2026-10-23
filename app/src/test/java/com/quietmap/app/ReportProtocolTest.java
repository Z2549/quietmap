package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * StatusProvider 的纯 Java 协议规则：键白名单、值长度、hit 最大值合并。
 * 这些规则决定跨进程写入 prefs 的边界，改动必须同步更新本测试。
 */
public class ReportProtocolTest {

    @Test
    public void validKeyAcceptsRepresentativePrefixes() {
        assertTrue(ReportProtocol.validKey("reg.splash_gate"));
        assertTrue(ReportProtocol.validKey("hit.bg_msg_fetch"));
        assertTrue(ReportProtocol.validKey("skip.shake_h5"));
    }

    @Test
    public void validKeyAcceptsAll57GeneratedKeys() {
        int count = 0;
        for (Feature f : Feature.values()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            count += 3;
        }
        assertEquals(57, count);
    }

    @Test
    public void validKeyRejectsUnknownKeys() {
        assertFalse(ReportProtocol.validKey("foo"));
        assertFalse(ReportProtocol.validKey("hit.not_a_feature"));
        assertFalse(ReportProtocol.validKey("hit." + "x".repeat(125)));
        assertFalse(ReportProtocol.validKey(null));
        assertFalse(ReportProtocol.validKey(""));
        assertFalse(ReportProtocol.validKey("REG.splash_gate"));
        assertFalse(ReportProtocol.validKey("reg.splash_gate "));
    }

    @Test
    public void mergeHitKeepsLargerValue() {
        assertEquals(10L, ReportProtocol.mergeHit(0L, 10L));
        assertEquals(10L, ReportProtocol.mergeHit(10L, 8L));
    }

    @Test
    public void mergeHitTreatsNegativeAsZero() {
        assertEquals(3L, ReportProtocol.mergeHit(-5L, 3L));
        assertEquals(0L, ReportProtocol.mergeHit(-5L, -3L));
        assertEquals(7L, ReportProtocol.mergeHit(7L, -1L));
    }

    @Test
    public void isValidValueRejectsNullAndOversizedValues() {
        assertTrue(ReportProtocol.isValidValue(""));
        assertTrue(ReportProtocol.isValidValue("a".repeat(256)));
        assertFalse(ReportProtocol.isValidValue(null));
        assertFalse(ReportProtocol.isValidValue("a".repeat(257)));
    }

    @Test
    public void validKeyRejectsPrefixOnlyAndBoundaryLengths() {
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_REG));
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT));
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP));
        // 128 字符恰好等于上限但不在白名单；129 与超长输入同样拒绝。
        assertFalse(ReportProtocol.validKey("reg." + "a".repeat(ReportProtocol.MAX_KEY - 4)));
        assertFalse(ReportProtocol.validKey("reg." + "a".repeat(ReportProtocol.MAX_KEY)));
        assertFalse(ReportProtocol.validKey("a".repeat(10_000)));
    }

    @Test
    public void parseHitRejectsMalformedValues() {
        // QuietMap 协议不使用 JSON：畸形文本（非数字/空白/溢出）等价于畸形载荷。
        assertNull(ReportProtocol.parseHit(null));
        assertNull(ReportProtocol.parseHit(""));
        assertNull(ReportProtocol.parseHit("abc"));
        assertNull(ReportProtocol.parseHit("1.5"));
        assertNull(ReportProtocol.parseHit(" 1"));
        assertNull(ReportProtocol.parseHit("1 "));
        assertNull(ReportProtocol.parseHit("0x10"));
        assertNull(ReportProtocol.parseHit("9223372036854775808"));
        assertNull(ReportProtocol.parseHit("a".repeat(ReportProtocol.MAX_VALUE + 1)));
    }

    @Test
    public void parseHitAcceptsDecimalCounts() {
        assertEquals(Long.valueOf(0), ReportProtocol.parseHit("0"));
        assertEquals(Long.valueOf(42), ReportProtocol.parseHit("42"));
        assertEquals(Long.valueOf(Long.MAX_VALUE),
                ReportProtocol.parseHit(String.valueOf(Long.MAX_VALUE)));
        // 负数交给 mergeHit 按 0 处理，不在这里吞掉。
        assertEquals(Long.valueOf(-1), ReportProtocol.parseHit("-1"));
    }

    @Test
    public void mergeHitAcrossProcessesTakesMaximum() {
        assertEquals(7L, ReportProtocol.mergeHit(ReportProtocol.mergeHit(3L, 7L), 5L));
        assertEquals(9L, ReportProtocol.mergeHit(2L, ReportProtocol.mergeHit(9L, 4L)));
        assertEquals(Long.MAX_VALUE, ReportProtocol.mergeHit(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, ReportProtocol.mergeHit(1L, Long.MAX_VALUE));
    }
}
