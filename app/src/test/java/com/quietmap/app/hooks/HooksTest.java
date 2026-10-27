package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.Feature;

import org.junit.Test;

/**
 * INSTALLERS 注册完整性：非 blocked Feature 必须全部有安装器，
 * blocked Feature 不得注册、也不得被 installAll 安装。
 * 漏注册在运行时只会给单个功能记 skip（诊断卡可见但容易被忽略），这里在构建期就拦住。
 */
public class HooksTest {

    @Test
    public void everyNonBlockedFeatureHasInstaller() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("缺少 installer: " + f.id + " (" + f.key + ")",
                    Hooks.installerKeys().contains(f));
        }
    }

    @Test
    public void blockedFeaturesAreNotRegisteredOrInstallable() {
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                continue;
            }
            assertFalse("blocked Feature 不应注册 installer: " + f.id,
                    Hooks.installerKeys().contains(f));
            // blocked 与用户配置无关：即使开关为 true 也不得安装。
            assertFalse("blocked Feature 不应被安装: " + f.id,
                    Hooks.shouldInstall(f, true));
            assertFalse("blocked Feature 不应被安装: " + f.id,
                    Hooks.shouldInstall(f, false));
        }
        // 当前没有 blocked 项；未来新增 blocked 项时同样自动校验这三条规则。
    }

    @Test
    public void shouldInstallHonorsEnabledForNonBlockedFeatures() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("开启的非 blocked Feature 应安装: " + f.id,
                    Hooks.shouldInstall(f, true));
            assertFalse("关闭的 Feature 不应安装: " + f.id,
                    Hooks.shouldInstall(f, false));
        }
    }

    @Test
    public void installerCountMatchesNonBlockedFeatureCount() {
        int expected = 0;
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                expected++;
            }
        }
        assertEquals(expected, Hooks.installerKeys().size());
        assertEquals(expected, Feature.values().length - countBlocked());
    }

    private static int countBlocked() {
        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                blocked++;
            }
        }
        return blocked;
    }
}
