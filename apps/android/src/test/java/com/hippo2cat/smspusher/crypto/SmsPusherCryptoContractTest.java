package com.hippo2cat.smspusher.crypto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SmsPusherCryptoContractTest {
    @Test
    public void secureEnvelopeValueObjectSerializesExpectedFields() throws Exception {
        SecureEnvelope envelope = new SecureEnvelope(2, "dev_1", "key_1", 9L, "nonce", "ciphertext");

        String json = envelope.toJson().toString();
        SecureEnvelope restored = SecureEnvelope.fromJson(json);

        assertEquals(2, restored.version);
        assertEquals("dev_1", restored.deviceId);
        assertEquals("key_1", restored.keyId);
        assertEquals(9L, restored.counter);
        assertTrue(json.contains("\"ciphertext\":\"ciphertext\""));
    }
}
