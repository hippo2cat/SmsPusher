package com.hippo2cat.smspusher.state;

public final class PairingStatusText {
    private PairingStatusText() {}

    public static String from(boolean hasToken, String macBaseUrl, Copy copy) {
        if (hasToken && macBaseUrl != null && !macBaseUrl.isEmpty()) {
            return format(copy.pairedWithTemplate, "baseUrl", macBaseUrl);
        }
        return copy.discovering;
    }

    public static final class Copy {
        public final String pairedWithTemplate;
        public final String discovering;

        public Copy(String pairedWithTemplate, String discovering) {
            this.pairedWithTemplate = pairedWithTemplate;
            this.discovering = discovering;
        }
    }

    private static String format(String template, String name, String value) {
        return template == null ? "" : template.replace("{" + name + "}", value == null ? "" : value);
    }
}
