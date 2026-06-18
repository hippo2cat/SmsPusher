package com.hippo2cat.smspusher.sms;

import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.time.Instant;

public final class SmsIntentParser {
    public IncomingSms parse(Intent intent, String deviceId) {
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return null;
        StringBuilder body = new StringBuilder();
        String sender = messages[0].getDisplayOriginatingAddress();
        long timestamp = messages[0].getTimestampMillis();
        int subscriptionId = intent.getIntExtra("subscription", -1);
        for (SmsMessage message : messages) {
            body.append(message.getMessageBody());
        }
        return new IncomingSms(sender, body.toString(), Instant.ofEpochMilli(timestamp), subscriptionId, deviceId);
    }
}
