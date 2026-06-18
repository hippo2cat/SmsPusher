package com.hippo2cat.smspusher.sms;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.provider.Telephony;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.SecureTokenStore;
import com.hippo2cat.smspusher.delivery.DeliveryWorker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmsInboxSynchronizer {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private static final String[] PROJECTION_WITH_SUBSCRIPTION = {
        BaseColumns._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.SUBSCRIPTION_ID
    };
    private static final String[] PROJECTION_BASE = {
        BaseColumns._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT
    };

    private SmsInboxSynchronizer() {}

    public static int sync(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            LOG.info("inbox sync skipped: missing READ_SMS permission");
            return 0;
        }
        PairingCredential credential = new SecureTokenStore(appContext).loadCredential();
        if (credential == null || credential.requiresSecureRePairing()) {
            LOG.info("inbox sync skipped: secure pairing required");
            return 0;
        }

        long now = System.currentTimeMillis();
        SmsInboxSyncState state = new SmsInboxSyncState(appContext);
        long lowerBound = SmsInboxScanPolicy.lowerBoundMillis(state.lastScanAtMillis(), now);
        List<InboxRecord> records;
        try {
            records = queryRecentInbox(appContext, credential.deviceId, lowerBound);
        } catch (Exception error) {
            LOG.error("inbox sync query failed", error);
            return 0;
        }
        int queued = 0;
        for (InboxRecord record : records) {
            if (DeliveryWorker.enqueueIncomingSms(appContext, record.sms, "inbox")) queued++;
        }
        state.saveLastScanAtMillis(now);
        LOG.info("inbox sync completed lowerBound={} scanned={} queued={}", lowerBound, records.size(), queued);
        return queued;
    }

    private static List<InboxRecord> queryRecentInbox(Context context, String deviceId, long lowerBoundMillis) throws Exception {
        try {
            return queryRecentInbox(context, deviceId, lowerBoundMillis, PROJECTION_WITH_SUBSCRIPTION);
        } catch (Exception firstFailure) {
            LOG.warn("inbox sync retrying without subscription column", firstFailure);
            return queryRecentInbox(context, deviceId, lowerBoundMillis, PROJECTION_BASE);
        }
    }

    private static List<InboxRecord> queryRecentInbox(Context context, String deviceId, long lowerBoundMillis, String[] projection) throws Exception {
        List<InboxRecord> records = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            Telephony.Sms.DATE + ">=?",
            new String[] { String.valueOf(lowerBoundMillis) },
            Telephony.Sms.DATE + " DESC"
        )) {
            if (cursor == null) return records;
            while (cursor.moveToNext() && records.size() < SmsInboxScanPolicy.MAX_ROWS_PER_SCAN) {
                InboxRecord record = recordFrom(cursor, deviceId);
                if (record != null) records.add(record);
            }
        }
        Collections.reverse(records);
        return records;
    }

    private static InboxRecord recordFrom(Cursor cursor, String deviceId) {
        int senderIndex = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
        int bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY);
        int dateIndex = cursor.getColumnIndex(Telephony.Sms.DATE);
        if (senderIndex < 0 || bodyIndex < 0 || dateIndex < 0) return null;

        String sender = cursor.getString(senderIndex);
        String body = cursor.getString(bodyIndex);
        if (sender == null || sender.isEmpty() || body == null || body.isEmpty()) return null;

        long providerDate = cursor.getLong(dateIndex);
        long dateSent = longColumn(cursor, Telephony.Sms.DATE_SENT, 0L);
        long receivedAt = dateSent > 0L ? dateSent : providerDate;
        int subscriptionId = (int) longColumn(cursor, Telephony.Sms.SUBSCRIPTION_ID, -1L);
        IncomingSms sms = new IncomingSms(sender, body, Instant.ofEpochMilli(receivedAt), subscriptionId, deviceId);
        return new InboxRecord(sms);
    }

    private static long longColumn(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return fallback;
        return cursor.getLong(index);
    }

    private static final class InboxRecord {
        final IncomingSms sms;

        InboxRecord(IncomingSms sms) {
            this.sms = sms;
        }
    }
}
