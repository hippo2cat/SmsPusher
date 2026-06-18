package com.hippo2cat.smspusher.delivery;

public interface BridgeApiFactory {
    BridgeApi create(String baseUrl);
}
