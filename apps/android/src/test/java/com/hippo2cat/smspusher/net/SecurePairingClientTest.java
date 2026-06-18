package com.hippo2cat.smspusher.net;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.crypto.SmsPusherCrypto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SecurePairingClientTest {
    @Test
    public void securePairingNeverSendsPairingCodeInHttpBody() throws Exception {
        FakeTransport transport = new FakeTransport();
        String serverConfirm = Base64.getEncoder().encodeToString("host-jvm-server-confirm".getBytes(StandardCharsets.UTF_8));
        transport.enqueue(200, "{"
            + "\"protocol\":\"lan-secure-v2\","
            + "\"pairingSessionId\":\"pair_session_1\","
            + "\"serverPakeMessage\":\"c2VydmVyLW1lc3NhZ2U=\","
            + "\"serverConfirm\":\"" + serverConfirm + "\","
            + "\"keyId\":\"pair_key_1\","
            + "\"expiresAt\":\"2026-06-15T08:00:30Z\""
            + "}");
        String pairResponseJson = "{"
            + "\"deviceId\":\"dev_1\","
            + "\"keyId\":\"key_1\","
            + "\"deviceSecret\":\"c2VjcmV0\","
            + "\"desktopDeviceName\":\"Test Desktop\","
            + "\"pairedAt\":\"2026-06-15T08:00:00Z\""
            + "}";
        String responseEnvelope = SmsPusherCrypto.sealEnvelopeForRequest(
            "cGFpcmluZy1rZXk=",
            "pairing-session",
            "pair_key_1",
            1L,
            "SmsPusher\nlan-secure-v2\nPOST\n/pair/v2/finish/response\npairing-session\npair_key_1\n1",
            pairResponseJson
        );
        transport.enqueue(200, "{\"encryptedPairResponse\":" + responseEnvelope + "}");
        SecurePairingClient client = new SecurePairingClient("http://mac.local:55515", transport);

        PairingCredential credential = client.pair("123456", "pair_session_1", "Test Android Device", "android-client-1");

        assertEquals("dev_1", credential.deviceId);
        assertEquals("key_1", credential.keyId);
        assertEquals(1L, credential.nextCounter);
        assertEquals("Test Desktop", credential.pairedDesktopName);
        assertEquals("/pair/v2/start", transport.requests.get(0).path);
        assertEquals("/pair/v2/finish", transport.requests.get(1).path);
        assertFalse(transport.requests.get(0).body.contains("123456"));
        assertFalse(transport.requests.get(1).body.contains("123456"));
        assertTrue(transport.requests.get(0).body.contains("\"protocol\":\"lan-secure-v2\""));
        assertTrue(transport.requests.get(1).body.contains("\"encryptedPairRequest\""));
    }
}
