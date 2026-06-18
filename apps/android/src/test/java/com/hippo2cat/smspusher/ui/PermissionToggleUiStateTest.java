package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PermissionToggleUiStateTest {
    @Test
    public void missingRuntimePermissionIsActionableOffToggle() {
        PermissionToggleUiState state = PermissionToggleUiState.runtime("短信权限", false, "去开启", "去开启");

        assertFalse(state.checked);
        assertTrue(state.actionEnabled);
        assertEquals("去开启", state.actionLabel);
    }

    @Test
    public void grantedRuntimePermissionStaysCheckedAndDoesNotPretendToDisable() {
        PermissionToggleUiState state = PermissionToggleUiState.runtime("短信权限", true, "去开启", "去开启");

        assertTrue(state.checked);
        assertFalse(state.actionEnabled);
        assertEquals("", state.actionLabel);
    }
}
