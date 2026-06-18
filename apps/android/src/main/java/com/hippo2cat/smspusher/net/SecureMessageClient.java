package com.hippo2cat.smspusher.net;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.crypto.SmsPusherCrypto;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class SecureMessageClient {
    private static final String PROTOCOL_NAME = "lan-secure-v2";

    private final String baseUrl;
    private final HttpTransport transport;

    public SecureMessageClient(String baseUrl, HttpTransport transport) {
        this.baseUrl = baseUrl;
        this.transport = transport;
    }

    public PairingCredential sendMessage(PairingCredential credential, String messageJson)
        throws IOException, SmsBridgeClient.PairingRequiredException {
        return postSecure(
            credential,
            "/secure/messages",
            messageJson,
            "Secure SMS send failed with status "
        );
    }

    public PairingCredential verifyPairing(PairingCredential credential)
        throws IOException, SmsBridgeClient.PairingRequiredException {
        return postSecure(
            credential,
            "/secure/auth/check",
            "{\"reason\":\"foreground-status-check\"}",
            "Secure auth check failed with status "
        );
    }

    private PairingCredential postSecure(
        PairingCredential credential,
        String path,
        String plaintextJson,
        String failurePrefix
    ) throws IOException, SmsBridgeClient.PairingRequiredException {
        long counter = credential.nextCounter;
        String aad = aad(path, credential.deviceId, credential.keyId, counter);
        String envelope = SmsPusherCrypto.sealEnvelopeForRequest(
            credential.deviceSecret,
            credential.deviceId,
            credential.keyId,
            counter,
            aad,
            plaintextJson
        );
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        HttpTransport.Response response = transport.post(baseUrl, path, headers, envelope);
        PairingCredential incremented = credential.withNextCounter(counter + 1L);
        if (response.status == 200) return incremented;
        if (response.status == 401 || response.status == 409) {
            throw new SmsBridgeClient.PairingRequiredException(SmsBridgeClient.errorCode(response.body));
        }
        throw new IOException(failurePrefix + response.status);
    }

    private static String aad(String path, String deviceId, String keyId, long counter) {
        return "SmsPusher\n" + PROTOCOL_NAME + "\nPOST\n" + path + "\n" + deviceId + "\n" + keyId + "\n" + counter;
    }
}
