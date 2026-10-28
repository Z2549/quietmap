package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConfigStore 的纯 JVM 决策：
 * remote 不可用必须拒绝写入、commit 失败必须上报、
 * 旧 group 独立兜底、store 复用条件。
 */
public class ConfigStoreTest {

    @Test
    public void remoteUnavailableRejectsWrite() {
        FakeStore newStore = new FakeStore();
        FakeStore legacy = new FakeStore();
        ConfigStore store = new ConfigStore(newStore, legacy, false);

        assertFalse("remote 不可用时 put 必须返回 false",
                store.put(Feature.SPLASH_GATE.key, true, null));
        assertEquals("remote 不可用时不得写新 group", 0, newStore.puts);
        assertEquals("remote 不可用时不得写旧 group", 0, legacy.puts);
    }

    @Test
    public void commitSuccessReportedToCallback() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = true;
        ConfigStore store = new ConfigStore(newStore, null, true);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, true);

        assertTrue("remote 可用时应接受写入任务", result.scheduled);
        assertTrue("callback 应收到 commit=true", result.committed.get());
        assertEquals(1, newStore.puts);
        assertTrue(newStore.lastValue);
    }

    @Test
    public void commitFailureReportedToCallback() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, null, true);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, true);

        assertTrue(result.scheduled);
        assertFalse("commit=false 不能被当成写成功", result.committed.get());
    }

    @Test
    public void commitFailureKeepsLastCommittedValue() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.values.put(Feature.SPLASH_GATE.key, true);
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, null, true);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, false);

        assertFalse(result.committed.get());
        // FakeStore 与 RemotePreferences 一样先乐观更新 map 再返回 commit 结果；
        // 读取必须用最后一次确认提交的值覆盖它。
        assertTrue("commit 失败后读取必须仍是上次已提交的值",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void commitFailureForAbsentKeyDoesNotLeakValue() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, null, true);

        writeAndWait(store, Feature.SPLASH_GATE.key, true);

        assertFalse("写前不存在的 key，commit 失败后仍应回退默认值",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void legacyGroupIsUsedWhenNewGroupMissing() {
        FakeStore legacy = new FakeStore();
        legacy.values.put(Feature.SPLASH_GATE.key, true);
        // newStore=null 模拟新 group config 绑定失败，remote=false 表示不能写。
        ConfigStore store = new ConfigStore(null, legacy, false);

        assertTrue("新 group 绑定失败时旧 group 仍要兜底",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void newGroupWinsOverLegacy() {
        FakeStore newStore = new FakeStore();
        newStore.values.put(Feature.SPLASH_GATE.key, false);
        FakeStore legacy = new FakeStore();
        legacy.values.put(Feature.SPLASH_GATE.key, true);
        ConfigStore store = new ConfigStore(newStore, legacy, true);

        assertFalse(store.get(Feature.SPLASH_GATE.key, true));
    }

    @Test
    public void readFailureFallsBackToLegacy() {
        FakeStore newStore = new FakeStore();
        newStore.throwOnGet = true;
        FakeStore legacy = new FakeStore();
        legacy.values.put(Feature.SPLASH_GATE.key, true);
        ConfigStore store = new ConfigStore(newStore, legacy, true);

        assertTrue("新 group 读取异常时旧 group 继续兜底",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void absentInBothUsesDefault() {
        ConfigStore store = new ConfigStore(new FakeStore(), new FakeStore(), true);

        assertTrue(store.get(Feature.SPLASH_GATE.key, true));
        assertFalse(store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void storeRebuildDecision() {
        assertTrue("没有 store 时必须重建", ConfigStore.needsRebuild(false, true));
        assertTrue("service 换绑时必须重建", ConfigStore.needsRebuild(true, false));
        assertFalse("同一 service 且已有 store 时复用", ConfigStore.needsRebuild(true, true));
    }

    @Test
    public void resolveOrderIsNewThenLegacyThenDefault() {
        assertFalse(ConfigStore.resolve(false, true, true));
        assertTrue(ConfigStore.resolve(null, true, false));
        assertFalse(ConfigStore.resolve(null, null, false));
        assertTrue(ConfigStore.resolve(null, null, true));
    }

    private static Result writeAndWait(ConfigStore store, String key, boolean value)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean();
        boolean scheduled = store.put(key, value, ok -> {
            committed.set(ok);
            latch.countDown();
        });
        assertTrue("写入回调未在 5 秒内返回", latch.await(5, TimeUnit.SECONDS));
        return new Result(scheduled, committed);
    }

    private static final class Result {
        final boolean scheduled;
        final AtomicBoolean committed;

        Result(boolean scheduled, AtomicBoolean committed) {
            this.scheduled = scheduled;
            this.committed = committed;
        }
    }

    private static final class FakeStore implements ConfigStore.Store {
        final Map<String, Boolean> values = new HashMap<>();
        boolean commitResult = true;
        boolean throwOnGet;
        int puts;
        boolean lastValue;

        @Override
        public Boolean get(String key) {
            if (throwOnGet) {
                throw new IllegalStateException("boom");
            }
            return values.get(key);
        }

        @Override
        public boolean put(String key, boolean value) {
            puts++;
            lastValue = value;
            // 与 RemotePreferences.Editor 一致：先乐观更新内存 map，再返回 commit 结果。
            values.put(key, value);
            return commitResult;
        }
    }
}
