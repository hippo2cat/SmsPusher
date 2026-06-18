package com.hippo2cat.smspusher.sms;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class IncomingSmsTest {
    @Test
    public void messageIdIsDeterministicForSameSms() {
        IncomingSms first = new IncomingSms("TEST-SENDER", "hello", Instant.parse("2026-06-05T08:05:12Z"), 1, "dev_1");
        IncomingSms second = new IncomingSms("TEST-SENDER", "hello", Instant.parse("2026-06-05T08:05:12Z"), 1, "dev_1");

        assertEquals(first.messageId(), second.messageId());
        assertTrue(first.messageId().startsWith("msg_"));
    }

    @Test
    public void toJsonIncludesRequiredFields() {
        IncomingSms sms = new IncomingSms("TEST-SENDER", "hello", Instant.parse("2026-06-05T08:05:12Z"), 1, "dev_1");

        String json = sms.toJson();

        assertTrue(json.contains("\"sender\":\"TEST-SENDER\""));
        assertTrue(json.contains("\"body\":\"hello\""));
        assertTrue(json.contains("\"deviceId\":\"dev_1\""));
    }

    @Test
    public void toJsonEscapesJsonControlCharactersInBody() {
        IncomingSms sms = new IncomingSms("TEST-SENDER", "line1\nline2\tend", Instant.parse("2026-06-05T08:05:12Z"), 1, "dev_1");

        String json = sms.toJson();

        assertTrue(json.contains("\"body\":\"line1\\nline2\\tend\""));
        assertTrue(!json.contains("line1\nline2"));
    }

    @Test
    public void sanitizeJsonControlCharactersRepairsLegacyQueuedPayloads() {
        String legacy = "{\"body\":\"line1\nline2\",\"deviceId\":\"dev_1\"}";

        String repaired = IncomingSms.sanitizeJsonControlCharacters(legacy);

        assertEquals("{\"body\":\"line1\\nline2\",\"deviceId\":\"dev_1\"}", repaired);
    }
}
