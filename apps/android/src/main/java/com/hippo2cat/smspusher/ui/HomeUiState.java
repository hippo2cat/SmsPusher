package com.hippo2cat.smspusher.ui;

public final class HomeUiState {
    public final String summary;
    public final String privacyText;
    public final PermissionRow smsPermission;
    public final PermissionRow inboxPermission;
    public final PermissionRow notificationPermission;
    public final PermissionRow miuiPermission;
    public final boolean showMiuiPermission;
    public final String macText;
    public final String pendingText;
    public final String listenerText;
    public final String rePairActionLabel;
    public final boolean rePairActionEnabled;
    public final boolean needsAttention;

    private HomeUiState(
        String summary,
        String privacyText,
        PermissionRow smsPermission,
        PermissionRow inboxPermission,
        PermissionRow notificationPermission,
        PermissionRow miuiPermission,
        boolean showMiuiPermission,
        String macText,
        String pendingText,
        String listenerText,
        String rePairActionLabel,
        boolean rePairActionEnabled,
        boolean needsAttention
    ) {
        this.summary = summary;
        this.privacyText = privacyText;
        this.smsPermission = smsPermission;
        this.inboxPermission = inboxPermission;
        this.notificationPermission = notificationPermission;
        this.miuiPermission = miuiPermission;
        this.showMiuiPermission = showMiuiPermission;
        this.macText = macText;
        this.pendingText = pendingText;
        this.listenerText = listenerText;
        this.rePairActionLabel = rePairActionLabel;
        this.rePairActionEnabled = rePairActionEnabled;
        this.needsAttention = needsAttention;
    }

    public static HomeUiState from(
        boolean smsPermissionGranted,
        boolean inboxReadPermissionGranted,
        boolean notificationPermissionGranted,
        boolean miuiDevice,
        boolean miuiNotificationSmsAllowed,
        boolean paired,
        String macName,
        String macBaseUrl,
        int pendingCount,
        boolean listenerRunning,
        Copy copy
    ) {
        PermissionRow sms = smsPermissionGranted
            ? PermissionRow.enabled(copy.smsPermission, copy.smsPermissionEnabled, copy.permissionEnabled)
            : PermissionRow.required(copy.smsPermission, copy.smsPermissionRequired, copy.permissionRequired, copy.permissionEnableAction);
        PermissionRow inbox = inboxReadPermissionGranted
            ? PermissionRow.enabled(copy.inboxPermission, copy.inboxPermissionEnabled, copy.permissionEnabled)
            : PermissionRow.required(copy.inboxPermission, copy.inboxPermissionRequired, copy.permissionRequired, copy.permissionEnableAction);
        PermissionRow notifications = notificationPermissionGranted
            ? PermissionRow.enabled(copy.notificationPermission, copy.notificationPermissionEnabled, copy.permissionEnabled)
            : PermissionRow.required(copy.notificationPermission, copy.notificationPermissionRequired, copy.permissionRequired, copy.permissionEnableAction);
        PermissionRow miui = miuiNotificationSmsAllowed
            ? PermissionRow.enabled(copy.miuiPermission, copy.miuiPermissionEnabled, copy.permissionEnabled)
            : PermissionRow.required(copy.miuiPermission, copy.miuiPermissionRequired, copy.permissionRequired, copy.openMiuiSettings);

        boolean blockedByPermission = !smsPermissionGranted || !inboxReadPermissionGranted || !notificationPermissionGranted || (miuiDevice && !miuiNotificationSmsAllowed);
        String summary;
        if (!smsPermissionGranted) {
            summary = copy.needsSmsPermission;
        } else if (!inboxReadPermissionGranted) {
            summary = copy.needsInboxPermission;
        } else if (!notificationPermissionGranted) {
            summary = copy.needsNotificationPermission;
        } else if (miuiDevice && !miuiNotificationSmsAllowed) {
            summary = copy.needsMiuiPermission;
        } else if (!paired || macBaseUrl == null || macBaseUrl.isEmpty()) {
            summary = copy.needsPairing;
        } else if (!listenerRunning) {
            summary = copy.macOffline;
        } else {
            String name = macName == null || macName.isEmpty() ? macBaseUrl : macName;
            summary = format(copy.connectedTemplate, "name", name);
        }

        String macText = paired && macBaseUrl != null && !macBaseUrl.isEmpty() ? macBaseUrl : copy.unpairedMac;
        String pendingText = format(copy.pendingTemplate, "count", String.valueOf(Math.max(0, pendingCount)));
        String listenerText = listenerRunning ? copy.listening : copy.listenerBlocked;
        String rePairActionLabel = copy.clearAndPairAgain;
        boolean rePairActionEnabled = paired && macBaseUrl != null && !macBaseUrl.isEmpty();
        boolean needsAttention = blockedByPermission || !paired || !listenerRunning;

        return new HomeUiState(summary, copy.privacyText, sms, inbox, notifications, miui, miuiDevice, macText, pendingText, listenerText, rePairActionLabel, rePairActionEnabled, needsAttention);
    }

