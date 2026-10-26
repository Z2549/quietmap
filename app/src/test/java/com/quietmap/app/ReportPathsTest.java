package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * 文件兜底路径优先级：a 模块 media → b 高德 media → c 高德 external files。
 */
public class ReportPathsTest {

    private static final String MODULE = "io.github.yigenhuobah.quietmap";
    private static final String TARGET = "com.autonavi.minimap";

    @Test
    public void candidatesFollowPriorityOrder() {
        File root = new File("root");
        List<File> files = ReportPaths.candidates(root, null, MODULE, TARGET);
        assertEquals(3, files.size());
        assertEquals(new File(new File(new File(root, "Android/media"), MODULE),
                ReportPaths.FILE_NAME), files.get(0));
        assertEquals(new File(new File(new File(root, "Android/media"), TARGET),
                ReportPaths.FILE_NAME), files.get(1));
        assertEquals(new File(new File(new File(new File(root, "Android/data"), TARGET),
                "files"), ReportPaths.FILE_NAME), files.get(2));
    }

    @Test
    public void explicitTargetFilesDirWinsOverDerivedPath() {
        File root = new File("root");
        File targetFiles = new File("sdcard/Android/data/com.autonavi.minimap/files");
        List<File> files = ReportPaths.candidates(root, targetFiles, MODULE, TARGET);
        assertEquals(3, files.size());
        assertEquals(new File(targetFiles, ReportPaths.FILE_NAME), files.get(2));
    }

    @Test
    public void withoutExternalRootOnlyTargetFilesDirRemains() {
        File targetFiles = new File("target-files");
        List<File> files = ReportPaths.candidates(null, targetFiles, MODULE, TARGET);
        assertEquals(1, files.size());
        assertEquals(new File(targetFiles, ReportPaths.FILE_NAME), files.get(0));
    }

    @Test
    public void missingPackageNamesAreSkipped() {
        File root = new File("root");
        assertTrue(ReportPaths.candidates(root, null, null, TARGET).size() == 2);
        // 空 target 包名时 b/c 都跳过，只剩模块 media 一条。
        assertTrue(ReportPaths.candidates(root, null, MODULE, "").size() == 1);
        assertTrue(ReportPaths.candidates(null, null, MODULE, TARGET).isEmpty());
    }

    @Test
    public void writeCandidatesNeverIncludeModuleMediaDir() {
        File root = new File("root");
        List<File> files = ReportPaths.writeCandidates(root, null, TARGET);
        assertEquals(2, files.size());
        assertEquals(new File(new File(new File(root, "Android/media"), TARGET),
                ReportPaths.FILE_NAME), files.get(0));
        assertEquals(new File(new File(new File(new File(root, "Android/data"), TARGET),
                "files"), ReportPaths.FILE_NAME), files.get(1));
        for (File file : files) {
            assertTrue("目标进程不能写模块 media 目录: " + file,
                    !file.getPath().contains(MODULE));
        }
    }

    @Test
    public void writeCandidatesUseExplicitTargetFilesDirAndSkipMissingRoot() {
        File targetFiles = new File("target-files");
        List<File> files = ReportPaths.writeCandidates(null, targetFiles, TARGET);
        assertEquals(1, files.size());
        assertEquals(new File(targetFiles, ReportPaths.FILE_NAME), files.get(0));
        assertTrue(ReportPaths.writeCandidates(null, null, TARGET).isEmpty());
    }
}
