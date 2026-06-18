package com.hippo2cat.smspusher.ble;

import org.json.JSONException;
import org.json.JSONObject;

public final class BleAckCodec {
    private BleAckCodec() {}

    public static String encode(BleAck ack) throws BleFrameException {
        validate(ack);
        try {
            return new JSONObject()
                .put("version", ack.version())
                .put("messageId", ack.messageId())
                .toString();
        } catch (JSONException error) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_JSON, error.getMessage());
        }
    }

    public static BleAck decode(String json) throws BleFrameException {
        try {
            JSONObject object = new JSONObject(json);
            BleAck ack = new BleAck(
                object.getInt("version"),
                object.getString("messageId")
            );
            validate(ack);
            return ack;
        } catch (JSONException error) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_JSON, error.getMessage());
        }
    }

    private static void validate(BleAck ack) throws BleFrameException {
        if (ack == null || ack.version() != BleFrameCodec.VERSION || ack.messageId() == null || ack.messageId().isEmpty()) {
            throw new BleFrameException(BleFrameException.Reason.INCONSISTENT_FRAME, "invalid ack");
        }
    }
}
