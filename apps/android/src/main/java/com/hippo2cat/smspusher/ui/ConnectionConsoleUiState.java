package com.hippo2cat.smspusher.ui;

import java.time.Duration;
import java.time.Instant;

public final class ConnectionConsoleUiState {
    public final String stateLabel;
    public final String statusTitle;
    public final String tokenText;
    public final String queueText;
    public final String connectionTabLabel;
    public final String permissionTabLabel;
    public final String queueTabLabel;
    public final boolean needsAttention;

    private ConnectionConsoleUiState(
        String stateLabel,
        String statusTitle,
        String tokenText,
        String queueText,
        Copy copy,
        boolean needsAttention
    ) {
        this.stateLabel = stateLabel;
        this.statusTitle = statusTitle;
        this.tokenText = tokenText;
        this.queueText = queueText;
        this.connectionTabLabel = copy.connectionTab;
        this.permissionTabLabel = copy.permissionTab;
        this.queueTabLabel = copy.queueTab;
        this.needsAttention = needsAttention;
    }

    public static ConnectionConsoleUiState from(
        boolean smsPermissionGranted,
        boolean inboxReadPermissionGranted,
        boolean notificationPermissionGranted,
        boolean miuiDevice,
        boolean miuiNotificationSmsAllowed,
        boolean paired,
        String macBaseUrl,
        int pendingCount,
        boolean listenerRunning,
        Instant accessTokenExpiresAt,
        Instant now,
        Copy copy
    ) {
        boolean permissionsOk = smsPermissionGranted
            && inboxReadPermissionGranted
            && notificationPermissionGranted
            && (!miuiDevice || miuiNotificationSmsAllowed);
        String queueText = format(copy.queueTemplate, "count", String.valueOf(Math.max(0, pendingCount)));
        if (!permissionsOk) {
            return new ConnectionConsoleUiState(copy.permissionLimited, copy.permissionLimited, copy.permissionRequired, queueText, copy, true);
        }
        if (!paired || macBaseUrl == null || macBaseUrl.isEmpty()) {
            return new ConnectionConsoleUiState(copy.needsPairing, copy.needsPairing, copy.needsRepairing, queueText, copy, true);
        }

        String port = portFrom(macBaseUrl);
        String tokenText = tokenText(accessTokenExpiresAt, now, copy);
        if (!listenerRunning) {
            return new ConnectionConsoleUiState(copy.macUnreachable, format(copy.macOfflineWithPortTemplate, "port", port), tokenText, queueText, copy, true);
        }
        return new ConnectionConsoleUiState(copy.online, format(copy.macOnlineWithPortTemplate, "port", port), tokenText, queueText, copy, false);
    }

    private static String tokenText(Instant accessTokenExpiresAt, Instant now, Copy copy) {
        if (accessTokenExpiresAt == null || now == null) return copy.tokenPairAgain;
        long hours = Math.max(0, Duration.between(now, accessTokenExpiresAt).toHours());
        if (hours == 0 && now.isBefore(accessTokenExpiresAt)) return copy.tokenValidLessThanHour;
        if (hours > 0) return format(copy.tokenValidHoursTemplate, "hours", String.valueOf(hours));
        return copy.tokenExpired;
    }

    private static String portFrom(String macBaseUrl) {
        int colon = macBaseUrl.lastIndexOf(':');
        if (colon < 0 || colon == macBaseUrl.length() - 1) return "55515";
        return macBaseUrl.substring(colon + 1);
    }

    public static final class Copy {
        public final String connectionTab;
        public final String permissionTab;
        public final String queueTab;
        public final String queueTemplate;
        public final String permissionLimited;
        public final String permissionRequired;
        public final String needsPairing;
        public final String needsRepairing;
        public final String macUnreachable;
        public final String macOfflineWithPortTemplate;
        public final String online;
        public final String macOnlineWithPortTemplate;
        public final String tokenPairAgain;
        public final String tokenValidLessThanHour;
        public final String tokenValidHoursTemplate;
        public final String tokenExpired;

        public Copy(
            String connectionTab,
            String permissionTab,
            String queueTab,
            String queueTemplate,
            String permissionLimited,
            String permissionRequired,
            String needsPairing,
            String needsRepairing,
            String macUnreachable,
            String macOfflineWithPortTemplate,
            String online,
            String macOnlineWithPortTemplate,
            String tokenPairAgain,
            String tokenValidLessThanHour,
            String tokenValidHoursTemplate,
            String tokenExpired
        ) {
            this.connectionTab = connectionTab;
            this.permissionTab = permissionTab;
            this.queueTab = queueTab;
            this.queueTemplate = queueTemplate;
            this.permissionLimited = permissionLimited;
            this.permissionRequired = permissionRequired;
            this.needsPairing = needsPairing;
            this.needsRepairing = needsRepairing;
            this.macUnreachable = macUnreachable;
            this.macOfflineWithPortTemplate = macOfflineWithPortTemplate;
            this.online = online;
            this.macOnlineWithPortTemplate = macOnlineWithPortTemplate;
            this.tokenPairAgain = tokenPairAgain;
            this.tokenValidLessThanHour = tokenValidLessThanHour;
            this.tokenValidHoursTemplate = tokenValidHoursTemplate;
            this.tokenExpired = tokenExpired;
        }
    }

    private static String format(String template, String name, String value) {
        return template == null ? "" : template.replace("{" + name + "}", value == null ? "" : value);
    }
}
