package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MsgCenterHooks 的纯 JVM 逻辑：空会话列表回调形状、页面/缓存 key 判定、
 * AJX 页面路径反射。Hooker 本身需要 XposedModule/Chain，JVM 单测无法构造；
 * 回调形状通过真实 callback(Object[]) 接口验证。
 */
public class MsgCenterHooksTest {

    /** 与高德 JsFunctionCallback 相同的唯一方法签名（返回值类型不影响反射形状）。 */
    public interface FakeJsCallback {
        Object callback(Object[] args);
    }

    public static final class RecordingCallback implements FakeJsCallback {
        Object[] received;
        int calls;

        @Override
        public Object callback(Object[] args) {
            calls++;
            received = args;
            return null;
        }
    }

    public static final class FakeContext {
        private final String path;

        FakeContext(String path) {
            this.path = path;
        }

        public String getJsPath() {
            return path;
        }
    }

    public static final class FakeModule {
        private final FakeContext context;

        FakeModule(FakeContext context) {
            this.context = context;
        }

        public FakeContext getContext() {
            return context;
        }
    }

    /** 反射调用必须把 Object[] 作为“单个实参”传进去，而不是展开。 */
    @Test
    public void invokeSessionListCallbackPassesSingleArrayArgument() throws Exception {
        Method callback = FakeJsCallback.class
                .getDeclaredMethod("callback", Object[].class);
        RecordingCallback target = new RecordingCallback();

        MsgCenterHooks.invokeSessionListCallback(callback, target);

        assertEquals(1, target.calls);
        assertNotNull("回调必须收到数组实参", target.received);
        assertEquals(1, target.received.length);
        assertEquals(MsgCenterHooks.EMPTY_SESSION_LIST_JSON, target.received[0]);
    }

    /** 回归锁定：直接传 Object[]{"..."} 会因参数类型不匹配抛 IllegalArgumentException。 */
    @Test(expected = IllegalArgumentException.class)
    public void unboxedArrayWouldBeRejectedByReflection() throws Exception {
        Method callback = FakeJsCallback.class
                .getDeclaredMethod("callback", Object[].class);
        callback.invoke(new RecordingCallback(),
                MsgCenterHooks.emptySessionListCallbackArgs());
    }

    @Test
    public void emptySessionListCallbackUsesConvListJson() {
        Object[] args = MsgCenterHooks.emptySessionListCallbackArgs();
        assertEquals(1, args.length);
        assertEquals("{\"convList\":[]}", args[0]);
        assertEquals(MsgCenterHooks.EMPTY_SESSION_LIST_JSON, args[0]);
    }

    @Test
    public void jsonConstantsMatchAjxContract() {
        assertEquals("{\"convList\":[]}", MsgCenterHooks.EMPTY_SESSION_LIST_JSON);
        assertEquals("{\"value\":0}", MsgCenterHooks.ZERO_BADGE_JSON);
        assertEquals("[]", MsgCenterHooks.EMPTY_RECENT_LIST_JSON);
    }

    @Test
    public void messageCenterPageMatched() {
        assertTrue(MsgCenterHooks.isMessageCenterPage(
                "path://amap_bundle_messagetab/src/pages/message_tab/MessageTab.page.js"));
        assertTrue(MsgCenterHooks.isMessageCenterPage(
                "path://amap_bundle_mine/src/pages/MinePage.page.js"));
        assertTrue("大小写不敏感",
                MsgCenterHooks.isMessageCenterPage("PATH://AMAP_BUNDLE_MESSAGETAB/X"));
    }

    @Test
    public void nonMessageCenterPageNotMatched() {
        assertFalse(MsgCenterHooks.isMessageCenterPage(
                "path://amap_bundle_amaphome/src/pages/MapHomePage.page.js"));
        assertFalse(MsgCenterHooks.isMessageCenterPage(
                "path://amap_bundle_search/src/pages/SearchPage.page.js"));
        assertFalse(MsgCenterHooks.isMessageCenterPage(null));
        assertFalse(MsgCenterHooks.isMessageCenterPage(""));
    }

