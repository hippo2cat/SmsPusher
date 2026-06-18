package com.hippo2cat.smspusher.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.net.SmsBridgeClient;
import com.hippo2cat.smspusher.net.UrlConnectionTransport;
import com.hippo2cat.smspusher.state.PairingEndpoint;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("deprecation")
public final class NsdMacEndpointResolver implements MacEndpointResolver {
    private static final Logger LOG = LoggerFactory.getLogger("SmsBridge");
    private static final long DISCOVERY_TIMEOUT_MS = 6_000L;

    private final NsdManager nsdManager;

    public NsdMacEndpointResolver(Context context) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public MacEndpointResolution resolve(PairingEndpoint current, PairingCredential credential) throws SmsBridgeClient.PairingRequiredException {
        if (nsdManager == null || current == null) return null;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<MacEndpointResolution> resolution = new AtomicReference<>();
        AtomicReference<SmsBridgeClient.PairingRequiredException> authFailure = new AtomicReference<>();
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        AtomicReference<NsdManager.DiscoveryListener> discoveryRef = new AtomicReference<>();
        ExecutorService verificationExecutor = Executors.newSingleThreadExecutor();

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                LOG.info("endpoint recovery discovery started serviceType={}", serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                LOG.warn("endpoint recovery discovery failed code={}", errorCode);
                finished.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!PairingEndpoint.SERVICE_TYPE.equals(serviceInfo.getServiceType())) return;
                if (!(current.serviceName.isEmpty() || current.serviceName.equals(serviceInfo.getServiceName()))) return;
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        LOG.warn("endpoint recovery resolve failed service={} code={}", serviceInfo.getServiceName(), errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        String baseUrl = MacEndpointUrls.baseUrl(serviceInfo);
                        if (baseUrl == null) return;
                        try {
                            verificationExecutor.execute(() -> verifyResolvedEndpoint(
                                current,
                                credential,
                                serviceInfo.getPort(),
                                serviceInfo.getServiceName(),
                                baseUrl,
                                resolution,
                                authFailure,
                                finished
                            ));
                        } catch (RejectedExecutionException rejected) {
                            LOG.warn("endpoint recovery verification executor rejected baseUrl={}", baseUrl, rejected);
                        }
                    }
                });
            }
        };
        discoveryRef.set(listener);

        try {
            nsdManager.discoverServices(current.serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
            finished.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException startFailed) {
            LOG.warn("endpoint recovery discovery start failed", startFailed);
        } finally {
            NsdManager.DiscoveryListener discovery = discoveryRef.get();
            if (discovery != null && stopRequested.compareAndSet(false, true)) {
                try {
                    nsdManager.stopServiceDiscovery(discovery);
                } catch (RuntimeException ignored) {
                    // Discovery may already be stopped by Android after a startup failure.
                }
            }
            verificationExecutor.shutdownNow();
        }

        SmsBridgeClient.PairingRequiredException rejected = authFailure.get();
        if (rejected != null) throw rejected;
        return resolution.get();
    }

    private static void verifyResolvedEndpoint(
        PairingEndpoint current,
        PairingCredential credential,
        int port,
        String serviceName,
        String baseUrl,
        AtomicReference<MacEndpointResolution> resolution,
        AtomicReference<SmsBridgeClient.PairingRequiredException> authFailure,
        CountDownLatch finished
    ) {
        try {
            SmsBridgeClient client = new SmsBridgeClient(baseUrl, new UrlConnectionTransport());
            PairingCredential verified = client.verifyPairing(credential);
            PairingEndpoint endpoint = new PairingEndpoint(
                baseUrl,
                serviceName,
                current.serviceType,
                port,
                current.secureProtocol,
                current.pairingSessionId,
                current.pairingExpiresAt
            );
            resolution.set(new MacEndpointResolution(endpoint, verified));
            finished.countDown();
        } catch (SmsBridgeClient.PairingRequiredException rejected) {
            authFailure.set(rejected);
            finished.countDown();
        } catch (Exception verificationFailed) {
            LOG.warn("endpoint recovery verification failed baseUrl={}", baseUrl, verificationFailed);
        }
    }
}
