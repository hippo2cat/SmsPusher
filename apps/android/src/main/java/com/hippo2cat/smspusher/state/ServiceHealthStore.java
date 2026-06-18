package com.hippo2cat.smspusher.state;

import android.content.Context;
import android.content.SharedPreferences;

public final class ServiceHealthStore {
    private static final String PREFS = "sms_bridge_service_health";
    private static final String KEY_LAST_HEARTBEAT_AT = "lastHeartbeatAt";
    private static final String KEY_NETWORK_AVAILABLE = "networkAvailable";
    public static final long STALE_AFTER_MS = 20_000L;

    private ServiceHealthStore() {}

    public static void recordHeartbeat(Context context) {
        preferences(context)
            .edit()
            .putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
            .apply();
    }

    public static void clearHeartbeat(Context context) {
        preferences(context)
            .edit()
            .remove(KEY_LAST_HEARTBEAT_AT)
            .remove(KEY_NETWORK_AVAILABLE)
            .apply();
    }

    public static void recordNetworkAvailable(Context context) {
        preferences(context)
            .edit()
            .putBoolean(KEY_NETWORK_AVAILABLE, true)
            .apply();
    }

    public static void recordNetworkLost(Context context) {
        preferences(context)
            .edit()
            .putBoolean(KEY_NETWORK_AVAILABLE, false)
            .apply();
    }

    public static long lastHeartbeatAt(Context context) {
        return preferences(context).getLong(KEY_LAST_HEARTBEAT_AT, 0L);
    }

    public static boolean isHealthy(Context context) {
        return isHealthy(context, System.currentTimeMillis());
    }

    public static boolean isHealthy(Context context, long nowMillis) {
        long lastHeartbeat = lastHeartbeatAt(context);
        return lastHeartbeat > 0L && nowMillis - lastHeartbeat <= STALE_AFTER_MS;
    }

    public static boolean isNetworkAvailable(Context context) {
        return preferences(context).getBoolean(KEY_NETWORK_AVAILABLE, true);
    }

    public static boolean isConnectionHealthy(Context context) {
        return isHealthy(context) && isNetworkAvailable(context);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
