package com.hippo2cat.smspusher.crypto;

import org.json.JSONObject;

public final class SecureEnvelope {
    public final int version;
    public final String deviceId;
    public final String keyId;
    public final long counter;
    public final String nonce;
    public final String ciphertext;

    public SecureEnvelope(int version, String deviceId, String keyId, long counter, String nonce, String ciphertext) {
        this.version = version;
        this.deviceId = value(deviceId);
        this.keyId = value(keyId);
        this.counter = counter;
        this.nonce = value(nonce);
        this.ciphertext = value(ciphertext);
    }

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("version", version);
        json.put("deviceId", deviceId);
        json.put("keyId", keyId);
        json.put("counter", counter);
        json.put("nonce", nonce);
        json.put("ciphertext", ciphertext);
        return json;
    }

    public static SecureEnvelope fromJson(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        return new SecureEnvelope(
            json.getInt("version"),
            json.optString("deviceId", ""),
            json.getString("keyId"),
            json.optLong("counter", 0L),
            json.getString("nonce"),
            json.getString("ciphertext")
        );
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
