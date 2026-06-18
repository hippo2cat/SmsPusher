package com.hippo2cat.smspusher.state;

public final class ForwardingStatsText {
    public final String pendingLine;
    public final String lastReceivedLine;
    public final String lastForwardedLine;
    public final String lastFailureLine;

    private ForwardingStatsText(String pendingLine, String lastReceivedLine, String lastForwardedLine, String lastFailureLine) {
        this.pendingLine = pendingLine;
        this.lastReceivedLine = lastReceivedLine;
        this.lastForwardedLine = lastForwardedLine;
        this.lastFailureLine = lastFailureLine;
    }

    public static ForwardingStatsText from(int pendingCount, String lastReceivedAt, String lastForwardedAt, String lastFailureReason, Copy copy) {
        return new ForwardingStatsText(
            format(copy.pendingTemplate, "count", String.valueOf(Math.max(0, pendingCount))),
            format(copy.lastReceivedTemplate, "value", dashIfBlank(lastReceivedAt)),
            format(copy.lastForwardedTemplate, "value", dashIfBlank(lastForwardedAt)),
            format(copy.failureReasonTemplate, "value", dashIfBlank(lastFailureReason))
        );
    }

    private static String dashIfBlank(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        return value;
    }

    public static final class Copy {
        public final String pendingTemplate;
        public final String lastReceivedTemplate;
        public final String lastForwardedTemplate;
        public final String failureReasonTemplate;

        public Copy(String pendingTemplate, String lastReceivedTemplate, String lastForwardedTemplate, String failureReasonTemplate) {
            this.pendingTemplate = pendingTemplate;
            this.lastReceivedTemplate = lastReceivedTemplate;
            this.lastForwardedTemplate = lastForwardedTemplate;
            this.failureReasonTemplate = failureReasonTemplate;
        }
    }

    private static String format(String template, String name, String value) {
        return template == null ? "" : template.replace("{" + name + "}", value == null ? "" : value);
    }
}
