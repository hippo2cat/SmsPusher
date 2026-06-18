package com.hippo2cat.smspusher.sms;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public final class MessageEventStoreTest {
    private Context context;
    private MessageEventStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(MessageEventStore.DATABASE_NAME);
        store = new MessageEventStore(context);
    }

    @After
    public void tearDown() {
        store.close();
        context.deleteDatabase(MessageEventStore.DATABASE_NAME);
    }

    @Test
    public void recordPendingUpsertsMessageEvent() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");

        store.recordPending(sms, MessageEvent.Source.BROADCAST, "macOS", 10L);
        store.recordPending(sms, MessageEvent.Source.INBOX, "Test Desktop", 20L);

        List<MessageEvent> events = store.recentActivity(10);
        assertEquals(1, events.size());
        MessageEvent event = events.get(0);
        assertEquals(sms.messageId(), event.messageId);
        assertEquals("TEST-SENDER", event.sender);
        assertEquals("test body 001", event.body);
        assertEquals(MessageEvent.Source.INBOX, event.source);
        assertEquals(MessageEvent.Status.PENDING, event.status);
        assertEquals("Test Desktop", event.destination);
        assertEquals(10L, event.createdAtMillis);
        assertEquals(20L, event.updatedAtMillis);
    }

    @Test
    public void retentionKeepsLatestHundredEvents() {
        for (int index = 0; index < 105; index += 1) {
            store.recordPending(
                sms("sender-" + index, "body-" + index, "2026-06-12T09:00:00Z"),
                MessageEvent.Source.BROADCAST,
                "macOS",
                index
            );
        }

        List<MessageEvent> events = store.recentActivity(200);

        assertEquals(100, events.size());
        assertEquals("sender-104", events.get(0).sender);
        assertEquals("sender-5", events.get(99).sender);
    }

    @Test
    public void recordAttemptStoresLastAttemptTimeWithoutChangingStatus() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        store.recordPending(sms, MessageEvent.Source.BROADCAST, "macOS", 10L);

        store.recordAttempt(sms.messageId(), Instant.parse("2026-06-12T09:00:30Z"), 20L);

        MessageEvent event = store.recentActivity(1).get(0);
        assertEquals(MessageEvent.Status.PENDING, event.status);
        assertEquals("2026-06-12T09:00:30Z", event.lastAttemptAt);
        assertEquals(20L, event.updatedAtMillis);
    }

    @Test
    public void recordForwardedClearsFailureAndSetsForwardedAt() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        store.recordPending(sms, MessageEvent.Source.BROADCAST, "macOS", 10L);
        store.recordFailed(sms.messageId(), "IOException", 20L);

        store.recordForwarded(sms.messageId(), "Test Desktop", Instant.parse("2026-06-12T09:01:00Z"), 30L);

        MessageEvent event = store.recentActivity(1).get(0);
        assertEquals(MessageEvent.Status.FORWARDED, event.status);
        assertEquals("2026-06-12T09:01:00Z", event.forwardedAt);
        assertNull(event.failureReason);
        assertEquals("Test Desktop", event.destination);
    }

    @Test
    public void recordFailedPreservesPendingDetails() {
        IncomingSms sms = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        store.recordPending(sms, MessageEvent.Source.BROADCAST, "macOS", 10L);

        store.recordFailed(sms.messageId(), "SocketTimeoutException", 20L);

        MessageEvent event = store.recentActivity(1).get(0);
        assertEquals(MessageEvent.Status.FAILED, event.status);
        assertEquals("SocketTimeoutException", event.failureReason);
        assertEquals("TEST-SENDER", event.sender);
        assertEquals("test body 001", event.body);
    }

    @Test
    public void byMessageIdsReturnsOnlyRequestedStoredEvents() {
        IncomingSms first = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        IncomingSms second = sms("10010", "balance", "2026-06-12T09:02:00Z");
        store.recordPending(first, MessageEvent.Source.BROADCAST, "macOS", 10L);
        store.recordPending(second, MessageEvent.Source.INBOX, "macOS", 20L);

        Map<String, MessageEvent> events = store.byMessageIds(Arrays.asList(first.messageId(), "missing"));

        assertEquals(1, events.size());
        assertEquals("TEST-SENDER", events.get(first.messageId()).sender);
        assertFalse(events.containsKey(second.messageId()));
    }

    @Test
    public void unresolvedMessagesExcludeForwardedEvents() {
        IncomingSms forwarded = sms("TEST-SENDER", "test body 001", "2026-06-12T09:00:00Z");
        IncomingSms failed = sms("10010", "balance", "2026-06-12T09:02:00Z");
        store.recordPending(forwarded, MessageEvent.Source.BROADCAST, "macOS", 10L);
        store.recordForwarded(forwarded.messageId(), "macOS", Instant.parse("2026-06-12T09:00:30Z"), 20L);
        store.recordPending(failed, MessageEvent.Source.INBOX, "macOS", 30L);
        store.recordFailed(failed.messageId(), "SocketTimeoutException", 40L);

        List<MessageEvent> unresolved = store.unresolvedMessages(10);

        assertEquals(1, unresolved.size());
        assertEquals(failed.messageId(), unresolved.get(0).messageId);
        assertEquals(MessageEvent.Status.FAILED, unresolved.get(0).status);
        assertEquals("SocketTimeoutException", unresolved.get(0).failureReason);
    }

    private static IncomingSms sms(String sender, String body, String receivedAt) {
        return new IncomingSms(sender, body, Instant.parse(receivedAt), 1, "dev_1");
    }
}
