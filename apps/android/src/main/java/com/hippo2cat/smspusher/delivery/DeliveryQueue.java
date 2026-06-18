package com.hippo2cat.smspusher.delivery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class DeliveryQueue {
    public static final class Entry {
        public final String messageId;
        public final String json;

        public Entry(String messageId, String json) {
            this.messageId = messageId;
            this.json = json;
        }
    }

    private final int maxSize;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public DeliveryQueue(int maxSize) {
        this.maxSize = maxSize;
    }

    public void enqueue(String messageId, String json) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().messageId.equals(messageId)) {
                iterator.remove();
                break;
            }
        }
        entries.addLast(new Entry(messageId, json));
        while (entries.size() > maxSize) entries.removeFirst();
    }

    public Entry peek() {
        return entries.peekFirst();
    }

    public void markAccepted(String messageId) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().messageId.equals(messageId)) {
                iterator.remove();
                return;
            }
        }
    }

    public void clear() {
        entries.clear();
    }

    public List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public int size() {
        return entries.size();
    }
}
