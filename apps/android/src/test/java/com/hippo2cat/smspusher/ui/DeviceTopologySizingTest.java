package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DeviceTopologySizingTest {
    @Test
    public void narrowScreensShrinkLaptopBaseToFitWeightedNode() {
        DeviceTopologySizing.Layout sizing = DeviceTopologySizing.forScreenWidthDp(320);

        assertTrue(sizing.laptopBaseWidthDp <= sizing.nodeWidthDp);
        assertTrue(sizing.laptopFrameWidthDp <= sizing.laptopBaseWidthDp);
        assertTrue(sizing.connectorWidthDp < 82);
    }

    @Test
    public void midWidthScreensReduceConnectorBeforeShrinkingLaptop() {
        DeviceTopologySizing.Layout sizing = DeviceTopologySizing.forScreenWidthDp(390);

        assertEquals(114, sizing.nodeWidthDp);
        assertEquals(102, sizing.laptopFrameWidthDp);
        assertEquals(114, sizing.laptopBaseWidthDp);
        assertEquals(70, sizing.connectorWidthDp);
    }

    @Test
    public void wideScreensKeepOriginalHeroTopologyDimensions() {
        DeviceTopologySizing.Layout sizing = DeviceTopologySizing.forScreenWidthDp(430);

        assertEquals(82, sizing.connectorWidthDp);
        assertEquals(102, sizing.laptopFrameWidthDp);
        assertEquals(78, sizing.laptopFrameHeightDp);
        assertEquals(114, sizing.laptopBaseWidthDp);
        assertEquals(34, sizing.laptopIconSizeDp);
    }
}
