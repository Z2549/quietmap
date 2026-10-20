package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * HookState 的纯 JVM 行为：命中计数、标签排序、skip 原因保留。
 * 诊断卡与 Report 回传的快照都从这里取值，语义变了会直接反映到用户界面。
 *
 * 每个用例使用不同的 Feature：HookState 是进程级静态状态，测试方法之间不能互相污染。
 */
public class HookStateTest {

    @Test
    public void hitIncrementsCount() {
        Feature f = Feature.SPLASH_GATE;
        assertEquals(1L, HookState.hit(f));
        assertEquals(2L, HookState.hit(f));
        assertEquals(2L, HookState.hitCount(f));
    }

    @Test
    public void labelsAreSorted() {
        Feature f = Feature.SEARCH_HOTWORD;
        HookState.installed(f, "beta");
        HookState.installed(f, "alpha");
        assertEquals(Arrays.asList("alpha", "beta"), HookState.labels(f));
    }

    @Test
    public void markNotFoundKeepsLastReason() {
        Feature f = Feature.POPUP_CLEAN;
        HookState.markNotFound(f, "first");
        HookState.markNotFound(f, "second");
        assertEquals("second", HookState.skipReason(f));
    }

    @Test
    public void untouchedFeatureHasEmptyState() {
        Feature f = Feature.H5_INSTALL_APK;
        assertEquals(0L, HookState.hitCount(f));
        assertTrue("未触碰的 Feature 不应有标签", HookState.labels(f).isEmpty());
        assertNull("未触碰的 Feature 不应有 skip 原因", HookState.skipReason(f));
    }
}
