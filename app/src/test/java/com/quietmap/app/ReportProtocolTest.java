package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * StatusProvider 的纯 Java 协议规则：键白名单、值长度、hit/entry 计数最大值合并。
 * 这些规则决定跨进程写入 prefs 的边界，改动必须同步更新本测试。
 */
public class ReportProtocolTest {

    @Test
    public void validKeyAcceptsRepresentativePrefixes() {
        assertTrue(ReportProtocol.validKey("reg.splash_gate"));
        assertTrue(ReportProtocol.validKey("hit.bg_msg_fetch"));
        assertTrue(ReportProtocol.validKey("entry.bg_msg_fetch"));
        assertTrue(ReportProtocol.validKey("skip.shake_h5"));
    }

    @Test
    public void validKeyAcceptsAll88GeneratedKeys() {
        int count = 0;
        for (Feature f : Feature.values()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_ENTRY + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            count += 4;
        }
        assertEquals(88, count);
    }

    @Test
    public void validKeyRejectsUnknownKeys() {
        assertFalse(ReportProtocol.validKey("foo"));
        assertFalse(ReportProtocol.validKey("hit.not_a_feature"));
        assertFalse(ReportProtocol.validKey("entry.not_a_feature"));
        assertFalse(ReportProtocol.validKey("hit." + "x".repeat(125)));
        assertFalse(ReportProtocol.validKey(null));
        assertFalse(ReportProtocol.validKey(""));
        assertFalse(ReportProtocol.validKey("REG.splash_gate"));
        assertFalse(ReportProtocol.validKey("ENTRY.splash_gate"));
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
    public void countKeysCoverHitAndEntryOnly() {
        assertTrue(ReportProtocol.isCountKey("hit.splash_gate"));
        assertTrue(ReportProtocol.isCountKey("entry.splash_gate"));
        assertFalse(ReportProtocol.isCountKey("reg.splash_gate"));
        assertFalse(ReportProtocol.isCountKey("skip.splash_gate"));
        assertFalse(ReportProtocol.isCountKey(null));
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
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_ENTRY));
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

    @Test
    public void resetSessionOnlyForNewMainProcessSession() {
        assertTrue(ReportProtocol.shouldResetSession(null, "s1", true));
        assertTrue(ReportProtocol.shouldResetSession("s0", "s1", true));
        assertFalse("同一会话不清理", ReportProtocol.shouldResetSession("s1", "s1", true));
        assertFalse("子进程不能清空其他进程数据",
                ReportProtocol.shouldResetSession("s0", "s1", false));
        assertFalse("没有 session 的旧客户端不清理",
                ReportProtocol.shouldResetSession("s0", null, true));
        assertFalse(ReportProtocol.shouldResetSession("s0", "", true));
    }

    @Test
    public void valueChangedOnlyWhenActuallyWritten() {
        assertTrue(ReportProtocol.valueChanged("reg.a", null, "x"));
        assertFalse(ReportProtocol.valueChanged("reg.a", "x", "x"));
        assertTrue(ReportProtocol.valueChanged("reg.a", "x", "y"));
        assertFalse("skip 相同不应刷新时间戳",
                ReportProtocol.valueChanged("skip.a", "old", "old"));
        assertTrue(ReportProtocol.valueChanged("hit.a", "3", "5"));
        assertFalse("hit 变小不写", ReportProtocol.valueChanged("hit.a", "5", "3"));
        assertFalse("hit 非法不写", ReportProtocol.valueChanged("hit.a", "5", "bad"));
        assertTrue(ReportProtocol.valueChanged("hit.a", null, "1"));
        // entry 与 hit 共用同一套非负计数规则。
        assertTrue(ReportProtocol.valueChanged("entry.a", "3", "5"));
        assertFalse("entry 变小不写", ReportProtocol.valueChanged("entry.a", "5", "3"));
        assertFalse("entry 负数不写", ReportProtocol.valueChanged("entry.a", "5", "-1"));
        assertFalse("entry 非法不写",
                ReportProtocol.valueChanged("entry.a", "5", "bad"));
        assertFalse("entry 溢出按非法处理", ReportProtocol.valueChanged(
                "entry.a", "5", "9223372036854775808"));
        assertTrue(ReportProtocol.valueChanged("entry.a", null, "1"));
    }
}
