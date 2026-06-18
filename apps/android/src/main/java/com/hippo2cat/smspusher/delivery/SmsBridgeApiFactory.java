package com.hippo2cat.smspusher.delivery;

import com.hippo2cat.smspusher.net.SmsBridgeClient;
import com.hippo2cat.smspusher.net.UrlConnectionTransport;

public final class SmsBridgeApiFactory implements BridgeApiFactory {
    @Override
    public BridgeApi create(String baseUrl) {
        SmsBridgeClient client = new SmsBridgeClient(baseUrl, new UrlConnectionTransport());
        return client::sendMessage;
    }
}
