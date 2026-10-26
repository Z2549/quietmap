package com.quietmap.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 诊断文件兜底的候选路径（纯 Java，可在 JVM 单测里锁定优先级）。
 *
 * 优先级：
 *   1. Android/media/&lt;模块包名&gt;/qm_report.json      —— 模块 App 预创建并 chmod，目标进程写入
 *   2. Android/media/&lt;高德包名&gt;/qm_report.json      —— 高德自己的 media 目录，模块读侧尽力尝试
 *   3. Android/data/&lt;高德包名&gt;/files/qm_report.json —— 目标进程 getExternalFilesDir(null)，
 *      模块 App 在 Android 11+ 通常读不到，仅作为写入侧最后兜底
 */
public final class ReportPaths {

    public static final String FILE_NAME = "qm_report.json";
    public static final String TEMP_SUFFIX = ".tmp";

    /** 与 applicationId / StatusProvider.AUTHORITY 前缀保持一致。 */
    public static final String MODULE_PACKAGE = "io.github.yigenhuobah.quietmap";
    public static final String TARGET_PACKAGE = "com.autonavi.minimap";

    private ReportPaths() {
    }

    /**
     * @param externalRoot  外部存储根（通常是 /storage/emulated/0），可为 null
     * @param targetFilesDir 目标进程的 getExternalFilesDir(null)，可为 null；
     *                       为 null 时按标准布局推导 Android/data/&lt;target&gt;/files
     */
    public static List<File> candidates(File externalRoot, File targetFilesDir,
                                        String modulePackage, String targetPackage) {
        List<File> out = new ArrayList<>(3);
        File media = externalRoot != null ? new File(externalRoot, "Android/media") : null;
        if (media != null && modulePackage != null && !modulePackage.isEmpty()) {
            out.add(new File(new File(media, modulePackage), FILE_NAME));
        }
        if (media != null && targetPackage != null && !targetPackage.isEmpty()) {
            out.add(new File(new File(media, targetPackage), FILE_NAME));
        }
        if (targetFilesDir != null) {
            out.add(new File(targetFilesDir, FILE_NAME));
        } else if (externalRoot != null && targetPackage != null && !targetPackage.isEmpty()) {
            out.add(new File(new File(new File(new File(externalRoot, "Android/data"),
                    targetPackage), "files"), FILE_NAME));
        }
        return Collections.unmodifiableList(out);
    }
}
