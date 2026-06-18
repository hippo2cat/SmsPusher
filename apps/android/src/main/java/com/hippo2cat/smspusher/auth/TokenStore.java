package com.hippo2cat.smspusher.auth;

public interface TokenStore {
    PairingCredential loadCredential();
    void saveCredential(PairingCredential credential);
    void clearCredential();

    default void clear() {
        clearCredential();
    }

    default TokenBundle load() {
        PairingCredential credential = loadCredential();
        if (credential == null || credential.protocolVersion != 1) return null;
        return credential.toLegacyTokenBundle();
    }

    default void save(TokenBundle bundle) {
        saveCredential(PairingCredential.v1(bundle));
    }
}
