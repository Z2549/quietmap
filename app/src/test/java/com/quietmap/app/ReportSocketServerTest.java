package com.quietmap.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * socket 回传的 peer 包白名单：只允许高德目标进程与模块自身。
 * UID/凭据读取是 Android 胶水，这里覆盖纯包名判断。
 */
public class ReportSocketServerTest {

    private static final String MODULE = "io.github.yigenhuobah.quietmap";

    @Test
    public void allowsTargetAndModulePackages() {
        assertTrue(ReportSocketServer.isAllowedPackage(
                new String[]{StatusProvider.TARGET_PACKAGE}, MODULE));
        assertTrue(ReportSocketServer.isAllowedPackage(
                new String[]{MODULE}, MODULE));
    }

    @Test
    public void rejectsOtherPackagesAndNull() {
        assertFalse(ReportSocketServer.isAllowedPackage(
                new String[]{"com.example.evil"}, MODULE));
        assertFalse(ReportSocketServer.isAllowedPackage(null, MODULE));
        assertFalse(ReportSocketServer.isAllowedPackage(new String[0], MODULE));
    }
}
