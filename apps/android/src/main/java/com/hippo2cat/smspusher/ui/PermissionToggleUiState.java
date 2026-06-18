package com.hippo2cat.smspusher.ui;

public final class PermissionToggleUiState {
    public final String title;
    public final boolean checked;
    public final boolean actionEnabled;
    public final String actionLabel;

    private PermissionToggleUiState(String title, boolean checked, boolean actionEnabled, String actionLabel) {
        this.title = title;
        this.checked = checked;
        this.actionEnabled = actionEnabled;
        this.actionLabel = actionLabel;
    }

    public static PermissionToggleUiState runtime(String title, boolean granted, String actionLabel, String defaultActionLabel) {
        return new PermissionToggleUiState(
            title,
            granted,
            !granted,
            granted ? "" : nonEmpty(actionLabel, defaultActionLabel)
        );
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
