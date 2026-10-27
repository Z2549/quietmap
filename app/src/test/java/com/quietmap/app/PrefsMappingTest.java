package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * prefs.xml ↔ Feature 枚举映射：每个 Feature 的 key 都必须有对应的
 * android:key 属性，否则设置页会漏开关（配置项永远停在默认值）。
 * XML 里额外的非 Feature 键（status/diag/footer 等展示卡片）是允许的。
 */
public class PrefsMappingTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** AGP 单测工作目录是模块目录 app/；第二个候选兼容从仓库根执行的情况。 */
    private static final File[] CANDIDATES = {
            new File("src/main/res/xml/prefs.xml"),
            new File("app/src/main/res/xml/prefs.xml")
    };

    private File prefsFile;
    private Set<String> xmlKeys;

    @Before
    public void parsePrefsXml() throws Exception {
        prefsFile = resolvePrefsFile();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(prefsFile);

        xmlKeys = new LinkedHashSet<>();
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            String key = ((Element) nodes.item(i)).getAttributeNS(ANDROID_NS, "key");
            if (!key.isEmpty()) {
                xmlKeys.add(key);
            }
        }
    }

    @Test
    public void everyFeatureKeyPresentInXml() {
        for (Feature f : Feature.values()) {
            assertTrue("Feature key 未在 prefs.xml 中配置: " + f.key
                            + "（实际解析路径: " + prefsFile.getAbsolutePath() + "）",
                    xmlKeys.contains(f.key));
        }
    }

    @Test
    public void allTwentyTwoFeaturesPresent() {
        assertEquals("Feature 数量必须是 22（实际解析路径: "
                        + prefsFile.getAbsolutePath() + "）",
                22, Feature.values().length);
        assertEquals("22 个 Feature key 必须全部出现在 prefs.xml 中（实际解析路径: "
                        + prefsFile.getAbsolutePath() + "）",
                22, featureKeysInXml().size());
    }

    @Test
    public void nonFeatureKeysAreAllowed() {
        Set<String> featureKeys = featureKeysInXml();
        Set<String> extras = new LinkedHashSet<>(xmlKeys);
        extras.removeAll(featureKeys);
        assertFalse("prefs.xml 应包含 status/diag/footer 等非 Feature 展示键（实际解析路径: "
                        + prefsFile.getAbsolutePath() + "）",
                extras.isEmpty());
        assertTrue("status 卡片不应被误判为 Feature（实际解析路径: "
                + prefsFile.getAbsolutePath() + "）", extras.contains("status"));
        assertTrue("footer 卡片不应被误判为 Feature（实际解析路径: "
                + prefsFile.getAbsolutePath() + "）", extras.contains("footer"));
    }

    /** 实际存在于 XML 中的 Feature key（交集）。 */
    private Set<String> featureKeysInXml() {
        Set<String> present = new LinkedHashSet<>();
        Set<String> declared = featureKeySet();
        for (String key : xmlKeys) {
            if (declared.contains(key)) {
                present.add(key);
            }
        }
        return present;
    }

    private static Set<String> featureKeySet() {
        Set<String> keys = new LinkedHashSet<>();
        for (Feature f : Feature.values()) {
            keys.add(f.key);
        }
        return keys;
    }

    private static File resolvePrefsFile() {
        File cwd = new File("").getAbsoluteFile();
        StringBuilder tried = new StringBuilder();
        for (File candidate : CANDIDATES) {
            File abs = candidate.getAbsoluteFile();
            if (candidate.isFile()) {
                return abs;
            }
            tried.append("\n  - ").append(abs);
        }
        fail("找不到 prefs.xml（工作目录: " + cwd + "），已尝试:" + tried);
        return null;
    }
}
