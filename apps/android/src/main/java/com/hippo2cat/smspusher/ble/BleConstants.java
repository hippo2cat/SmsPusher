package com.hippo2cat.smspusher.ble;

import java.util.UUID;

public final class BleConstants {
    public static final UUID SERVICE_UUID = UUID.fromString("2f89b10a-9f3d-4f2e-9f7a-3d3b7b5f0a11");
    public static final UUID MESSAGE_CHARACTERISTIC_UUID = UUID.fromString("2f89b10b-9f3d-4f2e-9f7a-3d3b7b5f0a11");
    public static final UUID ACK_CHARACTERISTIC_UUID = UUID.fromString("2f89b10c-9f3d-4f2e-9f7a-3d3b7b5f0a11");
    public static final UUID METADATA_CHARACTERISTIC_UUID = UUID.fromString("2f89b10d-9f3d-4f2e-9f7a-3d3b7b5f0a11");

    private BleConstants() {}
}
