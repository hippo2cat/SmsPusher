package com.hippo2cat.smspusher.ble;

public final class BleFrameException extends Exception {
    public enum Reason {
        INVALID_MAX_PAYLOAD_SIZE,
        EMPTY_PAYLOAD,
        INVALID_CHUNK_INDEX,
        INCONSISTENT_FRAME,
        INVALID_BASE64,
        INVALID_JSON
    }

    public final Reason reason;

    public BleFrameException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
