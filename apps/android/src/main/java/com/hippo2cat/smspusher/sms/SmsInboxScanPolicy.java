package com.hippo2cat.smspusher.sms;

public final class SmsInboxScanPolicy {
    public static final long POLL_INTERVAL_MS = 5_000L;
    public static final long QUEUE_RETRY_INTERVAL_MS = 60_000L;
    public static final long INITIAL_LOOKBACK_MS = 10 * 60 * 1000L;
    public static final long RESCAN_OVERLAP_MS = 30 * 1000L;
    public static final int MAX_ROWS_PER_SCAN = 20;

    private SmsInboxScanPolicy() {}

    public static long lowerBoundMillis(long lastScanAtMillis, long nowMillis) {
        long initialLowerBound = Math.max(0L, nowMillis - INITIAL_LOOKBACK_MS);
        if (lastScanAtMillis <= 0L) return initialLowerBound;
        long overlappedLowerBound = Math.max(0L, lastScanAtMillis - RESCAN_OVERLAP_MS);
        return Math.max(initialLowerBound, overlappedLowerBound);
    }
}
