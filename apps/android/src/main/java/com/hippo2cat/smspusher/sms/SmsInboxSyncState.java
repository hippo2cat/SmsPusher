package com.hippo2cat.smspusher.sms;

import android.content.Context;
import android.content.SharedPreferences;

public final class SmsInboxSyncState {
    private static final String PREFS = "sms_bridge_inbox_sync";
    private static final String KEY_LAST_SCAN_AT = "lastScanAtMillis";

    private final SharedPreferences preferences;

    public SmsInboxSyncState(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public long lastScanAtMillis() {
        return preferences.getLong(KEY_LAST_SCAN_AT, 0L);
    }

    public void saveLastScanAtMillis(long value) {
        preferences.edit().putLong(KEY_LAST_SCAN_AT, Math.max(0L, value)).apply();
    }
}
