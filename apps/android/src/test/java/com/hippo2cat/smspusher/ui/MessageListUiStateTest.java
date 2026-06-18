package com.hippo2cat.smspusher.ui;

import com.hippo2cat.smspusher.sms.MessageEvent;
import com.hippo2cat.smspusher.sms.PendingMessage;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MessageListUiStateTest {
    @Test
    public void formatsPendingMessagesAndEnablesRetryWhenPaired() {
        MessageListUiState state = MessageListUiState.from(
            Arrays.asList(new PendingMessage("msg_1", "TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z", MessageEvent.Status.PENDING, null)),
            true,
            zhCopy()
        );

        assertEquals("1 条短信待推送", state.title);
        assertEquals("1 条待处理项目", state.pendingListTitle);
        assertEquals("立即推送", state.primaryActionLabel);
        assertEquals("查看短信", state.viewMessagesLabel);
        assertEquals("TEST-SENDER", state.rows.get(0).sender);
        assertEquals("test body 001", state.rows.get(0).bodyPreview);
        assertEquals("等待推送", state.rows.get(0).statusText);
        assertTrue(state.retryEnabled);
        assertTrue(state.viewMessagesVisible);
        assertFalse(state.failureVisible);
    }

    @Test
    public void emptyPendingListDisablesRetry() {
        MessageListUiState state = MessageListUiState.from(java.util.Collections.emptyList(), true, zhCopy());

        assertEquals("所有短信已推送", state.title);
        assertEquals("0 条待处理项目", state.pendingListTitle);
        assertEquals("立即推送", state.primaryActionLabel);
        assertFalse(state.viewMessagesVisible);
        assertFalse(state.failureVisible);
        assertTrue(state.retryEnabled);
    }

    @Test
    public void failedPendingMessagesUseFailureStatusCard() {
        MessageListUiState state = MessageListUiState.from(
            Arrays.asList(new PendingMessage("msg_2", "TEST-SENDER", "test body 002", "2026-06-12T09:00:00Z", MessageEvent.Status.FAILED, "服务器连接超时。请检查您的网络并重试。")),
            true,
            zhCopy()
        );

        assertEquals("推送失败", state.title);
        assertEquals("立即重试", state.primaryActionLabel);
        assertEquals("错误详情", state.failureTitle);
        assertEquals("服务器连接超时。请检查您的网络并重试。", state.failureDetail);
        assertTrue(state.failureVisible);
        assertFalse(state.viewMessagesVisible);
        assertTrue(state.retryEnabled);
    }

    static MessageListUiState.Copy zhCopy() {
        return new MessageListUiState.Copy(
            "未知发件人",
            "无正文",
            "已推送",
            "推送失败",
            "等待推送",
            "所有短信已推送",
            "{count} 条短信待推送",
            "{count} 条待处理项目",
            "立即推送",
            "立即重试",
            "查看短信",
            "错误详情",
            "服务器连接超时。请检查您的网络并重试。"
        );
    }
}
