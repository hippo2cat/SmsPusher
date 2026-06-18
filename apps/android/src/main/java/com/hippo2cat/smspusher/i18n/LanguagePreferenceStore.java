package com.hippo2cat.smspusher.i18n;

import android.content.Context;
import android.content.SharedPreferences;

public final class LanguagePreferenceStore {
    private static final String PREFS = "sms_bridge_language";
    private static final String KEY_LANGUAGE = "language_preference";

    private LanguagePreferenceStore() {}

    public static LanguagePreference get(Context context) {
        return LanguagePreference.fromStorageValue(preferences(context).getString(KEY_LANGUAGE, "auto"));
    }

    public static void set(Context context, LanguagePreference preference) {
        preferences(context)
            .edit()
            .putString(KEY_LANGUAGE, preference == null ? "auto" : preference.storageValue)
            .commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
