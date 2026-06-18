package com.hippo2cat.smspusher.ble;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class BleAckCodecTest {
    @Test
    public void ackJsonUsesVersionAndMessageIdAndRoundTrips() throws Exception {
        BleAck ack = new BleAck(BleFrameCodec.VERSION, "msg_1");

        String encoded = BleAckCodec.encode(ack);
        JSONObject json = new JSONObject(encoded);

        assertEquals(BleFrameCodec.VERSION, json.getInt("version"));
        assertEquals("msg_1", json.getString("messageId"));

        BleAck decoded = BleAckCodec.decode(encoded);
        assertEquals(ack.version(), decoded.version());
        assertEquals(ack.messageId(), decoded.messageId());
    }

    @Test
    public void decodeRejectsUnsupportedVersion() throws Exception {
        String encoded = new JSONObject()
            .put("version", 2)
            .put("messageId", "msg_1")
            .toString();

        try {
            BleAckCodec.decode(encoded);
            fail("Expected BleFrameException");
        } catch (BleFrameException expected) {
            assertEquals(BleFrameException.Reason.INCONSISTENT_FRAME, expected.reason);
        }
    }

    @Test
    public void decodeRejectsEmptyMessageId() throws Exception {
        String encoded = new JSONObject()
            .put("version", BleFrameCodec.VERSION)
            .put("messageId", "")
            .toString();

        try {
            BleAckCodec.decode(encoded);
            fail("Expected BleFrameException");
        } catch (BleFrameException expected) {
            assertEquals(BleFrameException.Reason.INCONSISTENT_FRAME, expected.reason);
        }
    }
}
