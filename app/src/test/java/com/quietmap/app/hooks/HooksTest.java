package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;

import com.quietmap.app.Feature;

import org.junit.Test;

import java.util.EnumSet;

/**
 * INSTALLERS 注册完整性：19 个 Feature 必须全部有安装器。
 * 漏注册在运行时只会给单个功能记 skip（诊断卡可见但容易被忽略），这里在构建期就拦住。
 */
public class HooksTest {

    @Test
    public void everyFeatureHasInstaller() {
        assertEquals(EnumSet.allOf(Feature.class), Hooks.installerKeys());
    }

    @Test
    public void installerCountMatchesFeatureCount() {
        assertEquals(19, Hooks.installerKeys().size());
    }
}
