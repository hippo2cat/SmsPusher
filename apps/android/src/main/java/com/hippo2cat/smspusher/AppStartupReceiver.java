package com.hippo2cat.smspusher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.SecureTokenStore;
import com.hippo2cat.smspusher.state.PairingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppStartupReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!isRecoveryAction(action)) return;
        if (!canRecoverListener(context)) {
            LOG.info("listener recovery skipped action={}", action);
            return;
        }
        try {
            SmsListenerService.start(context.getApplicationContext());
            LOG.info("listener recovery started action={}", action);
        } catch (RuntimeException error) {
            LOG.error("listener recovery failed action={}", action, error);
        }
    }

    private static boolean isRecoveryAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }

    private static boolean canRecoverListener(Context context) {
        PairingCredential credential = new SecureTokenStore(context).loadCredential();
        if (credential == null || credential.requiresSecureRePairing()) return false;
        if (PairingStore.loadMacBaseUrl(context).isEmpty()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }
}
