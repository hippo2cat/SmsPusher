package com.hippo2cat.smspusher.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SecureTokenStore implements TokenStore {
    private static final String KEY_ALIAS = "sms_bridge_token_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private final SharedPreferences preferences;

    public SecureTokenStore(Context context) {
        this.preferences = context.getSharedPreferences("sms_bridge_tokens", Context.MODE_PRIVATE);
    }

    @Override
    public PairingCredential loadCredential() {
        String iv = preferences.getString("iv", null);
        String ciphertext = preferences.getString("ciphertext", null);
        if (iv == null || ciphertext == null) return null;
        try {
            byte[] plain = decrypt(Base64.getDecoder().decode(iv), Base64.getDecoder().decode(ciphertext));
            JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            return PairingCredential.fromJson(json);
        } catch (Exception error) {
            LOG.warn("stored pairing credential unreadable; clearing", error);
            clearCredential();
            return null;
        }
    }

    @Override
    public void saveCredential(PairingCredential credential) {
        try {
            Encrypted encrypted = encrypt(credential.toJson().toString().getBytes(StandardCharsets.UTF_8));
            if (!preferences.edit()
                .putString("iv", Base64.getEncoder().encodeToString(encrypted.iv))
                .putString("ciphertext", Base64.getEncoder().encodeToString(encrypted.ciphertext))
                .commit()) {
                throw new IllegalStateException("SharedPreferences commit failed");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to save encrypted pairing credential", error);
        }
    }

    @Override
    public void clearCredential() {
        preferences.edit().clear().commit();
    }

    public void clear() {
        clearCredential();
    }

    private Encrypted encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return new Encrypted(cipher.getIV(), cipher.doFinal(plain));
    }

    private byte[] decrypt(byte[] iv, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    private static final class Encrypted {
        final byte[] iv;
        final byte[] ciphertext;

        Encrypted(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }
}
