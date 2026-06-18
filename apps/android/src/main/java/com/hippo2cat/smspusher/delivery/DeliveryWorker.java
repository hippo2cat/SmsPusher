package com.hippo2cat.smspusher.delivery;

import android.content.Context;
import android.content.Intent;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.SecureTokenStore;
import com.hippo2cat.smspusher.discovery.NsdMacEndpointResolver;
import com.hippo2cat.smspusher.state.ForwardingStatsStore;
import com.hippo2cat.smspusher.state.PairingEndpoint;
import com.hippo2cat.smspusher.state.PairingStore;
import com.hippo2cat.smspusher.sms.IncomingSms;
import com.hippo2cat.smspusher.sms.MessageEvent;
import com.hippo2cat.smspusher.sms.MessageEventStore;
import com.hippo2cat.smspusher.sms.PendingMessage;
import com.hippo2cat.smspusher.sms.SmsIntentParser;

import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");

    private DeliveryWorker() {}

    public static void enqueueFromSmsIntent(Context context, Intent intent) {
        SecureTokenStore tokenStore = new SecureTokenStore(context);
        PairingCredential credential = tokenStore.loadCredential();
        if (credential == null || credential.requiresSecureRePairing()) {
            LOG.info("sms ignored: secure pairing required");
            return;
        }
        IncomingSms sms = new SmsIntentParser().parse(intent, credential.deviceId);
        if (sms == null) {
            LOG.info("sms ignored: unable to parse intent");
            return;
        }
        enqueueIncomingSms(context, sms, "broadcast");
    }

    public static boolean enqueueIncomingSms(Context context, IncomingSms sms, String source) {
        if (sms == null) return false;
        String messageId = sms.messageId();
        if (!new ProcessedSmsStore(context).claim(messageId)) {
            LOG.info("sms skipped duplicate source={} messageId={}", source, messageId);
            return false;
        }
        ForwardingStatsStore.recordReceived(context, sms.receivedAt);
        new PersistentDeliveryQueue(context, 100).enqueue(messageId, sms.toJson());
        try {
            MessageEventStore store = new MessageEventStore(context);
            try {
                store.recordPending(
                    sms,
                    "inbox".equals(source) ? MessageEvent.Source.INBOX : MessageEvent.Source.BROADCAST,
                    "macOS",
                    System.currentTimeMillis()
                );
            } finally {
                store.close();
            }
        } catch (RuntimeException error) {
            LOG.error("message event pending record failed messageId={}", messageId, error);
        }
        LOG.info("sms queued source={} messageId={}", source, messageId);
        String macBaseUrl = PairingStore.loadMacBaseUrl(context);
        if (!macBaseUrl.isEmpty()) drainAsync(context, macBaseUrl);
        return true;
    }

    public static int pendingCount(Context context) {
        return new PersistentDeliveryQueue(context, 100).size();
    }

    public static List<PendingMessage> pendingMessages(Context context, int limit) {
        PersistentDeliveryQueue queue = new PersistentDeliveryQueue(context, 100);
        List<DeliveryQueue.Entry> entries = queue.snapshot();
        int boundedLimit = Math.max(0, limit);
        if (boundedLimit == 0) return new ArrayList<>();
        int queueLimit = Math.min(boundedLimit, entries.size());
        ArrayList<String> ids = new ArrayList<>();
        for (int i = 0; i < queueLimit; i += 1) ids.add(entries.get(i).messageId);
        MessageEventStore store = new MessageEventStore(context);
        Map<String, MessageEvent> events;
        List<MessageEvent> unresolved;
        try {
            events = store.byMessageIds(ids);
            unresolved = store.unresolvedMessages(boundedLimit);
        } finally {
            store.close();
        }
        ArrayList<PendingMessage> pending = new ArrayList<>();
        Set<String> includedIds = new HashSet<>();
        for (int i = 0; i < queueLimit; i += 1) {
            DeliveryQueue.Entry entry = entries.get(i);
            MessageEvent event = events.get(entry.messageId);
            if (event != null) {
                pending.add(pendingMessageFromEvent(event));
            } else {
                pending.add(fallbackPendingMessage(entry));
            }
            includedIds.add(entry.messageId);
        }
        for (MessageEvent event : unresolved) {
            if (pending.size() >= boundedLimit) break;
            if (event == null || includedIds.contains(event.messageId)) continue;
            pending.add(pendingMessageFromEvent(event));
            includedIds.add(event.messageId);
        }
        return pending;
    }

    public static void clearPending(Context context) {
        new PersistentDeliveryQueue(context, 100).clear();
    }

    public static void drainAsync(Context context, String macBaseUrl) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> drain(appContext, macBaseUrl), "SmsBridgeDelivery").start();
    }

    public static void drain(Context context, String macBaseUrl) {
        Context appContext = context.getApplicationContext();
        SecureTokenStore secureTokenStore = new SecureTokenStore(appContext);
        PersistentDeliveryQueue persistentQueue = new PersistentDeliveryQueue(appContext, 100);
        int restored = restoreUnresolvedMessages(appContext, persistentQueue, 100);
        if (restored > 0) LOG.info("delivery restored unresolved messages count={}", restored);
        DeliverySession session = new DeliverySession(
            new SmsBridgeApiFactory(),
            new NsdMacEndpointResolver(appContext),
            new DeliverySession.TokenStore() {
                @Override
                public PairingCredential loadCredential() {
                    return secureTokenStore.loadCredential();
                }

                @Override
                public void saveCredential(PairingCredential credential) {
                    secureTokenStore.saveCredential(credential);
                }

                @Override
                public void clearCredential() {
                    secureTokenStore.clearCredential();
                }
            },
            new DeliverySession.PairingStore() {
                @Override
                public PairingEndpoint loadEndpoint() {
                    return PairingStore.loadEndpoint(appContext);
                }

                @Override
                public void saveEndpoint(PairingEndpoint endpoint) {
                    PairingStore.saveEndpoint(appContext, endpoint);
                }

                @Override
                public void clearPairing() {
                    PairingStore.clear(appContext);
                }
            },
            new DeliverySession.StatsRecorder() {
                @Override
                public void recordForwarded(Instant forwardedAt) {
                    ForwardingStatsStore.recordForwarded(appContext, forwardedAt);
                }

                @Override
                public void recordFailure(String reason) {
                    ForwardingStatsStore.recordFailure(appContext, reason);
                }
            },
            Instant::now,
            messageActivityRecorder(appContext)
        );
        session.drain(new DeliverySession.QueueStore() {
            @Override
            public List<DeliveryQueue.Entry> snapshot() {
                return persistentQueue.snapshot();
            }

            @Override
            public void markAccepted(String messageId) {
                persistentQueue.markAccepted(messageId);
            }
        });
    }

    static int restoreUnresolvedMessages(Context context, PersistentDeliveryQueue queue, int limit) {
        if (context == null || queue == null || limit <= 0) return 0;
        Set<String> queuedIds = new HashSet<>();
        for (DeliveryQueue.Entry entry : queue.snapshot()) queuedIds.add(entry.messageId);
        MessageEventStore store = new MessageEventStore(context);
        List<MessageEvent> unresolved;
        try {
            unresolved = store.unresolvedMessages(limit);
        } finally {
            store.close();
        }
        int restored = 0;
        for (MessageEvent event : unresolved) {
            if (event == null || event.messageId == null || event.messageId.isEmpty()) continue;
            if (queuedIds.contains(event.messageId)) continue;
            String json = eventJson(event);
            if (json.isEmpty()) continue;
            queue.enqueue(event.messageId, json);
            queuedIds.add(event.messageId);
            restored += 1;
        }
        return restored;
    }

    static DeliverySession.MessageActivityRecorder messageActivityRecorder(Context context) {
        Context appContext = context.getApplicationContext();
        return new DeliverySession.MessageActivityRecorder() {
            @Override
            public void recordAttempt(String messageId, Instant attemptedAt) {
                try {
                    MessageEventStore store = new MessageEventStore(appContext);
                    try {
                        store.recordAttempt(messageId, attemptedAt, System.currentTimeMillis());
                    } finally {
                        store.close();
                    }
                } catch (RuntimeException error) {
                    LOG.error("message event delivery update failed messageId={}", messageId, error);
                }
            }

            @Override
            public void recordForwardedMessage(String messageId, Instant forwardedAt) {
                try {
                    MessageEventStore store = new MessageEventStore(appContext);
                    try {
                        store.recordForwarded(messageId, "macOS", forwardedAt, System.currentTimeMillis());
                    } finally {
                        store.close();
                    }
                } catch (RuntimeException error) {
                    LOG.error("message event delivery update failed messageId={}", messageId, error);
                }
            }

            @Override
            public void recordFailedMessage(String messageId, String reason, Instant failedAt) {
                try {
                    MessageEventStore store = new MessageEventStore(appContext);
                    try {
                        store.recordFailed(messageId, reason, System.currentTimeMillis());
                    } finally {
                        store.close();
                    }
                } catch (RuntimeException error) {
                    LOG.error("message event delivery update failed messageId={}", messageId, error);
                }
            }
        };
    }

    private static PendingMessage pendingMessageFromEvent(MessageEvent event) {
        return new PendingMessage(
            event.messageId,
            event.sender,
            event.body,
            event.receivedAt,
            event.status,
            event.failureReason
        );
    }

    private static String eventJson(MessageEvent event) {
        try {
            JSONObject json = new JSONObject();
            json.put("messageId", event.messageId);
            json.put("sender", event.sender == null ? "" : event.sender);
            json.put("body", event.body == null ? "" : event.body);
            json.put("receivedAt", event.receivedAt == null ? "" : event.receivedAt);
            json.put("subscriptionId", event.subscriptionId);
            json.put("deviceId", event.deviceId == null ? "" : event.deviceId);
            return json.toString();
        } catch (Exception invalidEvent) {
            LOG.warn("failed to restore unresolved message event messageId={}", event.messageId, invalidEvent);
            return "";
        }
    }

    private static PendingMessage fallbackPendingMessage(DeliveryQueue.Entry entry) {
        try {
            JSONObject json = new JSONObject(entry.json);
            return new PendingMessage(
                entry.messageId,
                json.optString("sender", ""),
                json.optString("body", ""),
                json.optString("receivedAt", ""),
                MessageEvent.Status.PENDING,
                null
            );
        } catch (Exception invalidJson) {
            return new PendingMessage(entry.messageId, "", "", "", MessageEvent.Status.PENDING, null);
        }
    }
}
