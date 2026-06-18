package com.hippo2cat.smspusher.delivery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class PersistentDeliveryQueue {
    private static final String PREFS = "sms_bridge_delivery_queue";
    private static final String KEY_ENTRIES = "entries";
    private final SharedPreferences preferences;
    private final DeliveryQueue queue;

    public PersistentDeliveryQueue(Context context, int maxSize) {
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.queue = new DeliveryQueue(maxSize);
        load();
    }

    public void enqueue(String messageId, String json) {
        queue.enqueue(messageId, json);
        persist();
    }

    public DeliveryQueue.Entry peek() {
        return queue.peek();
    }

    public List<DeliveryQueue.Entry> snapshot() {
        return queue.snapshot();
    }

    public void markAccepted(String messageId) {
        queue.markAccepted(messageId);
        persist();
    }

    public void clear() {
        queue.clear();
        persist();
    }

    public int size() {
        return queue.size();
    }

    private void load() {
        String raw = preferences.getString(KEY_ENTRIES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                queue.enqueue(item.getString("messageId"), item.getString("json"));
            }
        } catch (Exception invalidQueue) {
            preferences.edit().remove(KEY_ENTRIES).apply();
        }
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (DeliveryQueue.Entry entry : queue.snapshot()) {
                JSONObject item = new JSONObject();
                item.put("messageId", entry.messageId);
                item.put("json", entry.json);
                array.put(item);
            }
            preferences.edit().putString(KEY_ENTRIES, array.toString()).apply();
        } catch (Exception impossible) {
            throw new IllegalStateException("Unable to persist delivery queue", impossible);
        }
    }
}
