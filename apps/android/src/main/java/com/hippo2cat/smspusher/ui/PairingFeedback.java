package com.hippo2cat.smspusher.ui;

import com.hippo2cat.smspusher.net.SmsBridgeClient;

public final class PairingFeedback {
    public final String inlineError;
    public final String sectionError;

    private PairingFeedback(String inlineError, String sectionError) {
        this.inlineError = inlineError;
        this.sectionError = sectionError;
    }

    public static PairingFeedback from(Exception error, Copy copy) {
        if (error instanceof SmsBridgeClient.PairingRequiredException) {
            String reason = ((SmsBridgeClient.PairingRequiredException) error).reason;
            if ("invalid_pairing_code".equals(reason)) {
                return new PairingFeedback(copy.invalidCode, "");
            }
            if ("pairing_code_expired".equals(reason)) {
                return new PairingFeedback(copy.codeExpired, "");
            }
        }
        String message = error == null ? "" : error.getMessage();
        if (message != null && message.equals(copy.sessionUnavailable)) {
            return new PairingFeedback("", message);
        }
        return new PairingFeedback("", copy.unableToConnect);
    }

    public static final class Copy {
        public final String invalidCode;
        public final String codeExpired;
        public final String sessionUnavailable;
        public final String unableToConnect;

        public Copy(String invalidCode, String codeExpired, String sessionUnavailable, String unableToConnect) {
            this.invalidCode = invalidCode;
            this.codeExpired = codeExpired;
            this.sessionUnavailable = sessionUnavailable;
            this.unableToConnect = unableToConnect;
        }
    }
}
