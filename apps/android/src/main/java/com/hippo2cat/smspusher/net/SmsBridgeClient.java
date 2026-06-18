package com.hippo2cat.smspusher.net;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.TokenBundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SmsBridgeClient {
    private final String baseUrl;
    private final HttpTransport transport;

    public SmsBridgeClient(String baseUrl, HttpTransport transport) {
        this.baseUrl = baseUrl;
        this.transport = transport;
    }

    public boolean testConnection() throws IOException {
        HttpTransport.Response response = transport.get(baseUrl, "/health", new HashMap<>());
        return response.status == 200 && response.body.contains("\"status\":\"ok\"");
    }

    public PairingSession fetchPairingSession() throws IOException {
        HttpTransport.Response response = transport.get(baseUrl, "/pair/v2/session", new HashMap<>());
        if (response.status != 200) throw new IOException("Pairing session failed with status " + response.status);
        try {
            JSONObject json = new JSONObject(response.body);
            return new PairingSession(
                json.optString("serviceName", ""),
                json.optString("secureProtocol", ""),
                json.getString("pairingSessionId"),
                json.getString("pairingExpiresAt")
            );
        } catch (JSONException error) {
            throw new IOException("Invalid pairing session response", error);
        }
    }

    public TokenBundle verifyPairing(TokenBundle token) throws IOException, PairingRequiredException {
        HttpTransport.Response response = postAuthCheck(token.accessToken, token.deviceId);
        if (response.status == 200) return token;
        String error = errorCode(response.body);
        if (response.status == 401 && "token_expired".equals(error)) {
            return refresh(token);
        }
        if (response.status == 401) throw new PairingRequiredException(error);
        throw new IOException("Pairing verification failed with status " + response.status);
    }

    public TokenBundle sendMessage(TokenBundle token, String messageJson) throws IOException, PairingRequiredException {
        HttpTransport.Response response = postMessage(token.accessToken, messageJson);
        if (response.status == 200) return token;
        String error = errorCode(response.body);
        if (response.status == 401 && "token_expired".equals(error)) {
            TokenBundle refreshed = refresh(token);
            HttpTransport.Response retry = postMessage(refreshed.accessToken, messageJson);
            if (retry.status == 200) return refreshed;
            throw new IOException("SMS retry failed with status " + retry.status);
        }
        if (response.status == 401) throw new PairingRequiredException(error);
        throw new IOException("SMS send failed with status " + response.status);
    }

    public PairingCredential verifyPairing(PairingCredential credential) throws IOException, PairingRequiredException {
        if (credential == null || credential.requiresSecureRePairing()) {
            throw new PairingRequiredException("secure_pairing_required");
        }
        return new SecureMessageClient(baseUrl, transport).verifyPairing(credential);
    }

    public PairingCredential sendMessage(PairingCredential credential, String messageJson) throws IOException, PairingRequiredException {
        if (credential == null || credential.requiresSecureRePairing()) {
            throw new PairingRequiredException("secure_pairing_required");
        }
        return new SecureMessageClient(baseUrl, transport).sendMessage(credential, messageJson);
    }

    public PairingCredential pairSecure(
        String pairingCode,
        String pairingSessionId,
        String deviceName,
        String clientInstanceId
    ) throws IOException, PairingRequiredException {
        return pairSecure(pairingCode, pairingSessionId, deviceName, clientInstanceId, "", "");
    }

    public PairingCredential pairSecure(
        String pairingCode,
        String pairingSessionId,
        String deviceName,
        String clientInstanceId,
        String desktopServiceName,
        String pairingExpiresAt
    ) throws IOException, PairingRequiredException {
        return new SecurePairingClient(baseUrl, desktopServiceName, pairingExpiresAt, transport).pair(
            pairingCode,
            pairingSessionId,
            deviceName,
            clientInstanceId
        );
    }

    private HttpTransport.Response postMessage(String accessToken, String messageJson) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Content-Type", "application/json");
        return transport.post(baseUrl, "/messages", headers, messageJson);
    }

    private HttpTransport.Response postAuthCheck(String accessToken, String deviceId) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Content-Type", "application/json");
        String request = "{\"deviceId\":\"" + escape(deviceId) + "\"}";
        return transport.post(baseUrl, "/auth/check", headers, request);
    }

    private TokenBundle refresh(TokenBundle token) throws IOException, PairingRequiredException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        String request = "{\"deviceId\":\"" + escape(token.deviceId) + "\",\"refreshToken\":\"" + escape(token.refreshToken) + "\"}";
        HttpTransport.Response response = transport.post(baseUrl, "/auth/refresh", headers, request);
        if (response.status == 401) throw new PairingRequiredException(errorCode(response.body));
        if (response.status != 200) throw new IOException("Refresh failed with status " + response.status);
        return tokenFromResponse(token.deviceId, response.body);
    }

    public TokenBundle pair(String pairingCode, String deviceName) throws IOException, PairingRequiredException {
        return pair(pairingCode, deviceName, "");
    }

    public TokenBundle pair(String pairingCode, String deviceName, String clientInstanceId) throws IOException, PairingRequiredException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        String request = "{\"pairingCode\":\"" + escape(pairingCode) + "\",\"deviceName\":\"" + escape(deviceName) + "\",\"clientVersion\":1";
        if (clientInstanceId != null && !clientInstanceId.isEmpty()) {
            request += ",\"clientInstanceId\":\"" + escape(clientInstanceId) + "\"";
        }
        request += "}";
        HttpTransport.Response response = transport.post(baseUrl, "/pair", headers, request);
        if (response.status == 401) throw new PairingRequiredException(errorCode(response.body));
        if (response.status != 200) throw new IOException("Pair failed with status " + response.status);
        return tokenFromResponse(field(response.body, "deviceId"), response.body);
    }

    private static TokenBundle tokenFromResponse(String deviceId, String body) throws IOException {
        return new TokenBundle(
            deviceId,
            field(body, "accessToken"),
            Instant.parse(field(body, "accessTokenExpiresAt")),
            field(body, "refreshToken"),
            Instant.parse(field(body, "refreshTokenExpiresAt"))
        );
    }

    static String errorCode(String body) {
        try {
            return field(body, "error");
        } catch (IOException ignored) {
            return "unknown_error";
        }
    }

    private static String field(String body, String name) throws IOException {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) throw new IOException("Missing JSON field: " + name);
        return matcher.group(1);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final class PairingRequiredException extends Exception {
        public final String reason;

        public PairingRequiredException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    public static final class PairingSession {
        public final String serviceName;
        public final String secureProtocol;
        public final String pairingSessionId;
        public final String pairingExpiresAt;

        public PairingSession(String serviceName, String secureProtocol, String pairingSessionId, String pairingExpiresAt) {
            this.serviceName = serviceName == null ? "" : serviceName;
            this.secureProtocol = secureProtocol == null ? "" : secureProtocol;
            this.pairingSessionId = pairingSessionId == null ? "" : pairingSessionId;
            this.pairingExpiresAt = pairingExpiresAt == null ? "" : pairingExpiresAt;
        }
    }
}
