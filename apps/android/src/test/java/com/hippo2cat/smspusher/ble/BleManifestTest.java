package com.hippo2cat.smspusher.ble;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BleManifestTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void manifestDeclaresBlePeripheralPermissions() throws Exception {
        Document manifest = loadManifest();

        assertTrue(hasPermission(manifest, "android.permission.BLUETOOTH", "30"));
        assertTrue(hasPermission(manifest, "android.permission.BLUETOOTH_ADMIN", "30"));
        assertTrue(hasPermission(manifest, "android.permission.BLUETOOTH_ADVERTISE", ""));
        assertTrue(hasPermission(manifest, "android.permission.BLUETOOTH_CONNECT", ""));
        assertFalse(hasPermissionNamed(manifest, "android.permission.BLUETOOTH_SCAN"));
    }

    @Test
    public void manifestDeclaresBleFeatureAsOptional() throws Exception {
        Document manifest = loadManifest();
        NodeList features = manifest.getElementsByTagName("uses-feature");
        boolean found = false;
        for (int index = 0; index < features.getLength(); index += 1) {
            Element feature = (Element) features.item(index);
            if ("android.hardware.bluetooth_le".equals(androidAttribute(feature, "name"))) {
                found = true;
                assertEquals("false", androidAttribute(feature, "required"));
            }
        }
        assertTrue(found);
    }

    private static Document loadManifest() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
            .parse(Paths.get("src/main/AndroidManifest.xml").toFile());
    }

    private static boolean hasPermission(Document manifest, String name, String maxSdkVersion) {
        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int index = 0; index < permissions.getLength(); index += 1) {
            Element permission = (Element) permissions.item(index);
            if (!name.equals(androidAttribute(permission, "name"))) continue;
            return maxSdkVersion.equals(androidAttribute(permission, "maxSdkVersion"));
        }
        return false;
    }

    private static boolean hasPermissionNamed(Document manifest, String name) {
        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int index = 0; index < permissions.getLength(); index += 1) {
            Element permission = (Element) permissions.item(index);
            if (name.equals(androidAttribute(permission, "name"))) return true;
        }
        return false;
    }

    private static String androidAttribute(Element element, String name) {
        return element.getAttributeNS(ANDROID_NS, name);
    }
}
