package com.hippo2cat.smspusher.ble;

import java.util.List;

public final class BlePeripheralController {
    public interface Gateway {
        boolean hasRequiredPermissions();
        boolean isAdapterAvailable();
        void openGattServer(BleGattProfile profile);
        void startAdvertising(BleGattProfile profile);
        void notifyMessage(BleFrame frame);
        void stopAdvertising();
        void closeGattServer();
    }

    public interface AckSink {
        void accept(String messageId);
    }

    private final Gateway gateway;
    private final AckSink ackSink;
    private final BleGattProfile profile;
    private BlePeripheralState state = BlePeripheralState.STOPPED;

    public BlePeripheralController(Gateway gateway, AckSink ackSink) {
        this(gateway, ackSink, BleGattProfile.smsPusher());
    }

    public BlePeripheralController(Gateway gateway, AckSink ackSink, BleGattProfile profile) {
        this.gateway = gateway;
        this.ackSink = ackSink;
        this.profile = profile;
    }

    public BlePeripheralState start() {
        if (!gateway.hasRequiredPermissions()) {
            state = BlePeripheralState.PERMISSION_REQUIRED;
            return state;
        }
        if (!gateway.isAdapterAvailable()) {
            state = BlePeripheralState.ADAPTER_UNAVAILABLE;
            return state;
        }
        gateway.openGattServer(profile);
        gateway.startAdvertising(profile);
        state = BlePeripheralState.ADVERTISING;
        return state;
    }

    public void publishMessage(String messageId, byte[] payload, int maxPayloadSize) throws BleFrameException {
        List<BleFrame> frames = BleFrameCodec.chunkPayload(messageId, payload, maxPayloadSize);
        for (BleFrame frame : frames) {
            gateway.notifyMessage(frame);
        }
    }

    public void handleAckWrite(String json) throws BleFrameException {
        ackSink.accept(BleAckCodec.decode(json).messageId());
    }

    public BlePeripheralState stop() {
        gateway.stopAdvertising();
        gateway.closeGattServer();
        state = BlePeripheralState.STOPPED;
        return state;
    }

    public BlePeripheralState state() {
        return state;
    }
}
