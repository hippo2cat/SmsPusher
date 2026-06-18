package com.hippo2cat.smspusher.permission;

public final class RuntimePermissionRequestPolicy {
    public enum Action {
        REQUEST_RUNTIME_PERMISSION
    }

    private RuntimePermissionRequestPolicy() {}

    public static Action actionForToggle() {
        return Action.REQUEST_RUNTIME_PERMISSION;
    }
}
