package com.hippo2cat.smspusher.sms;

public final class PendingMessage {
    public final String messageId;
    public final String sender;
    public final String body;
    public final String receivedAt;
    public final MessageEvent.Status status;
    public final String failureReason;

    public PendingMessage(
        String messageId,
        String sender,
        String body,
        String receivedAt,
        MessageEvent.Status status,
        String failureReason
    ) {
        this.messageId = messageId;
        this.sender = sender;
        this.body = body;
        this.receivedAt = receivedAt;
        this.status = status;
        this.failureReason = failureReason;
    }
}
