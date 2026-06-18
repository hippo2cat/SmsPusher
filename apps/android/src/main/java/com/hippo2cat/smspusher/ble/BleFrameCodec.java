package com.hippo2cat.smspusher.ble;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public final class BleFrameCodec {
    public static final int VERSION = 1;
    private static final int MAX_CHUNK_COUNT = 0xFFFF;

    private BleFrameCodec() {}

    public static List<BleFrame> chunkPayload(String messageId, byte[] payload, int maxPayloadSize) throws BleFrameException {
        if (maxPayloadSize <= 0) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_MAX_PAYLOAD_SIZE, "max payload size must be positive");
        }
        if (payload == null || payload.length == 0) {
            throw new BleFrameException(BleFrameException.Reason.EMPTY_PAYLOAD, "payload must not be empty");
        }
        int chunkCount = (payload.length + maxPayloadSize - 1) / maxPayloadSize;
        if (chunkCount > MAX_CHUNK_COUNT) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_CHUNK_INDEX, "too many chunks");
        }
        ArrayList<BleFrame> frames = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index += 1) {
            int start = index * maxPayloadSize;
            int end = Math.min(payload.length, start + maxPayloadSize);
            frames.add(new BleFrame(VERSION, messageId, index, chunkCount, Arrays.copyOfRange(payload, start, end)));
        }
        return frames;
    }

    public static String encodeFrame(BleFrame frame) throws BleFrameException {
        validateFrame(frame);
        try {
            return new JSONObject()
                .put("version", frame.version())
                .put("messageId", frame.messageId())
                .put("chunkIndex", frame.chunkIndex())
                .put("chunkCount", frame.chunkCount())
                .put("payloadBase64", Base64.getEncoder().encodeToString(frame.payload()))
                .toString();
        } catch (JSONException error) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_JSON, error.getMessage());
        }
    }

    public static BleFrame decodeFrame(String json) throws BleFrameException {
        try {
            JSONObject object = new JSONObject(json);
            byte[] payload;
            try {
                payload = Base64.getDecoder().decode(object.getString("payloadBase64"));
            } catch (IllegalArgumentException error) {
                throw new BleFrameException(BleFrameException.Reason.INVALID_BASE64, error.getMessage());
            }
            BleFrame frame = new BleFrame(
                object.getInt("version"),
                object.getString("messageId"),
                object.getInt("chunkIndex"),
                object.getInt("chunkCount"),
                payload
            );
            validateFrame(frame);
            return frame;
        } catch (JSONException error) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_JSON, error.getMessage());
        }
    }

    static void validateFrame(BleFrame frame) throws BleFrameException {
        if (frame == null) {
            throw new BleFrameException(BleFrameException.Reason.INCONSISTENT_FRAME, "frame must not be null");
        }
        if (frame.version() != VERSION) {
            throw new BleFrameException(BleFrameException.Reason.INCONSISTENT_FRAME, "unsupported frame version");
        }
        if (frame.messageId() == null || frame.messageId().isEmpty()) {
            throw new BleFrameException(BleFrameException.Reason.INCONSISTENT_FRAME, "message id must not be empty");
        }
        if (frame.chunkCount() <= 0 || frame.chunkIndex() < 0 || frame.chunkIndex() >= frame.chunkCount()) {
            throw new BleFrameException(BleFrameException.Reason.INVALID_CHUNK_INDEX, "invalid chunk index");
        }
        if (frame.payload().length == 0) {
            throw new BleFrameException(BleFrameException.Reason.EMPTY_PAYLOAD, "payload must not be empty");
        }
    }
}
