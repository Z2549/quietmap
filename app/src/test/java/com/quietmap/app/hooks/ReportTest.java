package com.quietmap.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Report 的文件写入时机：首次立即写、变化节流 45 秒、未变化 5 分钟心跳。
 */
public class ReportTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void firstWriteIsAlwaysDue() {
        assertTrue(Report.fileWriteDue(true, T0, 0L));
        assertTrue(Report.fileWriteDue(false, T0, 0L));
    }

    @Test
    public void changedSnapshotIsThrottledThenWritten() {
        assertFalse(Report.fileWriteDue(true,
                T0 + Report.FILE_MIN_INTERVAL_MS - 1, T0));
        assertTrue(Report.fileWriteDue(true,
                T0 + Report.FILE_MIN_INTERVAL_MS, T0));
    }

    @Test
    public void unchangedSnapshotIsRewrittenByHeartbeat() {
        assertFalse("未变化时不应跟着 45 秒节流写盘",
                Report.fileWriteDue(false,
                        T0 + Report.FILE_HEARTBEAT_MS - 1, T0));
        assertTrue("5 分钟心跳必须重写，刷新文件 mtime",
                Report.fileWriteDue(false,
                        T0 + Report.FILE_HEARTBEAT_MS, T0));
        assertFalse(Report.fileWriteDue(false,
                T0 + Report.FILE_MIN_INTERVAL_MS, T0));
    }

    @Test
    public void clockRollbackAllowsOneWrite() {
        assertTrue(Report.fileWriteDue(false, T0 - 1, T0));
    }
}
