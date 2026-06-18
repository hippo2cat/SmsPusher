package com.hippo2cat.smspusher.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

public final class IncomingSms {
    public final String sender;
    public final String body;
    public final Instant receivedAt;
    public final int subscriptionId;
    public final String deviceId;

    public IncomingSms(String sender, String body, Instant receivedAt, int subscriptionId, String deviceId) {
        this.sender = sender;
        this.body = body;
        this.receivedAt = receivedAt;
        this.subscriptionId = subscriptionId;
        this.deviceId = deviceId;
    }

    public String messageId() {
        String input = sender + "\n" + body + "\n" + receivedAt + "\n" + subscriptionId + "\n" + deviceId;
        return "msg_" + sha256(input).substring(0, 16);
    }

    public String toJson() {
        return "{"
            + "\"messageId\":\"" + escape(messageId()) + "\","
            + "\"sender\":\"" + escape(sender) + "\","
            + "\"body\":\"" + escape(body) + "\","
            + "\"receivedAt\":\"" + escape(receivedAt.toString()) + "\","
            + "\"subscriptionId\":" + subscriptionId + ","
            + "\"deviceId\":\"" + escape(deviceId) + "\""
            + "}";
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static String sanitizeJsonControlCharacters(String json) {
        StringBuilder builder = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                if (c == '"') inString = true;
                builder.append(c);
                continue;
            }
            if (escaped) {
                builder.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                builder.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = false;
                builder.append(c);
                continue;
            }
            appendEscapedControlCharacter(builder, c);
        }
        return builder.toString();
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                builder.append("\\\\");
            } else if (c == '"') {
                builder.append("\\\"");
            } else {
                appendEscapedControlCharacter(builder, c);
            }
        }
        return builder.toString();
    }

    private static void appendEscapedControlCharacter(StringBuilder builder, char c) {
        switch (c) {
            case '\n':
                builder.append("\\n");
                break;
            case '\r':
                builder.append("\\r");
                break;
            case '\t':
                builder.append("\\t");
                break;
            case '\b':
                builder.append("\\b");
                break;
            case '\f':
                builder.append("\\f");
                break;
            default:
                if (c < 0x20) {
                    builder.append(String.format("\\u%04x", (int) c));
                } else {
                    builder.append(c);
                }
                break;
        }
    }
}
