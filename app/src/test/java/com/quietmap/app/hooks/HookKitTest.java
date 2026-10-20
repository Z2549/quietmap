package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * HookKit 的纯 JVM 逻辑：框架类黑名单 + 反射定位。
 * 只测不依赖 Xposed 框架实例的静态方法；install/hit/skipVoid 需要
 * XposedModule，JVM 单测无法构造，故不在覆盖范围内。
 */
public class HookKitTest {

    /** 反射定位 fixture：三个同名重载，只有精确参数类型才能命中。 */
    static class Fixture {
        void m(int v) {
        }

        void m(Integer v) {
        }

        void m(String v) {
        }
    }

    @Test
    public void frameworkClassesRefused() {
        String[] names = {
                "android.view.View",
                "java.lang.String",
                "dalvik.system.PathClassLoader",
                "androidx.core.AppCompat",
                "kotlin.Unit"
        };
        for (String name : names) {
            assertTrue("框架类应被拒绝: " + name, HookKit.isFrameworkClass(name));
        }
        assertTrue("null 类名应被拒绝", HookKit.isFrameworkClass(null));
    }

    @Test
    public void appClassesAllowed() {
        assertFalse("宿主应用类不应被当成框架类", HookKit.isFrameworkClass("com.autonavi.minimap.X"));
        assertFalse("模块自身类不应被当成框架类", HookKit.isFrameworkClass("com.quietmap.app.Foo"));
    }

    @Test
    public void findMethodQuietMatchesExactParamTypes() {
        Method intOverload = HookKit.findMethodQuiet(Fixture.class, "m", int.class);
        assertNotNull("int 参数应命中 m(int)", intOverload);
        assertEquals(int.class, intOverload.getParameterTypes()[0]);

        Method integerOverload = HookKit.findMethodQuiet(Fixture.class, "m", Integer.class);
        assertNotNull("Integer 参数应命中 m(Integer)", integerOverload);
        assertEquals(Integer.class, integerOverload.getParameterTypes()[0]);

        Method stringOverload = HookKit.findMethodQuiet(Fixture.class, "m", String.class);
        assertNotNull("String 参数应命中 m(String)", stringOverload);
        assertEquals(String.class, stringOverload.getParameterTypes()[0]);

        assertFalse("m(int) 与 m(Integer) 必须是两个不同的方法", intOverload.equals(integerOverload));
    }

    @Test
    public void findMethodQuietReturnsNullForUnknownParam() {
        assertNull("不存在的参数类型应返回 null",
                HookKit.findMethodQuiet(Fixture.class, "m", double.class));
        assertNull("不存在的方法名应返回 null",
                HookKit.findMethodQuiet(Fixture.class, "noSuchMethod", int.class));
    }

    @Test
    public void findMethodQuietReturnsNullForNullClass() {
        assertNull("cls 为 null 时应返回 null",
                HookKit.findMethodQuiet((Class<?>) null, "m", int.class));
    }
}
