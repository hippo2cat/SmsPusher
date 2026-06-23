package com.hippo2cat.smspusher.net;

import com.hippo2cat.smspusher.auth.PairingCredential;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public final class SecureMessageClientTest {
    @Test
    public void secureMessageUsesSecureMessagesRouteWithoutBearerToken() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"status\":\"accepted\"}");
        SecureMessageClient client = new SecureMessageClient("http://mac.local:55515", transport);
        PairingCredential credential = PairingCredential.v2(
            "dev_1",
            "key_1",
            "c2VjcmV0",
            5L,
            "Test Desktop",
            Instant.parse("2026-06-15T08:00:00Z")
        );

        PairingCredential updated = client.sendMessage(credential, "{\"messageId\":\"msg_1\",\"body\":\"hello\"}");

        assertEquals(6L, updated.nextCounter);
        assertEquals("/secure/messages", transport.requests.get(0).path);
        assertFalse(transport.requests.get(0).headers.containsKey("Authorization"));
        assertTrue(transport.requests.get(0).body.contains("\"counter\":5"));
        assertFalse(transport.requests.get(0).body.contains("hello"));
    }

    @Test
    public void replayDetectedIsNotPairingRequired() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(409, "{\"error\":\"replay_detected\"}");
        SecureMessageClient client = new SecureMessageClient("http://mac.local:55515", transport);
        PairingCredential credential = PairingCredential.v2(
            "dev_1",
            "key_1",
            "c2VjcmV0",
            5L,
            "Test Desktop",
            Instant.parse("2026-06-15T08:00:00Z")
        );

        try {
            client.sendMessage(credential, "{\"messageId\":\"msg_1\",\"body\":\"hello\"}");
            fail("Expected IOException");
        } catch (SmsBridgeClient.PairingRequiredException unexpected) {
            fail("replay_detected must not clear pairing");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("replay_detected"));
        }
    }
}
