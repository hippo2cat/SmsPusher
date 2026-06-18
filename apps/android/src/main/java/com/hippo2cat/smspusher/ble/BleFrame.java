package com.hippo2cat.smspusher.ble;

import java.util.Arrays;

public final class BleFrame {
    private final int version;
    private final String messageId;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] payload;

    public BleFrame(int version, String messageId, int chunkIndex, int chunkCount, byte[] payload) {
        this.version = version;
        this.messageId = messageId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public int version() {
        return version;
    }

    public String messageId() {
        return messageId;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
