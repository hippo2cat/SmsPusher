package com.hippo2cat.smspusher.discovery;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.state.PairingEndpoint;

public final class MacEndpointResolution {
    public final PairingEndpoint endpoint;
    public final PairingCredential credential;

    public MacEndpointResolution(PairingEndpoint endpoint, PairingCredential credential) {
        this.endpoint = endpoint;
        this.credential = credential;
    }

    public boolean isUsable() {
        return endpoint != null && !endpoint.isEmpty() && credential != null && !credential.requiresSecureRePairing();
    }
}
