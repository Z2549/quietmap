package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Feature key ↔ 编译期默认值映射。
 * key 是 remote prefs 两读一写共用的键，改了会连带断掉已保存的开关状态，必须锁死。
 */
public class FeatureTest {

    private static final Set<String> EXPECTED_KEYS = new HashSet<>(Arrays.asList(
            "splash_gate",
            "splash_request",
            "splash_shake",
            "bg_msg_fetch",
            "bg_awaken_report",
            "bg_afc_taoke",
            "bg_utdid_upload",
            "h5_install_apk",
            "popup_upgrade",
            "home_banner",
            "home_operate_bar",
            "home_msg_filter",
            "search_hotword",
            "h5_ad_log",
            "map_float_layer",
            "msg_image_text"
    ));

    @Test
    public void keysMatchSpec() {
        Set<String> actual = new HashSet<>();
        for (Feature f : Feature.values()) {
            actual.add(f.key);
        }
        assertEquals(EXPECTED_KEYS, actual);
    }

    @Test
    public void allDefaultsEnabled() {
        for (Feature f : Feature.values()) {
            assertTrue(f.key + " 应默认开启", f.defaultEnabled);
        }
    }

    @Test
    public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("key 重复: " + f.key, seen.add(f.key));
        }
        assertEquals(EXPECTED_KEYS.size(), seen.size());
    }
}
