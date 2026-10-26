package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * OpsActivityHooks 的纯 JVM 逻辑：真机诊断日志限流。
 * Hooker 本身需要 XposedModule/Chain，JVM 单测无法构造；
 * 这里锁定「同一 key 10 秒最多一条」的窗口语义，防止日志刷屏。
 */
public class OpsActivityHooksTest {

    @Test
    public void logWindowIsTenSeconds() {
        assertEquals("同一 key 的日志间隔必须是 10 秒",
                10_000L, OpsActivityHooks.LOG_WINDOW_MS);
    }

    @Test
    public void throttleAllowsOncePerWindow() {
        OpsActivityHooks.LogThrottle throttle = new OpsActivityHooks.LogThrottle();
        assertTrue(throttle.allow("k", 0L));
        assertFalse(throttle.allow("k", 1L));
        assertFalse(throttle.allow("k", OpsActivityHooks.LOG_WINDOW_MS - 1));
        assertTrue(throttle.allow("k", OpsActivityHooks.LOG_WINDOW_MS));
        assertFalse(throttle.allow("k", OpsActivityHooks.LOG_WINDOW_MS + 1));
    }

    @Test
    public void throttleKeysAreIndependent() {
        OpsActivityHooks.LogThrottle throttle = new OpsActivityHooks.LogThrottle();
        assertTrue(throttle.allow("invoked:module switch", 100L));
        assertTrue(throttle.allow("invoked:open 3p", 100L));
        assertTrue(throttle.allow("open skipped:3p", 100L));
        assertFalse(throttle.allow("invoked:module switch", 101L));
        assertFalse(throttle.allow("invoked:open 3p", 101L));
        assertFalse(throttle.allow("open skipped:3p", 101L));
    }

    @Test
    public void throttleAllowsAfterClockRollback() {
        OpsActivityHooks.LogThrottle throttle = new OpsActivityHooks.LogThrottle();
        assertTrue(throttle.allow("k", 20_000L));
        assertTrue("时钟回拨应允许一次并重置窗口", throttle.allow("k", 15_000L));
        assertFalse(throttle.allow("k", 15_001L));
    }
}
