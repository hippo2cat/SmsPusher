package com.hippo2cat.smspusher.auth;

import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SecureTokenStoreTest {
    @Test
    public void v2CredentialJsonRoundTripsSecureFields() throws Exception {
        PairingCredential credential = PairingCredential.v2(
            "dev_1",
            "key_1",
            "fixture-device-key-base64",
            7L,
            "Test Desktop",
            Instant.parse("2026-06-15T08:00:00Z")
        );

        JSONObject json = credential.toJson();
        PairingCredential restored = PairingCredential.fromJson(json);

        assertEquals(2, restored.protocolVersion);
        assertEquals("dev_1", restored.deviceId);
        assertEquals("key_1", restored.keyId);
        assertEquals("fixture-device-key-base64", restored.deviceSecret);
        assertEquals(7L, restored.nextCounter);
        assertEquals("Test Desktop", restored.pairedDesktopName);
        assertEquals(Instant.parse("2026-06-15T08:00:00Z"), restored.pairedAt);
        assertFalse(json.toString().contains("accessToken"));
    }

    @Test
    public void v1TokenJsonIsDetectedAsLegacyCredential() throws Exception {
        JSONObject json = new JSONObject();
        json.put("deviceId", "dev_1");
        json.put("accessToken", "access");
        json.put("accessTokenExpiresAt", "2026-06-16T08:00:00Z");
        json.put("refreshToken", "refresh");
        json.put("refreshTokenExpiresAt", "2026-09-16T08:00:00Z");

        PairingCredential restored = PairingCredential.fromJson(json);

        assertEquals(1, restored.protocolVersion);
        assertTrue(restored.requiresSecureRePairing());
    }
}
