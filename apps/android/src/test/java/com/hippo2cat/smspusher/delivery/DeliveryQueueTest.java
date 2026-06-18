package com.hippo2cat.smspusher.delivery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DeliveryQueueTest {
    @Test
    public void queueKeepsLatestHundredEntries() {
        DeliveryQueue queue = new DeliveryQueue(100);

        for (int i = 0; i < 120; i++) queue.enqueue("msg_" + i, "{}");

        assertEquals(100, queue.size());
        assertEquals("msg_20", queue.peek().messageId);
    }

    @Test
    public void acceptedMessageIsRemoved() {
        DeliveryQueue queue = new DeliveryQueue(100);
        queue.enqueue("msg_1", "{}");

        queue.markAccepted("msg_1");

        assertEquals(0, queue.size());
    }

    @Test
    public void duplicateMessageIdsDoNotCreateDuplicateQueueEntries() {
        DeliveryQueue queue = new DeliveryQueue(100);

        queue.enqueue("msg_1", "{\"body\":\"first\"}");
        queue.enqueue("msg_1", "{\"body\":\"second\"}");

        assertEquals(1, queue.size());
        assertEquals("{\"body\":\"second\"}", queue.peek().json);
    }

    @Test
    public void clearRemovesAllQueuedMessages() {
        DeliveryQueue queue = new DeliveryQueue(100);
        queue.enqueue("msg_1", "{}");
        queue.enqueue("msg_2", "{}");

        queue.clear();

        assertEquals(0, queue.size());
    }
}