    public static final class PermissionRow {
        public final String title;
        public final String status;
        public final String description;
        public final String actionLabel;

        private PermissionRow(String title, String status, String description, String actionLabel) {
            this.title = title;
            this.status = status;
            this.description = description;
            this.actionLabel = actionLabel;
        }

        static PermissionRow enabled(String title, String description, String status) {
            return new PermissionRow(title, status, description, "");
        }

        static PermissionRow required(String title, String description, String status, String actionLabel) {
            return new PermissionRow(title, status, description, actionLabel);
        }
    }

    public static final class Copy {
        public final String privacyText;
        public final String permissionEnabled;
        public final String permissionRequired;
        public final String smsPermission;
        public final String smsPermissionEnabled;
        public final String smsPermissionRequired;
        public final String permissionEnableAction;
        public final String inboxPermission;
        public final String inboxPermissionEnabled;
        public final String inboxPermissionRequired;
        public final String notificationPermission;
        public final String notificationPermissionEnabled;
        public final String notificationPermissionRequired;
        public final String miuiPermission;
        public final String miuiPermissionEnabled;
        public final String miuiPermissionRequired;
        public final String openMiuiSettings;
        public final String needsSmsPermission;
        public final String needsInboxPermission;
        public final String needsNotificationPermission;
        public final String needsMiuiPermission;
        public final String needsPairing;
        public final String macOffline;
        public final String connectedTemplate;
        public final String unpairedMac;
        public final String pendingTemplate;
        public final String listening;
        public final String listenerBlocked;
        public final String clearAndPairAgain;

        public Copy(
            String privacyText,
            String permissionEnabled,
            String permissionRequired,
            String smsPermission,
            String smsPermissionEnabled,
            String smsPermissionRequired,
            String permissionEnableAction,
            String inboxPermission,
            String inboxPermissionEnabled,
            String inboxPermissionRequired,
            String notificationPermission,
            String notificationPermissionEnabled,
            String notificationPermissionRequired,
            String miuiPermission,
            String miuiPermissionEnabled,
            String miuiPermissionRequired,
            String openMiuiSettings,
            String needsSmsPermission,
            String needsInboxPermission,
            String needsNotificationPermission,
            String needsMiuiPermission,
            String needsPairing,
            String macOffline,
            String connectedTemplate,
            String unpairedMac,
            String pendingTemplate,
            String listening,
            String listenerBlocked,
            String clearAndPairAgain
        ) {
            this.privacyText = privacyText;
            this.permissionEnabled = permissionEnabled;
            this.permissionRequired = permissionRequired;
            this.smsPermission = smsPermission;
            this.smsPermissionEnabled = smsPermissionEnabled;
            this.smsPermissionRequired = smsPermissionRequired;
            this.permissionEnableAction = permissionEnableAction;
            this.inboxPermission = inboxPermission;
            this.inboxPermissionEnabled = inboxPermissionEnabled;
            this.inboxPermissionRequired = inboxPermissionRequired;
            this.notificationPermission = notificationPermission;
            this.notificationPermissionEnabled = notificationPermissionEnabled;
            this.notificationPermissionRequired = notificationPermissionRequired;
            this.miuiPermission = miuiPermission;
            this.miuiPermissionEnabled = miuiPermissionEnabled;
            this.miuiPermissionRequired = miuiPermissionRequired;
            this.openMiuiSettings = openMiuiSettings;
            this.needsSmsPermission = needsSmsPermission;
            this.needsInboxPermission = needsInboxPermission;
            this.needsNotificationPermission = needsNotificationPermission;
            this.needsMiuiPermission = needsMiuiPermission;
            this.needsPairing = needsPairing;
            this.macOffline = macOffline;
            this.connectedTemplate = connectedTemplate;
            this.unpairedMac = unpairedMac;
            this.pendingTemplate = pendingTemplate;
            this.listening = listening;
            this.listenerBlocked = listenerBlocked;
            this.clearAndPairAgain = clearAndPairAgain;
        }
    }

    private static String format(String template, String name, String value) {
        return template == null ? "" : template.replace("{" + name + "}", value == null ? "" : value);
    }
}
