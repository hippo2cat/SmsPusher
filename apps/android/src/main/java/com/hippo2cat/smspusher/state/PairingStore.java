package com.hippo2cat.smspusher.state;

import android.content.Context;
import android.content.SharedPreferences;

public final class PairingStore {
    private static final String PREFS = "sms_bridge_pairing";
    private static final String KEY_MAC_BASE_URL = "macBaseUrl";
    private static final String KEY_MAC_SERVICE_NAME = "macServiceName";
    private static final String KEY_MAC_SERVICE_TYPE = "macServiceType";
    private static final String KEY_MAC_PORT = "macPort";
    private static final String KEY_SECURE_PROTOCOL = "secureProtocol";
    private static final String KEY_PAIRING_SESSION_ID = "pairingSessionId";
    private static final String KEY_PAIRING_EXPIRES_AT = "pairingExpiresAt";

    private PairingStore() {}

    public static void saveMacBaseUrl(Context context, String macBaseUrl) {
        saveEndpoint(context, PairingEndpoint.manual(macBaseUrl));
    }

    public static void saveEndpoint(Context context, PairingEndpoint endpoint) {
        commitOrThrow(preferences(context).edit()
            .putString(KEY_MAC_BASE_URL, endpoint == null ? "" : endpoint.baseUrl)
            .putString(KEY_MAC_SERVICE_NAME, endpoint == null ? "" : endpoint.serviceName)
            .putString(KEY_MAC_SERVICE_TYPE, endpoint == null ? PairingEndpoint.SERVICE_TYPE : endpoint.serviceType)
            .putInt(KEY_MAC_PORT, endpoint == null ? PairingEndpoint.DEFAULT_PORT : endpoint.port)
            .putString(KEY_SECURE_PROTOCOL, endpoint == null ? "" : endpoint.secureProtocol)
            .putString(KEY_PAIRING_SESSION_ID, endpoint == null ? "" : endpoint.pairingSessionId)
            .putString(KEY_PAIRING_EXPIRES_AT, endpoint == null ? "" : endpoint.pairingExpiresAt));
    }

    public static String loadMacBaseUrl(Context context) {
        return loadEndpoint(context).baseUrl;
    }

    public static PairingEndpoint loadEndpoint(Context context) {
        SharedPreferences prefs = preferences(context);
        String baseUrl = prefs.getString(KEY_MAC_BASE_URL, "");
        String serviceName = prefs.getString(KEY_MAC_SERVICE_NAME, "");
        String serviceType = prefs.getString(KEY_MAC_SERVICE_TYPE, PairingEndpoint.SERVICE_TYPE);
        int port = prefs.getInt(KEY_MAC_PORT, PairingEndpoint.portFrom(baseUrl));
        String secureProtocol = prefs.getString(KEY_SECURE_PROTOCOL, "");
        String pairingSessionId = prefs.getString(KEY_PAIRING_SESSION_ID, "");
        String pairingExpiresAt = prefs.getString(KEY_PAIRING_EXPIRES_AT, "");
        return new PairingEndpoint(
            baseUrl,
            serviceName,
            serviceType,
            port,
            secureProtocol,
            pairingSessionId,
            pairingExpiresAt
        );
    }

    public static void clear(Context context) {
        commitOrThrow(preferences(context).edit().clear());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void commitOrThrow(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Unable to persist pairing endpoint state");
        }
    }
}
