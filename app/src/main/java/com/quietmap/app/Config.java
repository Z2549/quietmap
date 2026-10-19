package com.quietmap.app;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.EnumMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 开关读取：有 PROP_CAP_REMOTE 才读 getRemotePreferences("config")，
 * 否则（含读取抛异常）回退到 Feature 的编译期内置默认值。
 * 必须在 onModuleLoaded 之后调用，之前任何 API 都会抛 IllegalStateException。
 */
public final class Config {

    public static final String GROUP = "config";

    private static final String TAG = "QuietMap";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    /**
     * 纯 JVM 可测入口：overrides 里缺的键用 Feature 编译期默认值。
     * 单测的「十二钩子开关矩阵」走这里，不碰框架 API。
     */
    static Config fromOverrides(Map<String, Boolean> overrides) {
        Config config = new Config();
        if (overrides != null) {
            for (Feature f : Feature.values()) {
                Boolean v = overrides.get(f.key);
                if (v != null) {
                    config.mEnabled.put(f, v);
                }
            }
        }
        return config;
    }

    public static Config load(XposedModule module) {
        Config config = new Config();
        boolean remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return config;
        }
        try {
            SharedPreferences sp = module.getRemotePreferences(GROUP);
            for (Feature f : Feature.values()) {
                config.mEnabled.put(f, sp.getBoolean(f.key, f.defaultEnabled));
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
        }
        return config;
    }

    public boolean enabled(Feature f) {
        return Boolean.TRUE.equals(mEnabled.get(f));
    }

    public boolean anyEnabled() {
        for (Boolean v : mEnabled.values()) {
            if (Boolean.TRUE.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
