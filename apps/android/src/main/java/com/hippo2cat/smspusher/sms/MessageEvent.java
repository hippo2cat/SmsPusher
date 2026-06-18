package com.hippo2cat.smspusher.sms;

public final class MessageEvent {
    public enum Source {
        BROADCAST("broadcast"),
        INBOX("inbox");

        public final String value;

        Source(String value) {
            this.value = value;
        }

        public static Source from(String value) {
            if (INBOX.value.equals(value)) return INBOX;
            return BROADCAST;
        }
    }

    public enum Status {
        PENDING("pending"),
        FORWARDED("forwarded"),
        FAILED("failed");

        public final String value;

        Status(String value) {
            this.value = value;
        }

        public static Status from(String value) {
            if (FORWARDED.value.equals(value)) return FORWARDED;
            if (FAILED.value.equals(value)) return FAILED;
            return PENDING;
        }
    }

    public final String messageId;
    public final String sender;
    public final String body;
    public final String receivedAt;
    public final int subscriptionId;
    public final String deviceId;
    public final Source source;
    public final Status status;
    public final String destination;
    public final String failureReason;
    public final String lastAttemptAt;
    public final String forwardedAt;
    public final long createdAtMillis;
    public final long updatedAtMillis;

    public MessageEvent(
        String messageId,
        String sender,
        String body,
        String receivedAt,
        int subscriptionId,
        String deviceId,
        Source source,
        Status status,
        String destination,
        String failureReason,
        String lastAttemptAt,
        String forwardedAt,
        long createdAtMillis,
        long updatedAtMillis
    ) {
        this.messageId = messageId;
        this.sender = sender;
        this.body = body;
        this.receivedAt = receivedAt;
        this.subscriptionId = subscriptionId;
        this.deviceId = deviceId;
        this.source = source;
        this.status = status;
        this.destination = destination;
        this.failureReason = failureReason;
        this.lastAttemptAt = lastAttemptAt;
        this.forwardedAt = forwardedAt;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }
}
