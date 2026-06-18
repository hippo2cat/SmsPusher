package com.hippo2cat.smspusher.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PairingStatusTextTest {
    @Test
    public void showsPairedStatusWhenTokenAndMacUrlExist() {
        assertEquals(
            "Paired with http://192.166.12.3:55515",
            PairingStatusText.from(true, "http://192.166.12.3:55515", copy())
        );
    }

    @Test
    public void showsDiscoveryStatusWhenPairingIsIncomplete() {
        assertEquals("Discovering Macs on local network", PairingStatusText.from(false, "http://192.166.12.3:55515", copy()));
        assertEquals("Discovering Macs on local network", PairingStatusText.from(true, "", copy()));
    }

    private static PairingStatusText.Copy copy() {
        return new PairingStatusText.Copy(
            "Paired with {baseUrl}",
            "Discovering Macs on local network"
        );
    }
}
