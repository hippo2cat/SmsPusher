package com.hippo2cat.smspusher.permission;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RuntimePermissionRequestPolicyTest {
    @Test
    public void firstMissingPermissionUsesRuntimePrompt() {
        assertEquals(
            RuntimePermissionRequestPolicy.Action.REQUEST_RUNTIME_PERMISSION,
            RuntimePermissionRequestPolicy.actionForToggle()
        );
    }

    @Test
    public void previouslyRequestedPermissionWithoutRationaleStillUsesRuntimePromptFromToggle() {
        assertEquals(
            RuntimePermissionRequestPolicy.Action.REQUEST_RUNTIME_PERMISSION,
            RuntimePermissionRequestPolicy.actionForToggle()
        );
    }

    @Test
    public void deniedTogglePermissionWithoutRationaleStillUsesRuntimePromptFromToggle() {
        assertEquals(
            RuntimePermissionRequestPolicy.Action.REQUEST_RUNTIME_PERMISSION,
            RuntimePermissionRequestPolicy.actionForToggle()
        );
    }

    @Test
    public void deniedTogglePermissionWithRationaleUsesRuntimePromptFromToggle() {
        assertEquals(
            RuntimePermissionRequestPolicy.Action.REQUEST_RUNTIME_PERMISSION,
            RuntimePermissionRequestPolicy.actionForToggle()
        );
    }
}
