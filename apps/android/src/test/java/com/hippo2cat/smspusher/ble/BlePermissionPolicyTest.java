package com.hippo2cat.smspusher.ble;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BlePermissionPolicyTest {
    @Test
    public void androidTwelveAndNewerRequiresNearbyDeviceRuntimePermissions() {
        List<String> api31 = BlePermissionPolicy.requiredRuntimePermissions(31);
        List<String> api35 = BlePermissionPolicy.requiredRuntimePermissions(35);

        assertEquals(2, api31.size());
        assertTrue(api31.contains("android.permission.BLUETOOTH_ADVERTISE"));
        assertTrue(api31.contains("android.permission.BLUETOOTH_CONNECT"));
        assertEquals(api31, api35);
    }

    @Test
    public void androidElevenAndOlderRequiresNoNearbyDeviceRuntimePermission() {
        assertTrue(BlePermissionPolicy.requiredRuntimePermissions(30).isEmpty());
        assertTrue(BlePermissionPolicy.requiredRuntimePermissions(26).isEmpty());
    }
}
