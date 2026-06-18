package com.hippo2cat.smspusher.miui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MiuiPermissionGuideTest {
    @Test
    public void detectsMiuiFromSystemProperties() {
        assertTrue(MiuiPermissionGuide.isMiui("Xiaomi", "V816", ""));
        assertTrue(MiuiPermissionGuide.isMiui("POCO", "", "2.0"));
        assertFalse(MiuiPermissionGuide.isMiui("Xiaomi", "", ""));
        assertFalse(MiuiPermissionGuide.isMiui("Google", "", ""));
    }

    @Test
    public void promptsOnlyWhenMiuiPermissionIsNotAllowed() {
        assertTrue(MiuiPermissionGuide.shouldPrompt(true, false, false));
        assertFalse(MiuiPermissionGuide.shouldPrompt(false, false, false));
        assertFalse(MiuiPermissionGuide.shouldPrompt(true, true, false));
        assertFalse(MiuiPermissionGuide.shouldPrompt(true, false, true));
    }

    @Test
    public void declaresMiuiSystemPermissionConstant() {
        assertTrue(MiuiPermissionGuide.SYSTEM_PERMISSION_DECLARE.contains("SYSTEM_PERMISSION_DECLARE"));
        assertTrue(MiuiPermissionGuide.RECEIVE_SENSITIVE_NOTIFICATIONS.contains("RECEIVE_SENSITIVE_NOTIFICATIONS"));
        assertTrue(MiuiPermissionGuide.NOTIFICATION_SMS_APP_OP > 0);
    }
}
