package com.hippo2cat.smspusher.ble;

import java.util.UUID;

public final class BleGattProfile {
    private final UUID serviceUuid;
    private final UUID messageCharacteristicUuid;
    private final UUID ackCharacteristicUuid;
    private final UUID metadataCharacteristicUuid;

    public BleGattProfile(UUID serviceUuid, UUID messageCharacteristicUuid, UUID ackCharacteristicUuid, UUID metadataCharacteristicUuid) {
        this.serviceUuid = serviceUuid;
        this.messageCharacteristicUuid = messageCharacteristicUuid;
        this.ackCharacteristicUuid = ackCharacteristicUuid;
        this.metadataCharacteristicUuid = metadataCharacteristicUuid;
    }

    public static BleGattProfile smsPusher() {
        return new BleGattProfile(
            BleConstants.SERVICE_UUID,
            BleConstants.MESSAGE_CHARACTERISTIC_UUID,
            BleConstants.ACK_CHARACTERISTIC_UUID,
            BleConstants.METADATA_CHARACTERISTIC_UUID
        );
    }

    public UUID serviceUuid() {
        return serviceUuid;
    }

    public UUID messageCharacteristicUuid() {
        return messageCharacteristicUuid;
    }

    public UUID ackCharacteristicUuid() {
        return ackCharacteristicUuid;
    }

    public UUID metadataCharacteristicUuid() {
        return metadataCharacteristicUuid;
    }
}
