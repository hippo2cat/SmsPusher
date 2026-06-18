package com.hippo2cat.smspusher.ble;

public final class BleAck {
    private final int version;
    private final String messageId;

    public BleAck(int version, String messageId) {
        this.version = version;
        this.messageId = messageId;
    }

    public int version() {
        return version;
    }

    public String messageId() {
        return messageId;
    }
}
