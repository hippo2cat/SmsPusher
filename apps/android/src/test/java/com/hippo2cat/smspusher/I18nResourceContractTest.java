package com.hippo2cat.smspusher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class I18nResourceContractTest {
    private static final Path APP_ROOT = Paths.get("").toAbsolutePath().normalize();
    private static final Pattern CHINESE_STRING_LITERAL = Pattern.compile("\"[^\"]*[\\p{IsHan}][^\"]*\"");

    @Test
    public void androidGeneratedStringResourcesHaveMatchingKeys() throws Exception {
        Set<String> en = stringNames(APP_ROOT.resolve("src/main/res/values/strings.xml"));
        Set<String> zh = stringNames(APP_ROOT.resolve("src/main/res/values-zh-rCN/strings.xml"));

        assertTrue(en.contains("app_name"));
        assertTrue(en.contains("common_language_title"));
        assertEquals(en, zh);
    }

    @Test
    public void androidGeneratedStringResourcesKeepLocaleSpecificReviewCopy() throws Exception {
        String en = new String(Files.readAllBytes(APP_ROOT.resolve("src/main/res/values/strings.xml")), StandardCharsets.UTF_8);
        String zh = new String(Files.readAllBytes(APP_ROOT.resolve("src/main/res/values-zh-rCN/strings.xml")), StandardCharsets.UTF_8);

        assertEquals(", ", stringValue(en, "android_permission_list_separator"));
        assertEquals("、", stringValue(zh, "android_permission_list_separator"));
        assertEquals("正在与 {deviceName} 配对", stringValue(zh, "android_pairing_with_device"));
        assertEquals("已配对 {baseUrl}", stringValue(zh, "android_status_paired_with"));
        assertEquals("正在查找局域网中的 Mac", stringValue(zh, "android_status_discovering"));
    }

    @Test
    public void productionAndroidUiCodeDoesNotIntroduceHardcodedChineseStrings() throws Exception {
        try (Stream<Path> stream = Files.walk(APP_ROOT.resolve("src/main/java/com/hippo2cat/smspusher"))) {
            List<Path> javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .collect(Collectors.toList());
            for (Path path : javaFiles) {
                String relative = APP_ROOT.relativize(path).toString();
                if (relative.contains("/miui/")) continue;
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertFalse("Move Chinese UI copy to generated strings.xml: " + relative, CHINESE_STRING_LITERAL.matcher(source).find());
            }
        }
    }

    private static Set<String> stringNames(Path path) throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int start = line.indexOf("name=\"");
            if (start < 0) continue;
            int valueStart = start + "name=\"".length();
            int valueEnd = line.indexOf('"', valueStart);
            names.add(line.substring(valueStart, valueEnd));
        }
        return names;
    }

    private static String stringValue(String xml, String name) {
        String prefix = "<string name=\"" + name + "\">";
        int start = xml.indexOf(prefix);
        assertTrue(name + " should exist", start >= 0);
        int valueStart = start + prefix.length();
        int valueEnd = xml.indexOf("</string>", valueStart);
        assertTrue(name + " should have a closing tag", valueEnd >= 0);
        return xml.substring(valueStart, valueEnd);
    }
}
