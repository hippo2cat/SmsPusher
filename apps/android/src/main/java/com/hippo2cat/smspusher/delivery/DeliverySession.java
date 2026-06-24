package com.hippo2cat.smspusher.delivery;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.discovery.MacEndpointResolution;
import com.hippo2cat.smspusher.discovery.MacEndpointResolver;
import com.hippo2cat.smspusher.net.SmsBridgeClient;
import com.hippo2cat.smspusher.sms.IncomingSms;
import com.hippo2cat.smspusher.state.PairingEndpoint;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeliverySession {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");

    public interface QueueStore {
        List<DeliveryQueue.Entry> snapshot();
        void markAccepted(String messageId);
    }

    public interface TokenStore {
        PairingCredential loadCredential();
        void saveCredential(PairingCredential credential);
        void clearCredential();
    }

    public interface PairingStore {
        PairingEndpoint loadEndpoint();
        void saveEndpoint(PairingEndpoint endpoint);
        void clearPairing();
    }

    public interface StatsRecorder {
        void recordForwarded(Instant forwardedAt);
        void recordFailure(String reason);
    }

    public interface MessageActivityRecorder {
        void recordAttempt(String messageId, Instant attemptedAt);
        void recordForwardedMessage(String messageId, Instant forwardedAt);
        void recordFailedMessage(String messageId, String reason, Instant failedAt);
    }

    public interface Clock {
        Instant now();
    }

    private final BridgeApiFactory bridgeApiFactory;
    private final MacEndpointResolver resolver;
    private final TokenStore tokenStore;
    private final PairingStore pairingStore;
    private final StatsRecorder statsRecorder;
    private final Clock clock;
    private final MessageActivityRecorder messageActivityRecorder;

    public DeliverySession(
        BridgeApiFactory bridgeApiFactory,
        MacEndpointResolver resolver,
        TokenStore tokenStore,
        PairingStore pairingStore,
        StatsRecorder statsRecorder,
        Clock clock
    ) {
        this(bridgeApiFactory, resolver, tokenStore, pairingStore, statsRecorder, clock, new MessageActivityRecorder() {
            @Override
            public void recordAttempt(String messageId, Instant attemptedAt) {}

            @Override
            public void recordForwardedMessage(String messageId, Instant forwardedAt) {}

            @Override
            public void recordFailedMessage(String messageId, String reason, Instant failedAt) {}
        });
    }

    public DeliverySession(
        BridgeApiFactory bridgeApiFactory,
        MacEndpointResolver resolver,
        TokenStore tokenStore,
        PairingStore pairingStore,
        StatsRecorder statsRecorder,
        Clock clock,
        MessageActivityRecorder messageActivityRecorder
    ) {
        this.bridgeApiFactory = bridgeApiFactory;
        this.resolver = resolver;
        this.tokenStore = tokenStore;
        this.pairingStore = pairingStore;
        this.statsRecorder = statsRecorder;
        this.clock = clock;
        this.messageActivityRecorder = messageActivityRecorder;
    }

    public void drain(QueueStore queue) {
        PairingCredential credential = tokenStore.loadCredential();
        if (credential == null) {
            LOG.info("delivery drain skipped: missing credential");
            return;
        }
        if (credential.requiresSecureRePairing()) {
            LOG.warn("delivery drain clearing legacy or incomplete credential deviceId={}", credential.deviceId);
            tokenStore.clearCredential();
            pairingStore.clearPairing();
            statsRecorder.recordFailure("secure_pairing_required");
            return;
        }
        PairingEndpoint endpoint = pairingStore.loadEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            LOG.info("delivery drain skipped: missing endpoint deviceId={}", credential.deviceId);
            return;
        }
        List<DeliveryQueue.Entry> entries = queue.snapshot();
        LOG.info("delivery drain start baseUrl={} queued={}", endpoint.baseUrl, entries.size());
        for (DeliveryQueue.Entry entry : entries) {
            try {
                credential = sendAndAccept(queue, endpoint, credential, entry);
            } catch (SmsBridgeClient.PairingRequiredException invalid) {
                if (recordReplayRejection(entry, invalid)) return;
                LOG.warn("delivery rejected pairing messageId={} reason={}", entry.messageId, invalid.reason);
                messageActivityRecorder.recordFailedMessage(entry.messageId, invalid.reason, clock.now());
                tokenStore.clearCredential();
                pairingStore.clearPairing();
                return;
            } catch (IOException firstFailure) {
                MacEndpointResolution recovered;
                try {
                    LOG.warn("delivery failed messageId={} baseUrl={} reason={}", entry.messageId, endpoint.baseUrl, firstFailure.getClass().getSimpleName(), firstFailure);
                    messageActivityRecorder.recordFailedMessage(entry.messageId, firstFailure.getClass().getSimpleName(), clock.now());
                    credential = latestStoredCredential(credential);
                    recovered = recoverEndpoint(endpoint, credential, firstFailure);
                } catch (SmsBridgeClient.PairingRequiredException invalidDuringRecovery) {
                    if (recordReplayRejection(entry, invalidDuringRecovery)) return;
                    LOG.warn("delivery endpoint recovery rejected pairing messageId={} reason={}", entry.messageId, invalidDuringRecovery.reason);
                    messageActivityRecorder.recordFailedMessage(entry.messageId, invalidDuringRecovery.reason, clock.now());
                    tokenStore.clearCredential();
                    pairingStore.clearPairing();
                    return;
                }
                if (recovered == null || !recovered.isUsable()) {
                    LOG.warn("delivery endpoint recovery unavailable messageId={} oldBaseUrl={}", entry.messageId, endpoint.baseUrl);
                    return;
                }
                endpoint = recovered.endpoint;
                credential = recovered.credential;
                tokenStore.saveCredential(credential);
                LOG.info("delivery endpoint recovered baseUrl={}", endpoint.baseUrl);
                try {
                    credential = sendAndAccept(queue, endpoint, credential, entry);
                } catch (SmsBridgeClient.PairingRequiredException invalidAfterRecovery) {
                    if (recordReplayRejection(entry, invalidAfterRecovery)) return;
                    LOG.warn("delivery rejected after recovery messageId={} reason={}", entry.messageId, invalidAfterRecovery.reason);
                    messageActivityRecorder.recordFailedMessage(entry.messageId, invalidAfterRecovery.reason, clock.now());
                    tokenStore.clearCredential();
                    pairingStore.clearPairing();
                    return;
                } catch (Exception retryFailure) {
                    String reason = retryFailure.getClass().getSimpleName();
                    LOG.warn("delivery retry failed messageId={} reason={}", entry.messageId, reason, retryFailure);
                    statsRecorder.recordFailure(reason);
                    messageActivityRecorder.recordFailedMessage(entry.messageId, reason, clock.now());
                    return;
                }
            } catch (Exception failure) {
                String reason = failure.getClass().getSimpleName();
                LOG.warn("delivery failed messageId={} reason={}", entry.messageId, reason, failure);
                statsRecorder.recordFailure(reason);
                messageActivityRecorder.recordFailedMessage(entry.messageId, reason, clock.now());
                return;
            }
        }
    }

    private boolean recordReplayRejection(DeliveryQueue.Entry entry, SmsBridgeClient.PairingRequiredException invalid) {
        if (!"replay_detected".equals(invalid.reason)) return false;
        LOG.warn("delivery replay detected messageId={} reason={}", entry.messageId, invalid.reason);
        statsRecorder.recordFailure(invalid.reason);
        messageActivityRecorder.recordFailedMessage(entry.messageId, invalid.reason, clock.now());
        return true;
    }

    private PairingCredential sendAndAccept(QueueStore queue, PairingEndpoint endpoint, PairingCredential credential, DeliveryQueue.Entry entry)
        throws IOException, SmsBridgeClient.PairingRequiredException {
        Instant attemptAt = clock.now();
        messageActivityRecorder.recordAttempt(entry.messageId, attemptAt);
        BridgeApi client = bridgeApiFactory.create(endpoint.baseUrl);
        PairingCredential reserved = reserveCounter(credential);
        PairingCredential updated = client.sendMessage(credential, IncomingSms.sanitizeJsonControlCharacters(entry.json));
        if (updated.nextCounter < reserved.nextCounter) updated = reserved;
        tokenStore.saveCredential(updated);
        queue.markAccepted(entry.messageId);
        Instant forwardedAt = clock.now();
        statsRecorder.recordForwarded(forwardedAt);
        messageActivityRecorder.recordForwardedMessage(entry.messageId, forwardedAt);
        LOG.info("delivery accepted messageId={} baseUrl={} nextCounter={}", entry.messageId, endpoint.baseUrl, updated.nextCounter);
        return updated;
    }

    private MacEndpointResolution recoverEndpoint(PairingEndpoint endpoint, PairingCredential credential, IOException failure)
        throws SmsBridgeClient.PairingRequiredException {
        statsRecorder.recordFailure(failure.getClass().getSimpleName());
        boolean recoverable = isRecoverableNetworkFailure(failure);
        if (!recoverable) {
            LOG.warn("delivery endpoint recovery skipped recoverable={} serviceIdentity={}", recoverable, endpoint.hasServiceIdentity());
            return null;
        }
        LOG.info("delivery endpoint recovery start serviceName={} oldBaseUrl={}", endpoint.serviceName, endpoint.baseUrl);
        PairingCredential reserved = reserveCounter(credential);
        MacEndpointResolution recovered = resolver.resolve(endpoint, credential);
        if (recovered == null || !recovered.isUsable()) return null;
        pairingStore.saveEndpoint(recovered.endpoint);
        PairingCredential recoveredCredential = recovered.credential.nextCounter < reserved.nextCounter
            ? reserved
            : recovered.credential;
        return new MacEndpointResolution(recovered.endpoint, recoveredCredential);
    }

    private PairingCredential reserveCounter(PairingCredential credential) {
        PairingCredential reserved = credential.withNextCounter(credential.nextCounter + 1L);
        tokenStore.saveCredential(reserved);
        return reserved;
    }

    private PairingCredential latestStoredCredential(PairingCredential fallback) {
        PairingCredential latest = tokenStore.loadCredential();
        return latest == null ? fallback : latest;
    }

    public static boolean isRecoverableNetworkFailure(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                || current instanceof ConnectException
                || current instanceof NoRouteToHostException
                || current instanceof UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        String message = failure.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.US);
        return lower.contains("connection refused")
            || lower.contains("connection timed out")
            || lower.contains("no route to host")
            || lower.contains("failed to connect");
    }
}
