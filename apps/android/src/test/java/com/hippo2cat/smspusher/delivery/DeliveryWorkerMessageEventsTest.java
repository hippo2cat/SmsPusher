package com.hippo2cat.smspusher.delivery;

import android.content.Context;

import com.hippo2cat.smspusher.sms.IncomingSms;
import com.hippo2cat.smspusher.sms.MessageEvent;
import com.hippo2cat.smspusher.sms.MessageEventStore;
import com.hippo2cat.smspusher.sms.PendingMessage;
import com.hippo2cat.smspusher.state.PairingStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public final class DeliveryWorkerMessageEventsTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(MessageEventStore.DATABASE_NAME);
        context.getSharedPreferences("sms_bridge_processed_sms", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sms_bridge_delivery_queue", Context.MODE_PRIVATE).edit().clear().commit();
        PairingStore.clear(context);
    }

    @Test
    public void enqueueIncomingSmsRecordsPendingEvent() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");

        boolean queued = DeliveryWorker.enqueueIncomingSms(context, sms, "broadcast");

        List<MessageEvent> events = recentActivity(10);
        assertTrue(queued);
        assertEquals(1, events.size());
        assertEquals(sms.messageId(), events.get(0).messageId);
        assertEquals(MessageEvent.Status.PENDING, events.get(0).status);
        assertEquals(MessageEvent.Source.BROADCAST, events.get(0).source);
    }

    @Test
    public void duplicateSmsDoesNotCreateAnotherEvent() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");

        DeliveryWorker.enqueueIncomingSms(context, sms, "broadcast");
        boolean second = DeliveryWorker.enqueueIncomingSms(context, sms, "broadcast");

        assertTrue(!second);
        assertEquals(1, recentActivity(10).size());
    }

    @Test
    public void pendingMessagesReturnQueueBackedDetails() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        DeliveryWorker.enqueueIncomingSms(context, sms, "inbox");

        List<PendingMessage> pending = DeliveryWorker.pendingMessages(context, 10);

        assertEquals(1, pending.size());
        assertEquals(sms.messageId(), pending.get(0).messageId);
        assertEquals("TEST-SENDER", pending.get(0).sender);
        assertEquals("test body 001", pending.get(0).body);
        assertEquals(MessageEvent.Status.PENDING, pending.get(0).status);
    }

    @Test
    public void pendingMessagesIncludeFailedEventsWhenQueueIsEmpty() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        DeliveryWorker.enqueueIncomingSms(context, sms, "inbox");
        DeliveryWorker.clearPending(context);
        MessageEventStore store = new MessageEventStore(context);
        try {
            store.recordFailed(sms.messageId(), "SocketTimeoutException", 20L);
        } finally {
            store.close();
        }

        List<PendingMessage> pending = DeliveryWorker.pendingMessages(context, 10);

        assertEquals(1, pending.size());
        assertEquals(sms.messageId(), pending.get(0).messageId);
        assertEquals("TEST-SENDER", pending.get(0).sender);
        assertEquals("test body 001", pending.get(0).body);
        assertEquals(MessageEvent.Status.FAILED, pending.get(0).status);
        assertEquals("SocketTimeoutException", pending.get(0).failureReason);
    }

    @Test
    public void restoredFailedMessageIsOverwrittenWhenRetrySucceeds() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        DeliveryWorker.enqueueIncomingSms(context, sms, "inbox");
        DeliveryWorker.clearPending(context);
        MessageEventStore store = new MessageEventStore(context);
        try {
            store.recordFailed(sms.messageId(), "SocketTimeoutException", 20L);
        } finally {
            store.close();
        }
        PersistentDeliveryQueue queue = new PersistentDeliveryQueue(context, 100);

        int restored = DeliveryWorker.restoreUnresolvedMessages(context, queue, 10);
        DeliveryWorker.messageActivityRecorder(context)
            .recordForwardedMessage(sms.messageId(), Instant.parse("2026-06-12T09:01:00Z"));
        queue.markAccepted(sms.messageId());

        MessageEvent event = recentActivity(1).get(0);
        assertEquals(1, restored);
        assertEquals(0, DeliveryWorker.pendingMessages(context, 10).size());
        assertEquals(MessageEvent.Status.FORWARDED, event.status);
        assertEquals("2026-06-12T09:01:00Z", event.forwardedAt);
        assertNull(event.failureReason);
    }

    @Test
    public void messageActivityRecorderWritesDeliveryUpdatesToEventStore() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        DeliveryWorker.enqueueIncomingSms(context, sms, "broadcast");
        DeliverySession.MessageActivityRecorder recorder = DeliveryWorker.messageActivityRecorder(context);

        recorder.recordAttempt(sms.messageId(), Instant.parse("2026-06-12T09:00:30Z"));
        recorder.recordForwardedMessage(sms.messageId(), Instant.parse("2026-06-12T09:01:00Z"));

        MessageEvent event = recentActivity(1).get(0);
        assertEquals(MessageEvent.Status.FORWARDED, event.status);
        assertEquals("2026-06-12T09:00:30Z", event.lastAttemptAt);
        assertEquals("2026-06-12T09:01:00Z", event.forwardedAt);
    }

    private static IncomingSms sms(String sender, String body, String receivedAt) {
        return new IncomingSms(sender, body, Instant.parse(receivedAt), 1, "dev_1");
    }

    private List<MessageEvent> recentActivity(int limit) {
        MessageEventStore store = new MessageEventStore(context);
        try {
            return store.recentActivity(limit);
        } finally {
            store.close();
        }
    }
}
