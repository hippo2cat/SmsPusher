package com.hippo2cat.smspusher.ui;

import java.net.URI;

public final class ConnectionTestUiState {
    public final String buttonLabel;
    public final boolean buttonEnabled;
    public final String feedbackText;
    public final boolean feedbackVisible;
    public final boolean feedbackSuccess;

    private ConnectionTestUiState(
        String buttonLabel,
        boolean buttonEnabled,
        String feedbackText,
        boolean feedbackVisible,
        boolean feedbackSuccess
    ) {
        this.buttonLabel = buttonLabel;
        this.buttonEnabled = buttonEnabled;
        this.feedbackText = feedbackText;
        this.feedbackVisible = feedbackVisible;
        this.feedbackSuccess = feedbackSuccess;
    }

    public static ConnectionTestUiState idle(Copy copy) {
        return new ConnectionTestUiState(copy.buttonLabel, true, "", false, false);
    }

    public static ConnectionTestUiState running(Copy copy) {
        return new ConnectionTestUiState(copy.runningButtonLabel, false, copy.runningFeedback, true, false);
    }

    public static ConnectionTestUiState success(String baseUrl, Copy copy) {
        return new ConnectionTestUiState(copy.buttonLabel, true, format(copy.successFeedbackTemplate, "endpoint", endpointText(baseUrl, copy)), true, true);
    }

    public static ConnectionTestUiState failure(String message, Copy copy) {
        String text = message == null || message.isEmpty() ? copy.failureDefault : message;
        return new ConnectionTestUiState(copy.retryButtonLabel, true, text, true, false);
    }

    private static String endpointText(String baseUrl, Copy copy) {
        if (baseUrl == null || baseUrl.isEmpty()) return copy.unknownMac;
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host != null && !host.isEmpty()) {
                return host + ":" + (port > 0 ? port : 55515);
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to a readable endpoint below.
        }
        return baseUrl.replace("http://", "").replace("https://", "");
    }

    public static final class Copy {
        public final String buttonLabel;
        public final String runningButtonLabel;
        public final String runningFeedback;
        public final String successFeedbackTemplate;
        public final String failureDefault;
        public final String retryButtonLabel;
        public final String unknownMac;

        public Copy(
            String buttonLabel,
            String runningButtonLabel,
            String runningFeedback,
            String successFeedbackTemplate,
            String failureDefault,
            String retryButtonLabel,
            String unknownMac
        ) {
            this.buttonLabel = buttonLabel;
            this.runningButtonLabel = runningButtonLabel;
            this.runningFeedback = runningFeedback;
            this.successFeedbackTemplate = successFeedbackTemplate;
            this.failureDefault = failureDefault;
            this.retryButtonLabel = retryButtonLabel;
            this.unknownMac = unknownMac;
        }
    }

    private static String format(String template, String name, String value) {
        return template == null ? "" : template.replace("{" + name + "}", value == null ? "" : value);
    }
}
