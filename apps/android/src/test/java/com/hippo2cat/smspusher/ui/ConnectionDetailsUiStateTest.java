package com.hippo2cat.smspusher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConnectionDetailsUiStateTest {
    @Test
    public void detailsUseTruthfulSecurityTextWithoutE2eeClaim() {
        ConnectionDetailsUiState state = ConnectionDetailsUiState.from(
            true,
            "192.0.2.5",
            "http://192.0.2.10:55515",
            true,
            "dev_12345678",
            "2026-06-12 18:00",
            "2026-06-12 17:00",
            "0.3.0",
            zhCopy()
        );

        assertEquals("已连接", state.title);
        assertEquals("XChaCha20-Poly1305", state.securityText);
        assertFalse(state.allText().contains("E2EE"));
        assertFalse(state.allText().contains("AES-256"));
    }

    @Test
    public void detailsSeparateListenerAndBonjourStatus() {
        ConnectionDetailsUiState state = ConnectionDetailsUiState.from(
            true,
            "192.0.2.5",
            "http://192.0.2.10:55515",
            false,
            "dev_12345678",
            "",
            "",
            "0.3.0",
            zhCopy()
        );

        assertTrue(state.allText().contains("后台监听\n运行中"));
        assertTrue(state.allText().contains("Bonjour 服务\n未发现"));
    }

    private static ConnectionDetailsUiState.Copy zhCopy() {
        return new ConnectionDetailsUiState.Copy(
            "连接状态",
            "已连接",
            "未连接",
            "本机 IP",
            "未获取",
            "Mac 地址",
            "未配对",
            "后台监听",
            "运行中",
            "未运行",
            "Bonjour 服务",
            "已激活",
            "未发现",
            "设备 ID",
            "未生成",
            "最后接收",
            "最后推送",
            "应用版本",
            "未知",
            "传输方式",
            "XChaCha20-Poly1305",
            "暂无"
        );
    }
}
