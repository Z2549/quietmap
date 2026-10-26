package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.ReportFileStore;
import com.quietmap.app.ReportPaths;
import com.quietmap.app.ReportSnapshot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 文件兜底写入的纯 JVM 规则：session 合并/替换、写入后读回校验、
 * 失败路径继续尝试下一条、异常路径清理 .tmp。
 */
public class ReportFileSinkTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writesSessionSnapshotAndVerifiesReadable() throws Exception {
        File target = new File(folder.getRoot(), ReportPaths.FILE_NAME);
        long now = System.currentTimeMillis();
        Map<String, String> data = Collections.singletonMap(
                "reg.splash_gate", "canShowSplash");

        ReportFileSink.writeOne(target, data, now, "session-1", "t1");

        assertTrue(ReportFileSink.verifyReadable(target));
        ReportFileStore.Entry entry = ReportFileStore.readForMerge(target);
        assertNotNull(entry);
        assertEquals("session-1", entry.snapshot.session());
        assertEquals("canShowSplash",
                entry.snapshot.data().get("reg.splash_gate"));
    }

    @Test
    public void sameSessionMergesAndDifferentSessionReplaces() throws Exception {
        File target = new File(folder.getRoot(), ReportPaths.FILE_NAME);
        long now = System.currentTimeMillis();
        Map<String, String> first = new TreeMap<>();
        first.put("hit.splash_gate", "5");
        first.put("skip.splash_gate", "old-skip");
        ReportFileSink.writeOne(target, first, now, "session-1", "t1");

        Map<String, String> same = new TreeMap<>();
        same.put("hit.splash_gate", "3");
        same.put("reg.splash_gate", "new-reg");
        ReportFileSink.writeOne(target, same, now, "session-1", "t2");
        ReportFileStore.Entry merged = ReportFileStore.readForMerge(target);
        assertNotNull(merged);
        assertEquals("同一 session hit 取最大", "5",
                merged.snapshot.data().get("hit.splash_gate"));
        assertEquals("new-reg", merged.snapshot.data().get("reg.splash_gate"));
        assertEquals("old-skip", merged.snapshot.data().get("skip.splash_gate"));

        Map<String, String> nextSession = Collections.singletonMap(
                "reg.bg_msg_fetch", "fresh");
        ReportFileSink.writeOne(target, nextSession, now, "session-2", "t3");
        ReportFileStore.Entry replaced = ReportFileStore.readForMerge(target);
        assertNotNull(replaced);
        assertEquals("session-2", replaced.snapshot.session());
        assertNull("旧 session 的 skip 不能被保留",
                replaced.snapshot.data().get("skip.splash_gate"));
        assertNull("旧 session 的 hit 不能被保留",
                replaced.snapshot.data().get("hit.splash_gate"));
        assertEquals("fresh", replaced.snapshot.data().get("reg.bg_msg_fetch"));
    }

    @Test
    public void verifyReadableRejectsMissingDirectoryAndEmptyFile() throws Exception {
        assertFalse(ReportFileSink.verifyReadable(null));
        assertFalse(ReportFileSink.verifyReadable(
                new File(folder.getRoot(), "missing.json")));
        assertFalse(ReportFileSink.verifyReadable(folder.newFolder("directory")));

        File empty = folder.newFile("empty.json");
        assertFalse("空文件读不出内容，不能当成写入成功",
                ReportFileSink.verifyReadable(empty));
    }

    @Test
    public void writeCandidatesContinuesAfterUnreadablePath() throws Exception {
        File bad = folder.newFolder("not-a-file");
        File good = new File(folder.getRoot(), ReportPaths.FILE_NAME);
        long now = System.currentTimeMillis();
        Map<String, String> data = Collections.singletonMap("reg.splash_gate", "x");

        assertTrue(ReportFileSink.writeCandidates(
                Arrays.asList(bad, good), data, now, "s1", "t1"));
        assertTrue(ReportFileSink.verifyReadable(good));
        assertNotNull(ReportFileStore.readForMerge(good));
    }

    @Test
    public void allPathsFailReturnsFalseAndCleansTempFiles() throws Exception {
        File bad = folder.newFolder("bad-dir");
        File bad2 = folder.newFolder("bad-dir-2");
        Map<String, String> data = Collections.singletonMap("reg.splash_gate", "x");

        assertFalse(ReportFileSink.writeCandidates(
                Arrays.asList(bad, bad2), data, System.currentTimeMillis(),
                "s1", "cleanup"));

        for (File file : folder.getRoot().listFiles()) {
            assertFalse("异常路径必须清理 .tmp: " + file.getName(),
                    file.getName().contains(ReportPaths.TEMP_SUFFIX));
        }
    }

    @Test
    public void sameSessionRequiresNonEmptyEqualValues() {
        assertTrue(ReportFileSink.sameSession("s1", "s1"));
        assertFalse(ReportFileSink.sameSession("s1", "s2"));
        assertFalse(ReportFileSink.sameSession(null, null));
        assertFalse(ReportFileSink.sameSession("", ""));
    }

    @Test
    public void writeOneSerializesStableJson() throws Exception {
        File target = new File(folder.getRoot(), ReportPaths.FILE_NAME);
        long now = System.currentTimeMillis();
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "a");
        data.put("hit.splash_gate", "1");
        ReportFileSink.writeOne(target, data, now, "s1", "t1");

        String text = new String(java.nio.file.Files.readAllBytes(target.toPath()),
                StandardCharsets.UTF_8);
        assertEquals(ReportSnapshot.toJson(now, "s1", data), text);
    }

    @Test
    public void tempFileGetsUniqueNamePerWriter() throws Exception {
        File target = new File(folder.getRoot(), ReportPaths.FILE_NAME);
        FileOutputStream out = new FileOutputStream(
                new File(folder.getRoot(), ReportPaths.FILE_NAME + ".other"
                        + ReportPaths.TEMP_SUFFIX));
        out.write("keep".getBytes(StandardCharsets.UTF_8));
        out.close();
        ReportFileSink.writeOne(target,
                Collections.singletonMap("reg.splash_gate", "x"),
                System.currentTimeMillis(), "s1", "writer");
        assertTrue("其他写入者的 .tmp 不应被误删",
                new File(folder.getRoot(), ReportPaths.FILE_NAME + ".other"
                        + ReportPaths.TEMP_SUFFIX).exists());
    }
}
