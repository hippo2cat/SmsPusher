package com.hippo2cat.smspusher.discovery;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.net.SmsBridgeClient;
import com.hippo2cat.smspusher.state.PairingEndpoint;

public interface MacEndpointResolver {
    MacEndpointResolution resolve(PairingEndpoint current, PairingCredential credential) throws SmsBridgeClient.PairingRequiredException;
}
