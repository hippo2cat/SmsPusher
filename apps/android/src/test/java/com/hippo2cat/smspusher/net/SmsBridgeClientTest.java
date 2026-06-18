package com.hippo2cat.smspusher.net;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.TokenBundle;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SmsBridgeClientTest {
    @Test
    public void healthCheckUsesHealthEndpoint() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"status\":\"ok\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);

        boolean reachable = client.testConnection();

        assertTrue(reachable);
        assertEquals("GET", transport.requests.get(0).method);
        assertEquals("/health", transport.requests.get(0).path);
    }

    @Test
    public void fetchPairingSessionUsesCurrentSecureSessionEndpoint() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"serviceName\":\"SmsPusher Test\",\"secureProtocol\":\"lan-secure-v2\",\"pairingSessionId\":\"session_new\",\"pairingExpiresAt\":\"2026-06-05T08:00:30Z\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);

        SmsBridgeClient.PairingSession session = client.fetchPairingSession();

        assertEquals("SmsPusher Test", session.serviceName);
        assertEquals("lan-secure-v2", session.secureProtocol);
        assertEquals("session_new", session.pairingSessionId);
        assertEquals("2026-06-05T08:00:30Z", session.pairingExpiresAt);
        assertEquals("GET", transport.requests.get(0).method);
        assertEquals("/pair/v2/session", transport.requests.get(0).path);
    }

    @Test
    public void verifyPairingUsesAuthenticatedStatusEndpoint() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"status\":\"authorized\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);
        TokenBundle token = new TokenBundle("dev_1", "access1", Instant.parse("2026-06-06T08:00:00Z"), "refresh1", Instant.parse("2026-09-03T08:00:00Z"));

        TokenBundle verified = client.verifyPairing(token);

        assertEquals("access1", verified.accessToken);
        assertEquals("POST", transport.requests.get(0).method);
        assertEquals("http://mac.local:54321", transport.requests.get(0).baseUrl);
        assertEquals("/auth/check", transport.requests.get(0).path);
        assertEquals("Bearer access1", transport.requests.get(0).headers.get("Authorization"));
        assertTrue(transport.requests.get(0).body.contains("\"deviceId\":\"dev_1\""));
    }

    @Test
    public void revokedPairingRequiresPairingDuringVerification() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(401, "{\"error\":\"invalid_token\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);
        TokenBundle token = new TokenBundle("dev_1", "access1", Instant.parse("2026-06-06T08:00:00Z"), "refresh1", Instant.parse("2026-09-03T08:00:00Z"));

        try {
            client.verifyPairing(token);
            fail("Expected PairingRequiredException");
        } catch (SmsBridgeClient.PairingRequiredException expected) {
            assertEquals("invalid_token", expected.reason);
        }
    }

    @Test
    public void verifyPairingWithSecureCredentialUsesSecureAuthCheckEndpoint() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"status\":\"authorized\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);
        PairingCredential credential = PairingCredential.v2(
            "dev_1",
            "key_1",
            "c2VjcmV0",
            3L,
            "Test Desktop",
            Instant.parse("2026-06-15T08:00:00Z")
        );

        PairingCredential verified = client.verifyPairing(credential);

        assertEquals(4L, verified.nextCounter);
        assertEquals("/secure/auth/check", transport.requests.get(0).path);
        assertTrue(transport.requests.get(0).headers.containsKey("Content-Type"));
    }

    @Test
    public void pairReturnsTokenBundle() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"deviceId\":\"dev_1\",\"accessToken\":\"access\",\"accessTokenExpiresAt\":\"2026-06-06T08:00:00Z\",\"refreshToken\":\"refresh\",\"refreshTokenExpiresAt\":\"2026-09-03T08:00:00Z\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);

        TokenBundle token = client.pair("123456", "Test Android Device", "android-client-1");

        assertEquals("dev_1", token.deviceId);
        assertEquals("/pair", transport.requests.get(0).path);
        assertTrue(transport.requests.get(0).body.contains("\"pairingCode\":\"123456\""));
        assertTrue(transport.requests.get(0).body.contains("\"clientInstanceId\":\"android-client-1\""));
    }

    @Test
    public void tokenExpiredRefreshesAndRetriesMessage() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(401, "{\"error\":\"token_expired\"}");
        transport.enqueue(200, "{\"accessToken\":\"access2\",\"accessTokenExpiresAt\":\"2026-06-07T08:00:00Z\",\"refreshToken\":\"refresh2\",\"refreshTokenExpiresAt\":\"2026-09-04T08:00:00Z\"}");
        transport.enqueue(200, "{\"status\":\"accepted\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);
        TokenBundle token = new TokenBundle("dev_1", "access1", Instant.parse("2026-06-06T08:00:00Z"), "refresh1", Instant.parse("2026-09-03T08:00:00Z"));

        TokenBundle updated = client.sendMessage(token, "{\"messageId\":\"msg\",\"deviceId\":\"dev_1\"}");

        assertEquals("access2", updated.accessToken);
        assertEquals("refresh2", updated.refreshToken);
        assertEquals(3, transport.requests.size());
        assertEquals("/messages", transport.requests.get(0).path);
        assertEquals("/auth/refresh", transport.requests.get(1).path);
        assertEquals("/messages", transport.requests.get(2).path);
    }

    @Test
    public void invalidRefreshTokenRequiresPairing() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(401, "{\"error\":\"token_expired\"}");
        transport.enqueue(401, "{\"error\":\"invalid_refresh_token\"}");
        SmsBridgeClient client = new SmsBridgeClient("http://mac.local:54321", transport);
        TokenBundle token = new TokenBundle("dev_1", "access1", Instant.parse("2026-06-06T08:00:00Z"), "refresh1", Instant.parse("2026-09-03T08:00:00Z"));

        try {
            client.sendMessage(token, "{\"messageId\":\"msg\",\"deviceId\":\"dev_1\"}");
            fail("Expected PairingRequiredException");
        } catch (SmsBridgeClient.PairingRequiredException expected) {
            assertEquals("invalid_refresh_token", expected.reason);
        }
    }
}
