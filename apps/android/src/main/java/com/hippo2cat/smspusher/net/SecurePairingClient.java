package com.hippo2cat.smspusher.net;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.crypto.SmsPusherCrypto;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class SecurePairingClient {
    private static final String PROTOCOL_NAME = "lan-secure-v2";
    private static final String PAIRING_SESSION_DEVICE_ID = "pairing-session";

    private final String baseUrl;
    private final HttpTransport transport;
    private final String desktopServiceName;
    private final String pairingExpiresAt;

    public SecurePairingClient(String baseUrl, HttpTransport transport) {
        this(baseUrl, "", "", transport);
    }

    public SecurePairingClient(
        String baseUrl,
        String desktopServiceName,
        String pairingExpiresAt,
        HttpTransport transport
    ) {
        this.baseUrl = baseUrl;
        this.desktopServiceName = value(desktopServiceName);
        this.pairingExpiresAt = value(pairingExpiresAt);
        this.transport = transport;
    }

    public PairingCredential pair(String pairingCode, String pairingSessionId, String deviceName, String clientInstanceId)
        throws IOException, SmsBridgeClient.PairingRequiredException {
        try {
            String transcriptJson = transcriptJson(pairingSessionId, deviceName, clientInstanceId);
            JSONObject pairingStart = cryptoJson(SmsPusherCrypto.startPairingForRequest("client", transcriptJson, pairingCode));
            String clientPakeMessage = pairingStart.getString("messageBase64");
            String stateJson = pairingStart.getString("stateJson");

            JSONObject startBody = new JSONObject()
                .put("protocol", PROTOCOL_NAME)
                .put("pairingSessionId", pairingSessionId)
                .put("clientInstanceId", clientInstanceId)
                .put("deviceName", deviceName)
                .put("clientPakeMessage", clientPakeMessage);
            HttpTransport.Response startResponse = postJson("/pair/v2/start", startBody.toString());
            if (startResponse.status == 401 || startResponse.status == 410 || startResponse.status == 429) {
                throw new SmsBridgeClient.PairingRequiredException(SmsBridgeClient.errorCode(startResponse.body));
            }
            if (startResponse.status != 200) {
                throw new IOException("Secure pair start failed with status " + startResponse.status);
            }

            JSONObject start = new JSONObject(startResponse.body);
            String serverPakeMessage = start.getString("serverPakeMessage");
            JSONObject pairingFinish = cryptoJson(SmsPusherCrypto.finishPairingForRequest(stateJson, serverPakeMessage));
            String expectedServerConfirm = pairingFinish.getString("serverConfirmBase64");
            if (!expectedServerConfirm.equals(start.getString("serverConfirm"))) {
                throw new SmsBridgeClient.PairingRequiredException("pairing_confirmation_failed");
            }

            String pairKeyId = start.getString("keyId");
            String pairingKey = pairingFinish.getString("keyBase64");
            JSONObject plainFinishRequest = new JSONObject()
                .put("deviceName", deviceName)
                .put("clientInstanceId", clientInstanceId)
                .put("clientVersion", 2);
            String encryptedPairRequest = SmsPusherCrypto.sealEnvelopeForRequest(
                pairingKey,
                PAIRING_SESSION_DEVICE_ID,
                pairKeyId,
                0L,
                aad("/pair/v2/finish", pairKeyId, 0L),
                plainFinishRequest.toString()
            );
            JSONObject finishBody = new JSONObject()
                .put("protocol", PROTOCOL_NAME)
                .put("pairingSessionId", start.getString("pairingSessionId"))
                .put("clientConfirm", pairingFinish.getString("clientConfirmBase64"))
                .put("encryptedPairRequest", new JSONObject(encryptedPairRequest));
            HttpTransport.Response finishResponse = postJson("/pair/v2/finish", finishBody.toString());
            if (finishResponse.status == 401 || finishResponse.status == 409 || finishResponse.status == 410 || finishResponse.status == 429) {
                throw new SmsBridgeClient.PairingRequiredException(SmsBridgeClient.errorCode(finishResponse.body));
            }
            if (finishResponse.status != 200) {
                throw new IOException("Secure pair finish failed with status " + finishResponse.status);
            }

            JSONObject finish = new JSONObject(finishResponse.body);
            JSONObject encryptedPairResponse = finish.getJSONObject("encryptedPairResponse");
            String plainResponse = SmsPusherCrypto.openEnvelopeForRequest(
                pairingKey,
                encryptedPairResponse.toString(),
                aad("/pair/v2/finish/response", encryptedPairResponse.getString("keyId"), 1L)
            );
            JSONObject paired = new JSONObject(plainResponse);
            return PairingCredential.v2(
                paired.getString("deviceId"),
                paired.getString("keyId"),
                paired.getString("deviceSecret"),
                1L,
                paired.optString("desktopDeviceName", ""),
                Instant.parse(paired.getString("pairedAt"))
            );
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid secure pairing response", error);
        }
    }

    private HttpTransport.Response postJson(String path, String body) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return transport.post(baseUrl, path, headers, body);
    }

    private String transcriptJson(String pairingSessionId, String deviceName, String clientInstanceId) {
        try {
            return new JSONObject()
                .put("pairingSessionId", pairingSessionId)
                .put("desktopServiceInstanceId", desktopServiceName)
                .put("desktopDeviceName", desktopServiceName)
                .put("androidClientInstanceId", clientInstanceId)
                .put("androidDeviceName", deviceName)
                .put("desktopBaseUrl", "")
                .put("pairingExpiresAt", pairingExpiresAt)
                .toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid secure pairing transcript", error);
        }
    }

    private static JSONObject cryptoJson(String body) throws IOException {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) throw new IOException("Crypto operation failed: " + json.optString("error"));
            return json;
        } catch (JSONException error) {
            throw new IOException("Invalid crypto response", error);
        }
    }

    private static String aad(String path, String keyId, long counter) {
        return "SmsPusher\n" + PROTOCOL_NAME + "\nPOST\n" + path + "\n" + PAIRING_SESSION_DEVICE_ID + "\n" + keyId + "\n" + counter;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
