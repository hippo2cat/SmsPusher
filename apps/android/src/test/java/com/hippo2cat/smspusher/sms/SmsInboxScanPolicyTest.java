package com.hippo2cat.smspusher.sms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SmsInboxScanPolicyTest {
    @Test
    public void usesFiveSecondPollingWithBoundedRecentScan() {
        assertEquals(5_000L, SmsInboxScanPolicy.POLL_INTERVAL_MS);
        assertEquals(20, SmsInboxScanPolicy.MAX_ROWS_PER_SCAN);
        assertEquals(10 * 60 * 1000L, SmsInboxScanPolicy.INITIAL_LOOKBACK_MS);
        assertEquals(30 * 1000L, SmsInboxScanPolicy.RESCAN_OVERLAP_MS);
    }

    @Test
    public void lowerBoundUsesInitialWindowThenSmallOverlap() {
        long now = 200_000L;

        assertEquals(0L, SmsInboxScanPolicy.lowerBoundMillis(0L, now));
        assertEquals(130_000L, SmsInboxScanPolicy.lowerBoundMillis(160_000L, now));
        assertEquals(0L, SmsInboxScanPolicy.lowerBoundMillis(10_000L, now));
    }

    @Test
    public void staleWatermarkCannotExpandPastInitialLookbackWindow() {
        long now = 1780907000000L;

        assertEquals(
            now - SmsInboxScanPolicy.INITIAL_LOOKBACK_MS,
            SmsInboxScanPolicy.lowerBoundMillis(now - 60 * 60 * 1000L, now)
        );
    }
}
