package com.hippo2cat.smspusher.miui;

public final class MiuiPermissionGuide {
    public static final String SYSTEM_PERMISSION_DECLARE = "com.miui.securitycenter.permission.SYSTEM_PERMISSION_DECLARE";
    public static final String RECEIVE_SENSITIVE_NOTIFICATIONS = "android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS";
    public static final int NOTIFICATION_SMS_APP_OP = 10018;

    private MiuiPermissionGuide() {}

    public static boolean isMiui(String manufacturer, String miuiVersionName, String hyperOsVersionName) {
        return !isBlank(miuiVersionName) || !isBlank(hyperOsVersionName);
    }

    public static boolean shouldPrompt(boolean isMiui, boolean appOpAllowed, boolean promptedBefore) {
        return isMiui && !appOpAllowed && !promptedBefore;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
