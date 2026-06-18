package com.hippo2cat.smspusher.auth;

import java.time.Instant;

public final class TokenBundle {
    public final String deviceId;
    public final String accessToken;
    public final Instant accessTokenExpiresAt;
    public final String refreshToken;
    public final Instant refreshTokenExpiresAt;

    public TokenBundle(String deviceId, String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt) {
        this.deviceId = deviceId;
        this.accessToken = accessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshToken = refreshToken;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
    }
}
