package com.hippo2cat.smspusher.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ForwardingStatsStore {
    private static final String PREFS = "sms_bridge_forwarding_stats";
    private static final String KEY_LAST_RECEIVED_AT = "lastReceivedAt";
    private static final String KEY_LAST_FORWARDED_AT = "lastForwardedAt";
    private static final String KEY_LAST_FAILURE_REASON = "lastFailureReason";
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public final String lastReceivedAt;
    public final String lastForwardedAt;
    public final String lastFailureReason;

    private ForwardingStatsStore(String lastReceivedAt, String lastForwardedAt, String lastFailureReason) {
        this.lastReceivedAt = lastReceivedAt;
        this.lastForwardedAt = lastForwardedAt;
        this.lastFailureReason = lastFailureReason;
    }

    public static ForwardingStatsStore load(Context context) {
        SharedPreferences preferences = preferences(context);
        return new ForwardingStatsStore(
            preferences.getString(KEY_LAST_RECEIVED_AT, ""),
            preferences.getString(KEY_LAST_FORWARDED_AT, ""),
            preferences.getString(KEY_LAST_FAILURE_REASON, "")
        );
    }

    public static ForwardingStatsText text(Context context, int pendingCount, ForwardingStatsText.Copy copy) {
        ForwardingStatsStore stats = load(context);
        return ForwardingStatsText.from(
            pendingCount,
            stats.lastReceivedAt,
            stats.lastForwardedAt,
            stats.lastFailureReason,
            copy
        );
    }

    public static void recordReceived(Context context, Instant receivedAt) {
        preferences(context).edit()
            .putString(KEY_LAST_RECEIVED_AT, format(receivedAt))
            .remove(KEY_LAST_FAILURE_REASON)
            .apply();
    }

    public static void recordForwarded(Context context, Instant forwardedAt) {
        preferences(context).edit()
            .putString(KEY_LAST_FORWARDED_AT, format(forwardedAt))
            .remove(KEY_LAST_FAILURE_REASON)
            .apply();
    }

    public static void recordFailure(Context context, String reason) {
        preferences(context).edit()
            .putString(KEY_LAST_FAILURE_REASON, reason == null ? "" : reason)
            .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String format(Instant instant) {
        if (instant == null) return "";
        return DISPLAY_FORMATTER.format(instant.atZone(ZoneId.systemDefault()));
    }
}
