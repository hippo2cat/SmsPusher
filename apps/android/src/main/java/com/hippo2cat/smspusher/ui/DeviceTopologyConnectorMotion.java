package com.hippo2cat.smspusher.ui;

public final class DeviceTopologyConnectorMotion {
    public enum Mode {
        STATIC,
        PULSE,
        SWEEP
    }

    public final Mode mode;
    public final boolean animated;
    public final long durationMs;
    public final int segmentWidthDp;

    private DeviceTopologyConnectorMotion(Mode mode, boolean animated, long durationMs, int segmentWidthDp) {
        this.mode = mode;
        this.animated = animated;
        this.durationMs = durationMs;
        this.segmentWidthDp = segmentWidthDp;
    }

    public static DeviceTopologyConnectorMotion from(boolean paired, boolean connected) {
        if (connected) return new DeviceTopologyConnectorMotion(Mode.SWEEP, true, 1800, 38);
        if (paired) return new DeviceTopologyConnectorMotion(Mode.PULSE, true, 1200, 0);
        return new DeviceTopologyConnectorMotion(Mode.STATIC, false, 0, 0);
    }
}
