package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionTestUiStateTest {
    @Test
    public void idleStateShowsEnabledTestButtonWithoutFeedback() {
        ConnectionTestUiState state = ConnectionTestUiState.idle(zhCopy());

        assertEquals("测试连接", state.buttonLabel);
        assertTrue(state.buttonEnabled);
        assertFalse(state.feedbackVisible);
        assertFalse(state.feedbackSuccess);
        assertEquals("", state.feedbackText);
    }

    @Test
    public void runningStateDisablesButtonAndShowsProgressFeedback() {
        ConnectionTestUiState state = ConnectionTestUiState.running(zhCopy());

        assertEquals("测试中...", state.buttonLabel);
        assertFalse(state.buttonEnabled);
        assertTrue(state.feedbackVisible);
        assertFalse(state.feedbackSuccess);
        assertEquals("正在测试 Mac 连接...", state.feedbackText);
    }

    @Test
    public void successStateShowsReachableMacFeedback() {
        ConnectionTestUiState state = ConnectionTestUiState.success("http://10.0.2.2:55515", zhCopy());

        assertEquals("测试连接", state.buttonLabel);
        assertTrue(state.buttonEnabled);
        assertTrue(state.feedbackVisible);
        assertTrue(state.feedbackSuccess);
        assertEquals("连接正常 · 10.0.2.2:55515", state.feedbackText);
    }

    @Test
    public void failureStateShowsRetryButtonAndErrorFeedback() {
        ConnectionTestUiState state = ConnectionTestUiState.failure("连接失败：timeout", zhCopy());

        assertEquals("重试连接", state.buttonLabel);
        assertTrue(state.buttonEnabled);
        assertTrue(state.feedbackVisible);
        assertFalse(state.feedbackSuccess);
        assertEquals("连接失败：timeout", state.feedbackText);
    }

    private static ConnectionTestUiState.Copy zhCopy() {
        return new ConnectionTestUiState.Copy(
            "测试连接",
            "测试中...",
            "正在测试 Mac 连接...",
            "连接正常 · {endpoint}",
            "连接失败",
            "重试连接",
            "未知 Mac"
        );
    }
}
