package com.hippo2cat.smspusher.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class MacDiscoveryService {
    public interface Listener {
        void onServicesChanged(List<NsdServiceInfo> services);
        void onError(String message);
    }

    private static final String SERVICE_TYPE = "_smspusher._tcp.";
    private final NsdManager nsdManager;
    private final ArrayList<NsdServiceInfo> services = new ArrayList<>();
    private NsdManager.DiscoveryListener discoveryListener;

    public MacDiscoveryService(Context context) {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void start(Listener listener) {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                listener.onError("Discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                services.removeIf(service -> service.getServiceName().equals(serviceInfo.getServiceName()));
                listener.onServicesChanged(new ArrayList<>(services));
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!SERVICE_TYPE.equals(serviceInfo.getServiceType())) return;
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        listener.onError("Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        if (!isUsableResolvedService(resolved)) return;
                        services.removeIf(service -> service.getServiceName().equals(resolved.getServiceName()));
                        services.add(resolved);
                        listener.onServicesChanged(new ArrayList<>(services));
                    }
                });
            }
        };
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    static boolean isUsableResolvedService(NsdServiceInfo resolved) {
        return MacEndpointUrls.baseUrl(resolved) != null;
    }

    public void stop() {
        if (discoveryListener != null) nsdManager.stopServiceDiscovery(discoveryListener);
        discoveryListener = null;
    }
}
