package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

/**
 * MsgCenterHooks 的纯 JVM 逻辑：空回调参数、hasNewMessage 的 "0" 参数、
 * 反射调用形状、观测日志限流。Hooker 本身需要 XposedModule/Chain，
 * JVM 单测无法构造；回调形状通过真实 callback(Object[]) 接口验证。
 */
public class MsgCenterHooksTest {

    /** 与高德 JsFunctionCallback 相同的唯一方法签名。 */
    interface FakeJsCallback {
        void callback(Object[] args);
    }

    static final class RecordingCallback implements FakeJsCallback {
        Object[] received;
        int calls;

        @Override
        public void callback(Object[] args) {
            calls++;
            received = args;
        }
    }

    /** 反射调用必须把 Object[] 作为“单个实参”传进去，而不是展开。 */
    @Test
    public void invokeNoNewMessagePassesSingleArrayArgument() throws Exception {
        Method callback = FakeJsCallback.class
                .getDeclaredMethod("callback", Object[].class);
        RecordingCallback target = new RecordingCallback();

        MsgCenterHooks.invokeNoNewMessage(callback, target);

        assertEquals(1, target.calls);
        assertNotNull("回调必须收到数组实参", target.received);
        assertEquals(1, target.received.length);
        assertEquals("0", target.received[0]);
    }

    /** 回归锁定：直接传 Object[]{"0"} 会因参数类型不匹配抛 IllegalArgumentException。 */
    @Test(expected = IllegalArgumentException.class)
    public void unboxedArrayWouldBeRejectedByReflection() throws Exception {
        Method callback = FakeJsCallback.class
                .getDeclaredMethod("callback", Object[].class);
        callback.invoke(new RecordingCallback(),
                MsgCenterHooks.noNewMessageCallbackArgs());
    }

    @Test
    public void emptyOnlineDataArgsAreEmptyListsFalseAndZero() {
        Object[] args = MsgCenterHooks.emptyOnlineDataArgs();
        assertEquals(4, args.length);

        assertTrue("第 1 参应是 List", args[0] instanceof List);
        assertTrue("第 2 参应是 List", args[1] instanceof List);
        assertTrue("第 1 参列表应为空", ((List<?>) args[0]).isEmpty());
        assertTrue("第 2 参列表应为空", ((List<?>) args[1]).isEmpty());
        assertNotSame("两个列表必须是独立实例", args[0], args[1]);
        assertEquals(Boolean.FALSE, args[2]);
        assertEquals(Integer.valueOf(0), args[3]);
    }

    @Test
    public void emptyOnlineDataArgsAreFreshOnEveryCall() {
        Object[] first = MsgCenterHooks.emptyOnlineDataArgs();
        Object[] second = MsgCenterHooks.emptyOnlineDataArgs();
        assertNotSame("每次回调都应新建参数数组", first, second);
        assertNotSame("每次回调都应新建消息列表", first[0], second[0]);
        assertNotSame("每次回调都应新建分类列表", first[1], second[1]);
    }

    @Test
    public void noNewMessageCallbackUsesStringZero() {
        Object[] args = MsgCenterHooks.noNewMessageCallbackArgs();
        assertEquals(1, args.length);
        assertEquals("ModuleMessageBox$c 实测回调字符串 \"0\"", "0", args[0]);
    }

    @Test
    public void throttleAllowsOncePerWindow() {
        MsgCenterHooks.LogThrottle throttle = new MsgCenterHooks.LogThrottle();
        assertTrue(throttle.allow("k", 0L));
        assertFalse(throttle.allow("k", 1L));
        assertFalse(throttle.allow("k", MsgCenterHooks.LOG_WINDOW_MS - 1));
        assertTrue(throttle.allow("k", MsgCenterHooks.LOG_WINDOW_MS));
        assertFalse(throttle.allow("k", MsgCenterHooks.LOG_WINDOW_MS + 1));
    }

    @Test
    public void throttleKeysAreIndependent() {
        MsgCenterHooks.LogThrottle throttle = new MsgCenterHooks.LogThrottle();
        assertTrue(throttle.allow("a", 100L));
        assertTrue(throttle.allow("b", 100L));
        assertFalse(throttle.allow("a", 101L));
        assertFalse(throttle.allow("b", 101L));
    }

    @Test
    public void throttleAllowsAfterClockRollback() {
        MsgCenterHooks.LogThrottle throttle = new MsgCenterHooks.LogThrottle();
        assertTrue(throttle.allow("k", 20_000L));
        assertTrue("时钟回拨应允许一次并重置窗口", throttle.allow("k", 15_000L));
        assertFalse(throttle.allow("k", 15_001L));
    }
}
