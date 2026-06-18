package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HomeUiStateTest {
    @Test
    public void readyStateShowsConnectedListeningSummary() {
        HomeUiState state = HomeUiState.from(
            true,
            true,
            true,
            false,
            false,
            true,
            "jbz-Mac",
            "http://192.168.12.3:55515",
            0,
            true,
            zhCopy()
        );

        assertEquals("已连接 jbz-Mac，正在监听。", state.summary);
        assertEquals("新短信只会通过本地网络加密转发到已配对的电脑。", state.privacyText);
        assertEquals("清除并重配", state.rePairActionLabel);
        assertTrue(state.rePairActionEnabled);
        assertEquals("短信权限", state.smsPermission.title);
        assertEquals("已启用", state.smsPermission.status);
        assertEquals("网络短信读取权限", state.inboxPermission.title);
        assertEquals("已启用", state.inboxPermission.status);
        assertEquals("通知权限", state.notificationPermission.title);
        assertEquals("已启用", state.notificationPermission.status);
        assertFalse(state.showMiuiPermission);
        assertFalse(state.needsAttention);
    }

    @Test
    public void missingPermissionsBlockForwardingWithActions() {
        HomeUiState state = HomeUiState.from(
            false,
            false,
            false,
            true,
            false,
            false,
            "",
            "",
            0,
            false,
            zhCopy()
        );

        assertEquals("需要短信权限。", state.summary);
        assertEquals("需要授权", state.smsPermission.status);
        assertEquals("去开启", state.smsPermission.actionLabel);
        assertEquals("需要授权", state.inboxPermission.status);
        assertEquals("去开启", state.inboxPermission.actionLabel);
        assertEquals("需要授权", state.notificationPermission.status);
        assertEquals("去开启", state.notificationPermission.actionLabel);
        assertTrue(state.showMiuiPermission);
        assertEquals("需要授权", state.miuiPermission.status);
        assertEquals("打开 MIUI 设置", state.miuiPermission.actionLabel);
        assertTrue(state.needsAttention);
    }

    @Test
    public void queuedStateMentionsMacOfflineAndPendingMessages() {
        HomeUiState state = HomeUiState.from(
            true,
            true,
            true,
            false,
            false,
            true,
            "Studio Mac",
            "http://192.168.12.3:55515",
            3,
            false,
            zhCopy()
        );

        assertEquals("Mac 离线，短信会暂存到队列。", state.summary);
        assertEquals("待转发 3", state.pendingText);
        assertEquals("监听受阻", state.listenerText);
        assertTrue(state.needsAttention);
    }

    @Test
    public void queuedMessagesDoNotMarkRunningMacOffline() {
        HomeUiState state = HomeUiState.from(
            true,
            true,
            true,
            false,
            false,
            true,
            "Studio Mac",
            "http://192.168.12.3:55515",
            3,
            true,
            zhCopy()
        );

        assertEquals("已连接 Studio Mac，正在监听。", state.summary);
        assertEquals("待转发 3", state.pendingText);
        assertEquals("监听中", state.listenerText);
        assertFalse(state.needsAttention);
    }

    @Test
    public void unpairedStatePromptsPairing() {
        HomeUiState state = HomeUiState.from(
            true,
            true,
            true,
            false,
            false,
            false,
            "",
            "",
            0,
            false,
            zhCopy()
        );

        assertEquals("需要配对。", state.summary);
        assertEquals("未配对 Mac", state.macText);
        assertEquals("清除并重配", state.rePairActionLabel);
        assertFalse(state.rePairActionEnabled);
        assertTrue(state.needsAttention);
    }

    @Test
    public void missingInboxReadPermissionBlocksNetworkSmsFallback() {
        HomeUiState state = HomeUiState.from(
            true,
            false,
            true,
            false,
            false,
            true,
            "jbz-Mac",
            "http://192.168.12.3:55515",
            0,
            true,
            zhCopy()
        );

        assertEquals("需要网络短信读取权限。", state.summary);
        assertEquals("网络短信读取权限", state.inboxPermission.title);
        assertEquals("需要授权", state.inboxPermission.status);
        assertTrue(state.needsAttention);
    }

    private static HomeUiState.Copy zhCopy() {
        return new HomeUiState.Copy(
            "新短信只会通过本地网络加密转发到已配对的电脑。",
            "已启用",
            "需要授权",
            "短信权限",
            "允许接收新短信并转发到 Mac。",
            "需要授权后才能接收新短信。",
            "去开启",
            "网络短信读取权限",
            "用于读取短信库中不触发广播的网络短信。",
            "需要授权后才能同步网络短信。",
            "通知权限",
            "用于保持前台监听服务可见。",
            "当前 Android 版本需要通知权限才能稳定监听。",
            "MIUI 通知类短信权限",
            "允许 MIUI 分发通知类短信广播。",
            "MIUI 上接收通知类短信需要开启这个私有权限。",
            "打开 MIUI 设置",
            "需要短信权限。",
            "需要网络短信读取权限。",
            "需要通知权限。",
            "需要 MIUI 通知类短信权限。",
            "需要配对。",
            "Mac 离线，短信会暂存到队列。",
            "已连接 {name}，正在监听。",
            "未配对 Mac",
            "待转发 {count}",
            "监听中",
            "监听受阻",
            "清除并重配"
        );
    }
}
