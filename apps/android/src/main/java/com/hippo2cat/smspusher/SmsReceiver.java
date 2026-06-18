package com.hippo2cat.smspusher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.hippo2cat.smspusher.delivery.DeliveryWorker;

public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                DeliveryWorker.enqueueFromSmsIntent(appContext, intent);
            } finally {
                result.finish();
            }
        }, "SmsBridgeSmsReceiver").start();
    }
}
