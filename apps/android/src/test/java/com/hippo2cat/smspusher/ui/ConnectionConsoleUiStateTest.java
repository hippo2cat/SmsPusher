package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionConsoleUiStateTest {
    @Test
    public void healthyStateShowsOnlineMacTokenAndEmptyQueue() {
        ConnectionConsoleUiState state = ConnectionConsoleUiState.from(
            true, true, true, false, true, true,
            "http://192.0.2.10:55515",
            0,
            true,
            Instant.parse("2026-06-08T02:00:00Z"),
            Instant.parse("2026-06-08T01:00:00Z"),
            zhCopy()
        );

        assertEquals("在线", state.stateLabel);
        assertEquals("Mac 在线 · 55515", state.statusTitle);
        assertEquals("Token 有效 1h", state.tokenText);
        assertEquals("队列 0", state.queueText);
        assertEquals("连接", state.connectionTabLabel);
        assertEquals("权限", state.permissionTabLabel);
        assertEquals("队列", state.queueTabLabel);
        assertFalse(state.needsAttention);
    }

    @Test
    public void missingSmsPermissionShowsPermissionLimitedState() {
        ConnectionConsoleUiState state = ConnectionConsoleUiState.from(
            false, false, true, false, true, false,
            "",
            0,
            false,
            null,
            Instant.parse("2026-06-08T01:00:00Z"),
            zhCopy()
        );

        assertEquals("权限受限", state.stateLabel);
        assertEquals("权限受限", state.statusTitle);
        assertEquals("需要授权", state.tokenText);
        assertTrue(state.needsAttention);
    }

    @Test
    public void missingInboxReadPermissionShowsPermissionLimitedState() {
        ConnectionConsoleUiState state = ConnectionConsoleUiState.from(
            true, false, true, false, true, false,
            "",
            0,
            false,
            null,
            Instant.parse("2026-06-08T01:00:00Z"),
            zhCopy()
        );

        assertEquals("权限受限", state.stateLabel);
        assertEquals("权限受限", state.statusTitle);
        assertEquals("需要授权", state.tokenText);
        assertTrue(state.needsAttention);
    }

    @Test
    public void unpairedStateRequiresPairing() {
        ConnectionConsoleUiState state = ConnectionConsoleUiState.from(
            true, true, true, false, true, false,
            "",
            0,
            false,
            null,
            Instant.parse("2026-06-08T01:00:00Z"),
            zhCopy()
        );

        assertEquals("需要配对", state.stateLabel);
        assertEquals("需要配对", state.statusTitle);
        assertEquals("需要重新配对", state.tokenText);
        assertTrue(state.needsAttention);
    }

    @Test
    public void queuedStateShowsMacUnreachableAttention() {
        ConnectionConsoleUiState state = ConnectionConsoleUiState.from(
            true, true, true, false, true, true,
            "http://192.0.2.10:55515",
            3,
            false,
            Instant.parse("2026-06-08T02:00:00Z"),
            Instant.parse("2026-06-08T01:00:00Z"),
            zhCopy()
        );

        assertEquals("Mac 不可达", state.stateLabel);
        assertEquals("Mac 离线 · 55515", state.statusTitle);
        assertEquals("队列 3", state.queueText);
        assertTrue(state.needsAttention);
    }

    @Test
    public void queuedMessagesDoNotMarkRunningMacOffline() {
        ConnectionConsoleUiState state = ConnectionConsoleUiState.from(
            true, true, true, false, true, true,
            "http://192.0.2.10:55515",
            3,
            true,
            Instant.parse("2026-06-08T02:00:00Z"),
            Instant.parse("2026-06-08T01:00:00Z"),
            zhCopy()
        );

        assertEquals("在线", state.stateLabel);
        assertEquals("Mac 在线 · 55515", state.statusTitle);
        assertEquals("队列 3", state.queueText);
        assertFalse(state.needsAttention);
    }

    private static ConnectionConsoleUiState.Copy zhCopy() {
        return new ConnectionConsoleUiState.Copy(
            "连接",
            "权限",
            "队列",
            "队列 {count}",
            "权限受限",
            "需要授权",
            "需要配对",
            "需要重新配对",
            "Mac 不可达",
            "Mac 离线 · {port}",
            "在线",
            "Mac 在线 · {port}",
            "需要重新配对",
            "Token 有效 <1h",
            "Token 有效 {hours}h",
            "Token 已过期"
        );
    }
}
