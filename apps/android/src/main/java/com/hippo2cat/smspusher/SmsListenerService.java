package com.hippo2cat.smspusher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.SecureTokenStore;
import com.hippo2cat.smspusher.delivery.DeliveryWorker;
import com.hippo2cat.smspusher.i18n.AppLocale;
import com.hippo2cat.smspusher.sms.SmsInboxScanPolicy;
import com.hippo2cat.smspusher.sms.SmsInboxSynchronizer;
import com.hippo2cat.smspusher.state.PairingStore;
import com.hippo2cat.smspusher.state.ServiceHealthStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmsListenerService extends Service {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private static final String CHANNEL_ID = "sms_listener";
    private static final int NOTIFICATION_ID = 1001;
    private Handler handler;
    private ExecutorService executor;
    private ContentObserver inboxObserver;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final Runnable pollInbox = new Runnable() {
        @Override
        public void run() {
            ServiceHealthStore.recordHeartbeat(SmsListenerService.this);
            ensureInboxObserver();
            requestInboxSync("poll");
            handler.postDelayed(this, SmsInboxScanPolicy.POLL_INTERVAL_MS);
        }
    };
    private final Runnable retryQueue = new Runnable() {
        @Override
        public void run() {
            ServiceHealthStore.recordHeartbeat(SmsListenerService.this);
            drainPendingQueue("retryQueue");
            handler.postDelayed(this, SmsInboxScanPolicy.QUEUE_RETRY_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, SmsListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SmsListenerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "SmsBridgeInboxSync"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        ServiceHealthStore.recordHeartbeat(this);
        recordCurrentNetworkAvailability();
        ConnectionStatusEvents.notifyChanged(this, "service_start");
        ensureInboxObserver();
        ensureNetworkCallback();
        requestInboxSync("service_start");
        drainPendingQueue("service_start");
        handler.removeCallbacks(pollInbox);
        handler.removeCallbacks(retryQueue);
        handler.postDelayed(pollInbox, SmsInboxScanPolicy.POLL_INTERVAL_MS);
        handler.postDelayed(retryQueue, SmsInboxScanPolicy.QUEUE_RETRY_INTERVAL_MS);
        LOG.info("sms listener foreground service running");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(pollInbox);
        if (handler != null) handler.removeCallbacks(retryQueue);
        if (inboxObserver != null) {
            getContentResolver().unregisterContentObserver(inboxObserver);
            inboxObserver = null;
        }
        unregisterNetworkCallback();
        if (executor != null) executor.shutdownNow();
        ServiceHealthStore.clearHeartbeat(this);
        ConnectionStatusEvents.notifyChanged(this, "service_stop");
        super.onDestroy();
    }

    private void ensureInboxObserver() {
        if (inboxObserver != null || !hasReadSmsPermission()) return;
        inboxObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                requestInboxSync("observer");
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                requestInboxSync("observer");
            }
        };
        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, inboxObserver);
        LOG.info("inbox content observer registered");
    }

    private void ensureNetworkCallback() {
        if (networkCallback != null) return;
        connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    LOG.info("network available network={}", network);
                    ServiceHealthStore.recordHeartbeat(SmsListenerService.this);
                    ServiceHealthStore.recordNetworkAvailable(SmsListenerService.this);
                    ConnectionStatusEvents.notifyChanged(SmsListenerService.this, "network_available");
                    requestInboxSync("network_available");
                    drainPendingQueue("network_available");
                });
            }

            @Override
            public void onLost(Network network) {
                handler.post(() -> {
                    LOG.info("network lost network={}", network);
                    ServiceHealthStore.recordNetworkLost(SmsListenerService.this);
                    ConnectionStatusEvents.notifyChanged(SmsListenerService.this, "network_lost");
                });
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
        LOG.info("network availability callback registered");
    }

    private void recordCurrentNetworkAvailability() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null && manager.getActiveNetwork() != null) {
            ServiceHealthStore.recordNetworkAvailable(this);
        } else {
            ServiceHealthStore.recordNetworkLost(this);
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Network callbacks can already be gone if the system is tearing down the service.
        } finally {
            networkCallback = null;
            connectivityManager = null;
        }
    }

    private void requestInboxSync(String source) {
        if (!hasReadSmsPermission()) return;
        if (!syncRunning.compareAndSet(false, true)) return;
        Context appContext = getApplicationContext();
        executor.execute(() -> {
            try {
                int queued = SmsInboxSynchronizer.sync(appContext);
                ServiceHealthStore.recordHeartbeat(appContext);
                drainPendingQueue(source);
                LOG.info("inbox sync requested source={} queued={}", source, queued);
            } finally {
                syncRunning.set(false);
            }
        });
    }

    private void drainPendingQueue(String source) {
        PairingCredential credential = new SecureTokenStore(this).loadCredential();
        if (credential == null || credential.requiresSecureRePairing()) return;
        String macBaseUrl = PairingStore.loadMacBaseUrl(this);
        if (macBaseUrl.isEmpty()) return;
        LOG.info("delivery drain requested source={}", source);
        DeliveryWorker.drainAsync(this, macBaseUrl);
    }

    private boolean hasReadSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        ensureNotificationChannel();
        Context localized = AppLocale.wrap(this);
        String title = localized.getString(R.string.android_service_notification_title);
        String text = localized.getString(R.string.android_service_notification_text);
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sms_bridge)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Context localized = AppLocale.wrap(this);
        String title = localized.getString(R.string.android_service_notification_title);
        String channelDescription = localized.getString(R.string.android_service_notification_channel_description);
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            title,
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(channelDescription);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
