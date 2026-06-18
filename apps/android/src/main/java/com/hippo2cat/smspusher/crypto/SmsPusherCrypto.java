package com.hippo2cat.smspusher.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.json.JSONObject;

public final class SmsPusherCrypto {
    private static final String HOST_JVM_CLIENT_MESSAGE_BASE64 = "aG9zdC1qdm0tY2xpZW50LW1lc3NhZ2U=";
    private static final String HOST_JVM_PAIRING_KEY_BASE64 = "aG9zdC1qdm0tcGFpcmluZy1rZXk=";
    private static final String HOST_JVM_CLIENT_CONFIRM_BASE64 = "aG9zdC1qdm0tY2xpZW50LWNvbmZpcm0=";
    private static final String HOST_JVM_SERVER_CONFIRM_BASE64 = "aG9zdC1qdm0tc2VydmVyLWNvbmZpcm0=";

    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("smspusher_crypto_jni");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            if (isAndroidRuntime()) throw error;
        }
        LIBRARY_LOADED = loaded;
    }

    private SmsPusherCrypto() {}

    public static native String startPairing(String role, String transcriptJson, String pairingCode);
    public static native String finishPairing(String stateJson, String peerMessageBase64);
    public static native String sealEnvelope(String keyBase64, String deviceId, String keyId, long counter, String aad, String plaintextJson);
    public static native String openEnvelope(String keyBase64, String envelopeJson, String aad);

    public static String startPairingForRequest(String role, String transcriptJson, String pairingCode) {
        if (LIBRARY_LOADED) {
            return startPairing(role, transcriptJson, pairingCode);
        }
        ensureHostJvmTestFallback();
        try {
            return new JSONObject()
                .put("stateJson", new JSONObject().put("stateId", "host-jvm-test-state").toString())
                .put("messageBase64", HOST_JVM_CLIENT_MESSAGE_BASE64)
                .toString();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create host JVM test pairing start", error);
        }
    }

    public static String finishPairingForRequest(String stateJson, String peerMessageBase64) {
        if (LIBRARY_LOADED) {
            return finishPairing(stateJson, peerMessageBase64);
        }
        ensureHostJvmTestFallback();
        try {
            return new JSONObject()
                .put("keyBase64", HOST_JVM_PAIRING_KEY_BASE64)
                .put("clientConfirmBase64", HOST_JVM_CLIENT_CONFIRM_BASE64)
                .put("serverConfirmBase64", HOST_JVM_SERVER_CONFIRM_BASE64)
                .toString();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create host JVM test pairing finish", error);
        }
    }

    public static String sealEnvelopeForRequest(String keyBase64, String deviceId, String keyId, long counter, String aad, String plaintextJson) {
        if (LIBRARY_LOADED) {
            return sealEnvelope(keyBase64, deviceId, keyId, counter, aad, plaintextJson);
        }
        ensureHostJvmTestFallback();
        try {
            return new SecureEnvelope(
                2,
                deviceId,
                keyId,
                counter,
                "host-jvm-test-nonce",
                Base64.getEncoder().encodeToString(plaintextJson.getBytes(StandardCharsets.UTF_8))
            ).toJson().toString();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create host JVM test envelope", error);
        }
    }

    public static String openEnvelopeForRequest(String keyBase64, String envelopeJson, String aad) {
        if (LIBRARY_LOADED) {
            return openEnvelope(keyBase64, envelopeJson, aad);
        }
        ensureHostJvmTestFallback();
        try {
            SecureEnvelope envelope = SecureEnvelope.fromJson(envelopeJson);
            return new String(Base64.getDecoder().decode(envelope.ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to open host JVM test envelope", error);
        }
    }

    private static void ensureHostJvmTestFallback() {
        if (isAndroidRuntime()) {
            throw new IllegalStateException("Native crypto library is not loaded");
        }
    }

    private static boolean isAndroidRuntime() {
        return System.getProperty("java.runtime.name", "").toLowerCase().contains("android")
            || System.getProperty("java.vm.vendor", "").toLowerCase().contains("android");
    }
}
