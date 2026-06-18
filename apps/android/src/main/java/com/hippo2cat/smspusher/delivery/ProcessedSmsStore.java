package com.hippo2cat.smspusher.delivery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public final class ProcessedSmsStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "sms_bridge_processed_sms";
    private static final String KEY_MESSAGE_IDS = "messageIds";
    private static final int MAX_IDS = 250;

    private final SharedPreferences preferences;

    public ProcessedSmsStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean claim(String messageId) {
        if (messageId == null || messageId.isEmpty()) return false;
        synchronized (LOCK) {
            List<String> ids = load();
            if (ids.contains(messageId)) return false;
            ids.add(messageId);
            while (ids.size() > MAX_IDS) ids.remove(0);
            persist(ids);
            return true;
        }
    }

    private List<String> load() {
        List<String> ids = new ArrayList<>();
        String raw = preferences.getString(KEY_MESSAGE_IDS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                ids.add(array.getString(i));
            }
        } catch (Exception invalidState) {
            preferences.edit().remove(KEY_MESSAGE_IDS).apply();
        }
        return ids;
    }

    private void persist(List<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        preferences.edit().putString(KEY_MESSAGE_IDS, array.toString()).commit();
    }
}
