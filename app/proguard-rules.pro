# The framework instantiates the entry class reflectively and invokes the
# lifecycle callbacks by name, so neither may be renamed or removed.
-keep public class com.quietmap.app.QuietMapModule { public <init>(); }

-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
    public boolean onHotReloading(...);
    public void onHotReloaded(...);
}

# compileOnly artifacts are absent at runtime; R8 must not complain about them.
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# 自定义 Preference 由 res/xml/prefs.xml 按类名反射实例化，R8 看不到这层引用。
-keep class com.quietmap.app.MaterialSwitchPreference {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# 系统按 manifest 里的类名实例化 ContentProvider，R8 需要保留入口。
-keep class com.quietmap.app.ReportProvider { public <init>(); }

# DexKit 2.x（Lze5 混淆类字符串锚定用）：JNI 回调与 flatbuffers 会被 R8 裁剪/改名。
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn org.luckypray.dexkit.**
