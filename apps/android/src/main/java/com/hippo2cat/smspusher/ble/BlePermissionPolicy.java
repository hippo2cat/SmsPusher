package com.hippo2cat.smspusher.ble;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BlePermissionPolicy {
    public static final String BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE";
    public static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private BlePermissionPolicy() {}

    public static List<String> requiredRuntimePermissions(int sdkInt) {
        if (sdkInt < 31) return Collections.emptyList();
        return Collections.unmodifiableList(Arrays.asList(BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT));
    }
}
