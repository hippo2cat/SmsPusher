package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceTopologyConnectorMotionTest {
    @Test
    public void unpairedConnectorStaysStatic() {
        DeviceTopologyConnectorMotion motion = DeviceTopologyConnectorMotion.from(false, false);

        assertEquals(DeviceTopologyConnectorMotion.Mode.STATIC, motion.mode);
        assertFalse(motion.animated);
    }

    @Test
    public void pairedButDisconnectedConnectorBreathes() {
        DeviceTopologyConnectorMotion motion = DeviceTopologyConnectorMotion.from(true, false);

        assertEquals(DeviceTopologyConnectorMotion.Mode.PULSE, motion.mode);
        assertTrue(motion.animated);
        assertEquals(1200, motion.durationMs);
    }

    @Test
    public void connectedConnectorShowsSweepHighlight() {
        DeviceTopologyConnectorMotion motion = DeviceTopologyConnectorMotion.from(true, true);

        assertEquals(DeviceTopologyConnectorMotion.Mode.SWEEP, motion.mode);
        assertTrue(motion.animated);
        assertEquals(1800, motion.durationMs);
        assertEquals(38, motion.segmentWidthDp);
    }
}
