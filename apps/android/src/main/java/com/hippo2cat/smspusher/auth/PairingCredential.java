package com.hippo2cat.smspusher.auth;

import org.json.JSONObject;

import java.time.Instant;

public final class PairingCredential {
    public final int protocolVersion;
    public final String deviceId;
    public final String accessToken;
    public final Instant accessTokenExpiresAt;
    public final String refreshToken;
    public final Instant refreshTokenExpiresAt;
    public final String keyId;
    public final String deviceSecret;
    public final long nextCounter;
    public final String pairedDesktopName;
    public final Instant pairedAt;

    private PairingCredential(
        int protocolVersion,
        String deviceId,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String keyId,
        String deviceSecret,
        long nextCounter,
        String pairedDesktopName,
        Instant pairedAt
    ) {
        this.protocolVersion = protocolVersion;
        this.deviceId = value(deviceId);
        this.accessToken = value(accessToken);
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshToken = value(refreshToken);
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.keyId = value(keyId);
        this.deviceSecret = value(deviceSecret);
        this.nextCounter = Math.max(1L, nextCounter);
        this.pairedDesktopName = value(pairedDesktopName);
        this.pairedAt = pairedAt;
    }

    public static PairingCredential v2(
        String deviceId,
        String keyId,
        String deviceSecret,
        long nextCounter,
        String pairedDesktopName,
        Instant pairedAt
    ) {
        return new PairingCredential(
            2,
            deviceId,
            "",
            null,
            "",
            null,
            keyId,
            deviceSecret,
            nextCounter,
            pairedDesktopName,
            pairedAt
        );
    }

    public static PairingCredential v1(TokenBundle token) {
        return new PairingCredential(
            1,
            token.deviceId,
            token.accessToken,
            token.accessTokenExpiresAt,
            token.refreshToken,
            token.refreshTokenExpiresAt,
            "",
            "",
            1L,
            "",
            null
        );
    }

    public boolean isV2() {
        return protocolVersion == 2 && !deviceId.isEmpty() && !keyId.isEmpty() && !deviceSecret.isEmpty();
    }

    public boolean requiresSecureRePairing() {
        return protocolVersion < 2 || !isV2();
    }

    public PairingCredential withNextCounter(long nextCounter) {
        return new PairingCredential(
            protocolVersion,
            deviceId,
            accessToken,
            accessTokenExpiresAt,
            refreshToken,
            refreshTokenExpiresAt,
            keyId,
            deviceSecret,
            nextCounter,
            pairedDesktopName,
            pairedAt
        );
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("protocolVersion", protocolVersion);
        json.put("deviceId", deviceId);
        if (protocolVersion == 2) {
            json.put("keyId", keyId);
            json.put("deviceSecret", deviceSecret);
            json.put("nextCounter", nextCounter);
            json.put("pairedDesktopName", pairedDesktopName);
            json.put("pairedAt", pairedAt == null ? "" : pairedAt.toString());
        } else {
            json.put("accessToken", accessToken);
            json.put("accessTokenExpiresAt", accessTokenExpiresAt == null ? "" : accessTokenExpiresAt.toString());
            json.put("refreshToken", refreshToken);
            json.put("refreshTokenExpiresAt", refreshTokenExpiresAt == null ? "" : refreshTokenExpiresAt.toString());
        }
        return json;
    }

    public static PairingCredential fromJson(JSONObject json) throws Exception {
        int version = json.optInt("protocolVersion", json.has("accessToken") ? 1 : 2);
        if (version == 2) {
            return v2(
                json.getString("deviceId"),
                json.getString("keyId"),
                json.getString("deviceSecret"),
                json.optLong("nextCounter", 1L),
                json.optString("pairedDesktopName", ""),
                parseInstant(json.optString("pairedAt", ""))
            );
        }
        return new PairingCredential(
            1,
            json.getString("deviceId"),
            json.getString("accessToken"),
            Instant.parse(json.getString("accessTokenExpiresAt")),
            json.getString("refreshToken"),
            Instant.parse(json.getString("refreshTokenExpiresAt")),
            "",
            "",
            1L,
            "",
            null
        );
    }

    public TokenBundle toLegacyTokenBundle() {
        return new TokenBundle(deviceId, accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isEmpty()) return null;
        return Instant.parse(value);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
