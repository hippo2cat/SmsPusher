package com.hippo2cat.smspusher.ble;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class BleFrameCodecTest {
    @Test
    public void chunkPayloadSplitsPayloadAndSetsFrameMetadata() throws Exception {
        byte[] payload = "{\"messageId\":\"msg_1\",\"body\":\"Your test verification code is 135790\"}".getBytes(StandardCharsets.UTF_8);

        List<BleFrame> frames = BleFrameCodec.chunkPayload("ble-msg-1", payload, 12);

        assertTrue(frames.size() > 1);
        for (int index = 0; index < frames.size(); index += 1) {
            BleFrame frame = frames.get(index);
            assertEquals(BleFrameCodec.VERSION, frame.version());
            assertEquals("ble-msg-1", frame.messageId());
            assertEquals(index, frame.chunkIndex());
            assertEquals(frames.size(), frame.chunkCount());
            assertTrue(frame.payload().length <= 12);
        }
    }

    @Test
    public void frameJsonUsesCamelCaseFieldsAndRoundTrips() throws Exception {
        BleFrame frame = new BleFrame(
            BleFrameCodec.VERSION,
            "ble-msg-5",
            1,
            2,
            "hello".getBytes(StandardCharsets.UTF_8)
        );

        String encoded = BleFrameCodec.encodeFrame(frame);
        JSONObject json = new JSONObject(encoded);

        assertEquals(BleFrameCodec.VERSION, json.getInt("version"));
        assertEquals("ble-msg-5", json.getString("messageId"));
        assertEquals(1, json.getInt("chunkIndex"));
        assertEquals(2, json.getInt("chunkCount"));
        assertEquals("aGVsbG8=", json.getString("payloadBase64"));

        BleFrame decoded = BleFrameCodec.decodeFrame(encoded);
        assertEquals(frame.version(), decoded.version());
        assertEquals(frame.messageId(), decoded.messageId());
        assertEquals(frame.chunkIndex(), decoded.chunkIndex());
        assertEquals(frame.chunkCount(), decoded.chunkCount());
        assertArrayEquals(frame.payload(), decoded.payload());
    }

    @Test
    public void chunkPayloadRejectsZeroMaxPayloadSize() {
        try {
            BleFrameCodec.chunkPayload("ble-msg-4", "hello".getBytes(StandardCharsets.UTF_8), 0);
            fail("Expected BleFrameException");
        } catch (BleFrameException expected) {
            assertEquals(BleFrameException.Reason.INVALID_MAX_PAYLOAD_SIZE, expected.reason);
        }
    }

    @Test
    public void decodeFrameRejectsInvalidChunkIndex() throws Exception {
        JSONObject json = new JSONObject()
            .put("version", BleFrameCodec.VERSION)
            .put("messageId", "ble-msg-6")
            .put("chunkIndex", 2)
            .put("chunkCount", 2)
            .put("payloadBase64", Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));

        try {
            BleFrameCodec.decodeFrame(json.toString());
            fail("Expected BleFrameException");
        } catch (BleFrameException expected) {
            assertEquals(BleFrameException.Reason.INVALID_CHUNK_INDEX, expected.reason);
        }
    }
}
