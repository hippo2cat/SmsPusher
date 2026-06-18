package com.hippo2cat.smspusher.ui;

import com.hippo2cat.smspusher.sms.MessageEvent;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class MessageActivityUiStateTest {
    @Test
    public void mapsStatusesToActivityLabels() {
        MessageActivityUiState state = MessageActivityUiState.from(Arrays.asList(
            event("msg_1", MessageEvent.Status.FORWARDED),
            event("msg_2", MessageEvent.Status.FAILED),
            event("msg_3", MessageEvent.Status.PENDING)
        ), new MessageActivityUiState.Copy("最近活动", "暂无最近活动"), MessageListUiStateTest.zhCopy());

        assertEquals("已推送", state.rows.get(0).statusText);
        assertEquals("推送失败", state.rows.get(1).statusText);
        assertEquals("等待推送", state.rows.get(2).statusText);
    }

    private static MessageEvent event(String id, MessageEvent.Status status) {
        return new MessageEvent(id, "TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z", 1, "dev_1", MessageEvent.Source.BROADCAST, status, "macOS", null, null, null, 1L, 1L);
    }
}
