package com.hippo2cat.smspusher;

import android.content.Context;
import android.content.Intent;

public final class ConnectionStatusEvents {
    public static final String ACTION_REFRESH = "com.hippo2cat.smspusher.action.CONNECTION_STATUS_REFRESH";
    public static final String EXTRA_SOURCE = "source";

    private ConnectionStatusEvents() {}

    public static void notifyChanged(Context context, String source) {
        if (context == null) return;
        Intent intent = new Intent(ACTION_REFRESH);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_SOURCE, source == null ? "" : source);
        context.getApplicationContext().sendBroadcast(intent);
    }
}
