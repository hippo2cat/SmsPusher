package com.hippo2cat.smspusher.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ForwardingStatsTextTest {
    @Test
    public void formatsMissingTimestampsAsDashes() {
        ForwardingStatsText text = ForwardingStatsText.from(0, "", "", "", zhCopy());

        assertEquals("待转发 0", text.pendingLine);
        assertEquals("最近收到 -", text.lastReceivedLine);
        assertEquals("最近转发 -", text.lastForwardedLine);
        assertEquals("失败原因 -", text.lastFailureLine);
    }

    @Test
    public void formatsFailureReasonWhenPresent() {
        ForwardingStatsText text = ForwardingStatsText.from(2, "2026-06-08 14:01", "2026-06-08 14:02", "timeout", zhCopy());

        assertEquals("待转发 2", text.pendingLine);
        assertEquals("最近收到 2026-06-08 14:01", text.lastReceivedLine);
        assertEquals("最近转发 2026-06-08 14:02", text.lastForwardedLine);
        assertEquals("失败原因 timeout", text.lastFailureLine);
    }

    private static ForwardingStatsText.Copy zhCopy() {
        return new ForwardingStatsText.Copy(
            "待转发 {count}",
            "最近收到 {value}",
            "最近转发 {value}",
            "失败原因 {value}"
        );
    }
}
