package com.hippo2cat.smspusher.delivery;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.auth.TokenBundle;
import com.hippo2cat.smspusher.net.SmsBridgeClient;
import com.hippo2cat.smspusher.state.PairingEndpoint;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeliverySessionTest {
    private static final TokenBundle LEGACY_TOKEN = new TokenBundle(
        "dev_1",
        "access1",
        Instant.parse("2026-06-06T08:00:00Z"),
        "refresh1",
        Instant.parse("2026-09-03T08:00:00Z")
    );
    private static final PairingCredential CREDENTIAL = PairingCredential.v2(
        "dev_1",
        "key_1",
        "c2VjcmV0",
        5L,
        "Test Desktop",
        Instant.parse("2026-06-15T08:00:00Z")
    );
    private static final PairingCredential LEGACY_CREDENTIAL = PairingCredential.v1(LEGACY_TOKEN);

    @Test
    public void successfulDeliveryUsesSavedEndpoint() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").accept();
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(0, queue.size());
        assertEquals("http://192.0.2.10:55515", stores.savedEndpoint.baseUrl);
        assertFalse(resolver.called);
        assertEquals(1, stores.forwardedCount);
    }

    @Test
    public void networkFailureRecoversEndpointAndRetriesCurrentMessage() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("Connection refused"));
        factory.api("http://192.0.2.20:55515").accept();
        FakeResolver resolver = new FakeResolver();
        resolver.recovered = new com.hippo2cat.smspusher.discovery.MacEndpointResolution(
            PairingEndpoint.discovered("http://192.0.2.20:55515", "Test Desktop"),
            CREDENTIAL
        );
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(0, queue.size());
        assertEquals("http://192.0.2.20:55515", stores.savedEndpoint.baseUrl);
        assertTrue(resolver.called);
        assertEquals(1, stores.forwardedCount);
    }

    @Test
    public void nonRecoverableFailureWithoutServiceIdentityKeepsQueueAndOldEndpoint() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("TLS certificate mismatch"));
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.manual("http://192.0.2.10:55515"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(1, queue.size());
        assertEquals("http://192.0.2.10:55515", stores.savedEndpoint.baseUrl);
        assertFalse(resolver.called);
        assertEquals("IOException", stores.lastFailureReason);
    }

    @Test
    public void networkFailureWithoutServiceIdentityRecoversEndpointAndRetriesCurrentMessage() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("Connection timed out"));
        factory.api("http://192.0.2.20:55515").accept();
        FakeResolver resolver = new FakeResolver();
        resolver.recovered = new com.hippo2cat.smspusher.discovery.MacEndpointResolution(
            PairingEndpoint.discovered("http://192.0.2.20:55515", "Test Desktop"),
            CREDENTIAL
        );
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.manual("http://192.0.2.10:55515"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(0, queue.size());
        assertEquals("http://192.0.2.20:55515", stores.savedEndpoint.baseUrl);
        assertTrue(resolver.called);
        assertEquals(1, stores.forwardedCount);
    }

    @Test
    public void failedRecoveryKeepsQueueAndDoesNotSaveEndpoint() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("No route to host"));
        FakeResolver resolver = new FakeResolver();
        resolver.recovered = null;
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(1, queue.size());
        assertEquals("http://192.0.2.10:55515", stores.savedEndpoint.baseUrl);
        assertTrue(resolver.called);
        assertEquals("IOException", stores.lastFailureReason);
    }

    @Test
    public void authRejectionClearsTokenAndPairing() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").rejectPairing("invalid_token");
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(1, queue.size());
        assertTrue(stores.tokenCleared);
        assertTrue(stores.pairingCleared);
        assertFalse(resolver.called);
    }

    @Test
    public void successfulDeliveryRecordsAttemptAndForwardedEvent() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").accept();
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals("msg_1", stores.lastAttemptedMessageId);
        assertEquals("msg_1", stores.lastForwardedMessageId);
        assertEquals("", stores.lastFailedMessageId);
    }

    @Test
    public void transientFailureRecordsFailedEventAndKeepsQueue() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("Connection timed out"));
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.manual("http://192.0.2.10:55515"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(1, queue.size());
        assertEquals("msg_1", stores.lastFailedMessageId);
        assertEquals("IOException", stores.lastFailureReason);
    }

    @Test
    public void transientFailurePersistsReservedCountersBeforeRecoveryRetry() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("Connection timed out"));
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.manual("http://192.0.2.10:55515"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores, stores);
        session.drain(queue);

        assertTrue(resolver.called);
        assertEquals(7L, stores.credential.nextCounter);
        assertEquals(6L, resolver.verifiedCredential.nextCounter);
    }

    @Test
    public void endpointRecoveryPersistsReservedCounterForVerificationRequest() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").fail(new IOException("No route to host"));
        FakeResolver resolver = new FakeResolver();
        resolver.recovered = null;
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores, stores);
        session.drain(queue);

        assertTrue(resolver.called);
        assertEquals(7L, stores.credential.nextCounter);
        assertEquals(6L, resolver.verifiedCredential.nextCounter);
    }

    @Test
    public void authRejectionRecordsFailedEventBeforeClearingPairing() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").rejectPairing("invalid_token");
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals("msg_1", stores.lastFailedMessageId);
        assertEquals("invalid_token", stores.lastFailureReason);
        assertTrue(stores.tokenCleared);
        assertTrue(stores.pairingCleared);
    }

    @Test
    public void replayRejectionKeepsCredentialAndPairing() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        factory.api("http://192.0.2.10:55515").rejectPairing("replay_detected");
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(1, queue.size());
        assertFalse(stores.tokenCleared);
        assertFalse(stores.pairingCleared);
        assertFalse(resolver.called);
        assertEquals(6L, stores.credential.nextCounter);
        assertEquals("msg_1", stores.lastFailedMessageId);
        assertEquals("replay_detected", stores.lastFailureReason);
    }

    @Test
    public void secureDeliveryClearsLegacyCredentialAndRequiresRePairing() {
        FakeQueue queue = FakeQueue.with("msg_1", "{\"messageId\":\"msg_1\",\"deviceId\":\"dev_1\"}");
        FakeBridgeFactory factory = new FakeBridgeFactory();
        FakeResolver resolver = new FakeResolver();
        FakeStores stores = new FakeStores(LEGACY_CREDENTIAL, PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop"));

        DeliverySession session = new DeliverySession(factory, resolver, stores, stores, stores, stores);
        session.drain(queue);

        assertEquals(1, queue.size());
        assertTrue(stores.tokenCleared);
        assertTrue(stores.pairingCleared);
        assertEquals("secure_pairing_required", stores.lastFailureReason);
    }

    private static final class FakeQueue implements DeliverySession.QueueStore {
        private final ArrayList<DeliveryQueue.Entry> entries = new ArrayList<>();

        static FakeQueue with(String messageId, String json) {
            FakeQueue queue = new FakeQueue();
            queue.entries.add(new DeliveryQueue.Entry(messageId, json));
            return queue;
        }

        @Override
        public List<DeliveryQueue.Entry> snapshot() {
            return new ArrayList<>(entries);
        }

        @Override
        public void markAccepted(String messageId) {
            entries.removeIf(entry -> entry.messageId.equals(messageId));
        }

        int size() {
            return entries.size();
        }
    }

    private static final class FakeBridgeFactory implements BridgeApiFactory {
        private final java.util.Map<String, FakeBridgeApi> apis = new java.util.HashMap<>();

        FakeBridgeApi api(String baseUrl) {
            FakeBridgeApi api = new FakeBridgeApi();
            apis.put(baseUrl, api);
            return api;
        }

        @Override
        public BridgeApi create(String baseUrl) {
            FakeBridgeApi api = apis.get(baseUrl);
            if (api == null) throw new IllegalStateException("No fake API for " + baseUrl);
            return api;
        }
    }

    private static final class FakeBridgeApi implements BridgeApi {
        private Exception failure;
        private boolean accepted;

        void accept() {
            accepted = true;
            failure = null;
        }

        void fail(IOException failure) {
            this.failure = failure;
        }

        void rejectPairing(String reason) {
            this.failure = new SmsBridgeClient.PairingRequiredException(reason);
        }

        @Override
        public PairingCredential sendMessage(PairingCredential credential, String messageJson) throws IOException, SmsBridgeClient.PairingRequiredException {
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof SmsBridgeClient.PairingRequiredException) throw (SmsBridgeClient.PairingRequiredException) failure;
            if (accepted) return credential;
            throw new IOException("Fake API was not configured");
        }
    }

    private static final class FakeResolver implements com.hippo2cat.smspusher.discovery.MacEndpointResolver {
        boolean called;
        PairingCredential verifiedCredential;
        com.hippo2cat.smspusher.discovery.MacEndpointResolution recovered;

        @Override
        public com.hippo2cat.smspusher.discovery.MacEndpointResolution resolve(PairingEndpoint current, PairingCredential credential) {
            called = true;
            verifiedCredential = credential;
            return recovered;
        }
    }

    private static final class FakeStores implements DeliverySession.TokenStore, DeliverySession.PairingStore, DeliverySession.StatsRecorder, DeliverySession.Clock, DeliverySession.MessageActivityRecorder {
        private PairingCredential credential;
        PairingEndpoint savedEndpoint;
        boolean tokenCleared;
        boolean pairingCleared;
        int forwardedCount;
        String lastFailureReason = "";
        String lastAttemptedMessageId = "";
        String lastForwardedMessageId = "";
        String lastFailedMessageId = "";

        FakeStores(PairingCredential credential, PairingEndpoint endpoint) {
            this.credential = credential;
            this.savedEndpoint = endpoint;
        }

        @Override
        public PairingCredential loadCredential() {
            return credential;
        }

        @Override
        public void saveCredential(PairingCredential credential) {
            this.credential = credential;
        }

        @Override
        public void clearCredential() {
            tokenCleared = true;
            credential = null;
        }

        @Override
        public PairingEndpoint loadEndpoint() {
            return savedEndpoint;
        }

        @Override
        public void saveEndpoint(PairingEndpoint endpoint) {
            savedEndpoint = endpoint;
        }

        @Override
        public void clearPairing() {
            pairingCleared = true;
            savedEndpoint = PairingEndpoint.manual("");
        }

        @Override
        public void recordForwarded(Instant forwardedAt) {
            forwardedCount += 1;
        }

        @Override
        public void recordFailure(String reason) {
            lastFailureReason = reason;
        }

        @Override
        public void recordAttempt(String messageId, Instant attemptedAt) {
            lastAttemptedMessageId = messageId;
        }

        @Override
        public void recordForwardedMessage(String messageId, Instant forwardedAt) {
            lastForwardedMessageId = messageId;
        }

        @Override
        public void recordFailedMessage(String messageId, String reason, Instant failedAt) {
            lastFailedMessageId = messageId;
            lastFailureReason = reason;
        }

        @Override
        public Instant now() {
            return Instant.parse("2026-06-11T08:00:00Z");
        }
    }
}
