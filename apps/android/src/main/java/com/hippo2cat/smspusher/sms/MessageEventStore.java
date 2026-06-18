package com.hippo2cat.smspusher.sms;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MessageEventStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "sms_bridge_messages.db";

    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private static final int VERSION = 1;
    private static final int RETAINED_EVENTS = 100;
    private static final String TABLE = "message_events";

    public MessageEventStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
                "message_id TEXT PRIMARY KEY," +
                "sender TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "received_at TEXT NOT NULL," +
                "subscription_id INTEGER," +
                "device_id TEXT NOT NULL," +
                "source TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "destination TEXT," +
                "failure_reason TEXT," +
                "last_attempt_at TEXT," +
                "forwarded_at TEXT," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL" +
            ")"
        );
        db.execSQL("CREATE INDEX idx_message_events_updated_at ON " + TABLE + "(updated_at DESC)");
        db.execSQL("CREATE INDEX idx_message_events_status ON " + TABLE + "(status)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void recordPending(IncomingSms sms, MessageEvent.Source source, String destination, long nowMillis) {
        if (sms == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("message_id", sms.messageId());
        values.put("sender", nonNull(sms.sender));
        values.put("body", nonNull(sms.body));
        values.put("received_at", sms.receivedAt == null ? "" : sms.receivedAt.toString());
        values.put("subscription_id", sms.subscriptionId);
        values.put("device_id", nonNull(sms.deviceId));
        values.put("source", source == null ? MessageEvent.Source.BROADCAST.value : source.value);
        values.put("status", MessageEvent.Status.PENDING.value);
        values.put("destination", blankToNull(destination));
        values.put("failure_reason", (String) null);
        values.put("last_attempt_at", (String) null);
        values.put("forwarded_at", (String) null);
        values.put("created_at", existingCreatedAt(db, sms.messageId(), nowMillis));
        values.put("updated_at", nowMillis);
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        trim(db);
    }

    public void recordAttempt(String messageId, Instant attemptedAt, long nowMillis) {
        updateExisting(messageId, nowMillis, values -> {
            values.put("last_attempt_at", attemptedAt == null ? null : attemptedAt.toString());
        });
    }

    public void recordForwarded(String messageId, String destination, Instant forwardedAt, long nowMillis) {
        updateExisting(messageId, nowMillis, values -> {
            values.put("status", MessageEvent.Status.FORWARDED.value);
            values.put("destination", blankToNull(destination));
            values.put("failure_reason", (String) null);
            values.put("forwarded_at", forwardedAt == null ? null : forwardedAt.toString());
        });
    }

    public void recordFailed(String messageId, String reason, long nowMillis) {
        updateExisting(messageId, nowMillis, values -> {
            values.put("status", MessageEvent.Status.FAILED.value);
            values.put("failure_reason", blankToNull(reason));
        });
    }

    public List<MessageEvent> recentActivity(int limit) {
        int boundedLimit = Math.max(0, Math.min(limit, RETAINED_EVENTS));
        ArrayList<MessageEvent> events = new ArrayList<>();
        if (boundedLimit == 0) return events;
        try (Cursor cursor = getReadableDatabase().query(
            TABLE,
            null,
            null,
            null,
            null,
            null,
            "updated_at DESC",
            String.valueOf(boundedLimit)
        )) {
            while (cursor.moveToNext()) events.add(fromCursor(cursor));
        }
        return events;
    }

    public List<MessageEvent> unresolvedMessages(int limit) {
        int boundedLimit = Math.max(0, Math.min(limit, RETAINED_EVENTS));
        ArrayList<MessageEvent> events = new ArrayList<>();
        if (boundedLimit == 0) return events;
        try (Cursor cursor = getReadableDatabase().query(
            TABLE,
            null,
            "status!=?",
            new String[] { MessageEvent.Status.FORWARDED.value },
            null,
            null,
            "updated_at DESC",
            String.valueOf(boundedLimit)
        )) {
            while (cursor.moveToNext()) events.add(fromCursor(cursor));
        }
        return events;
    }

    public Map<String, MessageEvent> byMessageIds(List<String> messageIds) {
        HashMap<String, MessageEvent> events = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return events;
        for (String messageId : messageIds) {
            MessageEvent event = byMessageId(messageId);
            if (event != null) events.put(messageId, event);
        }
        return events;
    }

    private MessageEvent byMessageId(String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        try (Cursor cursor = getReadableDatabase().query(
            TABLE,
            null,
            "message_id=?",
            new String[] { messageId },
            null,
            null,
            null,
            "1"
        )) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    private interface ValuesMutation {
        void apply(ContentValues values);
    }

    private void updateExisting(String messageId, long nowMillis, ValuesMutation mutation) {
        if (messageId == null || messageId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        mutation.apply(values);
        values.put("updated_at", nowMillis);
        int updated = db.update(TABLE, values, "message_id=?", new String[] { messageId });
        if (updated == 0) LOG.info("message event update skipped missing messageId={}", messageId);
        trim(db);
    }

    private long existingCreatedAt(SQLiteDatabase db, String messageId, long fallback) {
        try (Cursor cursor = db.query(
            TABLE,
            new String[] { "created_at" },
            "message_id=?",
            new String[] { messageId },
            null,
            null,
            null,
            "1"
        )) {
            return cursor.moveToFirst() ? cursor.getLong(0) : fallback;
        }
    }

    private void trim(SQLiteDatabase db) {
        db.execSQL(
            "DELETE FROM " + TABLE + " WHERE message_id NOT IN (" +
                "SELECT message_id FROM " + TABLE + " ORDER BY updated_at DESC LIMIT " + RETAINED_EVENTS +
            ")"
        );
    }

    private static MessageEvent fromCursor(Cursor cursor) {
        return new MessageEvent(
            string(cursor, "message_id"),
            string(cursor, "sender"),
            string(cursor, "body"),
            string(cursor, "received_at"),
            (int) longValue(cursor, "subscription_id"),
            string(cursor, "device_id"),
            MessageEvent.Source.from(string(cursor, "source")),
            MessageEvent.Status.from(string(cursor, "status")),
            nullableString(cursor, "destination"),
            nullableString(cursor, "failure_reason"),
            nullableString(cursor, "last_attempt_at"),
            nullableString(cursor, "forwarded_at"),
            longValue(cursor, "created_at"),
            longValue(cursor, "updated_at")
        );
    }

    private static String string(Cursor cursor, String column) {
        String value = nullableString(cursor, column);
        return value == null ? "" : value;
    }

    private static String nullableString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return null;
        return cursor.getString(index);
    }

    private static long longValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return 0L;
        return cursor.getLong(index);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
