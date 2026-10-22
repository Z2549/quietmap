package com.quietmap.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 十九钩子开关矩阵：每个开关独立生效，互不干扰。
 * 行=override 的键（关掉一个），列=各 Feature 的 enabled 结果。
 * 批 2 三个开屏键、批 3 三个首页键、批 4 四个搜索/推广键、批 5 三个
 * 弹窗/统计/摇一摇键同样逐项覆盖。
 */
public class HookSwitchMatrixTest {

    /** 批 2（开屏一条龙）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void splashKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.SPLASH_GATE, Feature.SPLASH_REQUEST,
                Feature.SPLASH_SHAKE}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 3（首页营销三件套）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void homeKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.HOME_BANNER, Feature.HOME_OPERATE_BAR,
                Feature.HOME_MSG_FILTER}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 4（搜索热词 + H5 监测 + 地图浮层 + 图文素材）四个新键必须落在矩阵里。 */
    @Test
    public void searchPromoKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.SEARCH_HOTWORD, Feature.H5_AD_LOG,
                Feature.MAP_FLOAT_LAYER, Feature.MSG_IMAGE_TEXT}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 5（弹窗/统计/摇一摇）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void finalBatchKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.POPUP_CLEAN, Feature.TRACK_SUPPRESS,
                Feature.SHAKE_H5}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    @Test
    public void emptyOverridesUseDefaults() {
        Config c = Config.fromOverrides(new HashMap<String, Boolean>());
        for (Feature f : Feature.values()) {
            assertEquals(f.id + " 应使用编译期默认值", f.defaultEnabled, c.enabled(f));
        }
    }

    @Test
    public void eachSwitchIsIndependent() {
        for (Feature off : Feature.values()) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
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
            assertEquals(f.id + " 应保持默认值", f.defaultEnabled, c.enabled(f));
        }
    }

    @Test
    public void nullOverridesFallBackToDefaults() {
        Config c = Config.fromOverrides(null);
        for (Feature f : Feature.values()) {
            assertEquals(f.id + " 应回退默认值", f.defaultEnabled, c.enabled(f));
        }
    }
}
