package com.quietmap.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 六钩子开关矩阵：每个开关独立生效，互不干扰。
 * 行=override 的键（关掉一个），列=各 Feature 的 enabled 结果。
 */
public class HookSwitchMatrixTest {

    @Test
    public void emptyOverridesAllEnabled() {
        Config c = Config.fromOverrides(new HashMap<String, Boolean>());
        for (Feature f : Feature.values()) {
            assertEquals(f.key + " 默认应开启", true, c.enabled(f));
        }
    }

    @Test
    public void eachSwitchIsIndependent() {
        for (Feature off : Feature.values()) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    @Test
    public void allOffMatrix() {
        Map<String, Boolean> overrides = new HashMap<>();
        for (Feature f : Feature.values()) {
            overrides.put(f.key, false);
        }
        Config c = Config.fromOverrides(overrides);
        for (Feature f : Feature.values()) {
            assertEquals(f.key + " 应被关掉", false, c.enabled(f));
        }
        assertEquals(false, c.anyEnabled());
    }

    @Test
    public void unknownKeysIgnored() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("not_a_feature", false);
        Config c = Config.fromOverrides(overrides);
        for (Feature f : Feature.values()) {
            assertEquals(true, c.enabled(f));
        }
    }

    @Test
    public void nullOverridesFallBackToDefaults() {
        Config c = Config.fromOverrides(null);
        for (Feature f : Feature.values()) {
            assertEquals(true, c.enabled(f));
        }
    }
}