    @Test
    public void recentListKeyMatched() {
        assertTrue(MsgCenterHooks.isRecentListKey(
                "message_tab", "recentlyMsgList-937300487"));
        assertTrue(MsgCenterHooks.isRecentListKey(
                MsgCenterHooks.MESSAGE_TAB_NAMESPACE,
                MsgCenterHooks.RECENT_LIST_KEY_PREFIX + "1"));
    }

    @Test
    public void recentListKeyNotMatched() {
        assertFalse("namespace 必须完全一致",
                MsgCenterHooks.isRecentListKey("other", "recentlyMsgList-1"));
        assertFalse("key 必须以固定前缀开头",
                MsgCenterHooks.isRecentListKey("message_tab", "recentlyMsgList"));
        assertFalse(MsgCenterHooks.isRecentListKey("message_tab", null));
        assertFalse(MsgCenterHooks.isRecentListKey(null, "recentlyMsgList-1"));
        assertFalse(MsgCenterHooks.isRecentListKey("message_tab", 1));
    }

    @Test
    public void recentListGateOnlyInterceptsMessageCenter() {
        String message = "path://amap_bundle_messagetab/src/pages/message_tab/MessageTab.page.js";
        String mine = "path://amap_bundle_mine/src/pages/MinePage.page.js";
        String home = "path://amap_bundle_amaphome/src/pages/MapHomePage.page.js";

        assertTrue(MsgCenterHooks.shouldInterceptRecentList(
                message, "message_tab", "recentlyMsgList-1"));
        assertTrue(MsgCenterHooks.shouldInterceptRecentList(
                mine, "message_tab", "recentlyMsgList-1"));
        assertFalse("其他 AJX 页面必须放行", MsgCenterHooks.shouldInterceptRecentList(
                home, "message_tab", "recentlyMsgList-1"));
        assertFalse("key 不匹配时不拦截", MsgCenterHooks.shouldInterceptRecentList(
                message, "other", "recentlyMsgList-1"));
        assertTrue("路径取不到时保持旧的 key-only 行为",
                MsgCenterHooks.shouldInterceptRecentList(
                        null, "message_tab", "recentlyMsgList-1"));
    }

    @Test
    public void jsPathReadsContextReflectively() {
        assertEquals("path://amap_bundle_messagetab/x",
                MsgCenterHooks.jsPath(new FakeModule(
                        new FakeContext("path://amap_bundle_messagetab/x"))));
    }

    @Test
    public void jsPathFailsOpen() {
        assertNull(MsgCenterHooks.jsPath(null));
        assertNull(MsgCenterHooks.jsPath(new FakeModule(null)));
        assertNull(MsgCenterHooks.jsPath(new Object()));
    }

    @Test
    public void jsPathFailureDecisionIsOnlyReportedOnce() {
        AtomicBoolean warned = new AtomicBoolean();
        assertTrue("首次失败需要上报",
                MsgCenterHooks.shouldReportJsPathFailure(warned));
        assertFalse("后续调用不得重复上报",
                MsgCenterHooks.shouldReportJsPathFailure(warned));
        assertFalse(MsgCenterHooks.shouldReportJsPathFailure(null));
    }

    @Test
    public void jsPathFailureMarksNotFoundOnlyOnce() {
        Feature f = Feature.MSG_CENTER;
        AtomicBoolean warned = new AtomicBoolean();

        assertTrue(MsgCenterHooks.reportJsPathFailureOnce(f, warned));
        assertEquals("jsPath 获取失败", HookState.skipReason(f));

        // 模拟后续诊断原因：第二次 jsPath 失败不能再覆盖它。
        HookState.markNotFound(f, "sentinel");
        assertFalse("同一次进程内只上报一次",
                MsgCenterHooks.reportJsPathFailureOnce(f, warned));
        assertEquals("诊断状态不能被每次调用重复覆盖",
                "sentinel", HookState.skipReason(f));
    }
}
