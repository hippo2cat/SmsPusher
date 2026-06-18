package com.hippo2cat.smspusher.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class ClientIdentityStore {
    private static final String PREFS = "sms_bridge_client_identity";
    private static final String KEY_CLIENT_INSTANCE_ID = "client_instance_id";

    private ClientIdentityStore() {}

    public static String clientInstanceId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = preferences.getString(KEY_CLIENT_INSTANCE_ID, "");
        if (!existing.isEmpty()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        preferences.edit().putString(KEY_CLIENT_INSTANCE_ID, generated).apply();
        return generated;
    }
}
