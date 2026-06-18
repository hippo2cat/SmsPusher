package com.hippo2cat.smspusher.state;

import java.net.URI;

public final class PairingEndpoint {
    public static final String SERVICE_TYPE = "_smspusher._tcp.";
    public static final int DEFAULT_PORT = 55515;

    public final String baseUrl;
    public final String serviceName;
    public final String serviceType;
    public final int port;
    public final String secureProtocol;
    public final String pairingSessionId;
    public final String pairingExpiresAt;

    public PairingEndpoint(String baseUrl, String serviceName, String serviceType, int port) {
        this(baseUrl, serviceName, serviceType, port, "", "", "");
    }

    public PairingEndpoint(
        String baseUrl,
        String serviceName,
        String serviceType,
        int port,
        String secureProtocol,
        String pairingSessionId,
        String pairingExpiresAt
    ) {
        this.baseUrl = normalize(baseUrl);
        this.serviceName = serviceName == null ? "" : serviceName.trim();
        this.serviceType = serviceType == null || serviceType.trim().isEmpty() ? SERVICE_TYPE : serviceType.trim();
        this.port = port > 0 ? port : portFrom(this.baseUrl);
        this.secureProtocol = normalize(secureProtocol);
        this.pairingSessionId = normalize(pairingSessionId);
        this.pairingExpiresAt = normalize(pairingExpiresAt);
    }

    public static PairingEndpoint manual(String baseUrl) {
        return new PairingEndpoint(baseUrl, "", SERVICE_TYPE, portFrom(baseUrl));
    }

    public static PairingEndpoint discovered(String baseUrl, String serviceName) {
        return discovered(baseUrl, serviceName, "", "");
    }

    public static PairingEndpoint discovered(
        String baseUrl,
        String serviceName,
        String pairingSessionId,
        String pairingExpiresAt
    ) {
        return new PairingEndpoint(baseUrl, serviceName, SERVICE_TYPE, portFrom(baseUrl), "v2", pairingSessionId, pairingExpiresAt);
    }

    public boolean hasServiceIdentity() {
        return !serviceName.isEmpty();
    }

    public PairingEndpoint withBaseUrl(String nextBaseUrl) {
        return new PairingEndpoint(
            nextBaseUrl,
            serviceName,
            serviceType,
            portFrom(nextBaseUrl),
            secureProtocol,
            pairingSessionId,
            pairingExpiresAt
        );
    }

    public PairingEndpoint withSecureSession(String nextServiceName, String nextSecureProtocol, String nextSessionId, String nextExpiresAt) {
        return new PairingEndpoint(
            baseUrl,
            nextServiceName == null || nextServiceName.trim().isEmpty() ? serviceName : nextServiceName,
            serviceType,
            port,
            nextSecureProtocol == null || nextSecureProtocol.trim().isEmpty() ? secureProtocol : nextSecureProtocol,
            nextSessionId,
            nextExpiresAt
        );
    }

    public boolean isEmpty() {
        return baseUrl.isEmpty();
    }

    public static int portFrom(String baseUrl) {
        try {
            URI uri = URI.create(normalize(baseUrl));
            return uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        } catch (Exception invalid) {
            return DEFAULT_PORT;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
