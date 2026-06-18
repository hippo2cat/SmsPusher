package com.hippo2cat.smspusher.delivery;

import com.hippo2cat.smspusher.auth.PairingCredential;
import com.hippo2cat.smspusher.net.SmsBridgeClient;

import java.io.IOException;

public interface BridgeApi {
    PairingCredential sendMessage(PairingCredential credential, String messageJson) throws IOException, SmsBridgeClient.PairingRequiredException;
}
