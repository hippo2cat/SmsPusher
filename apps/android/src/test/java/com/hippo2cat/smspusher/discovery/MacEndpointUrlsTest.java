package com.hippo2cat.smspusher.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class MacEndpointUrlsTest {
    @Test
    public void formatsIpv4EndpointUrl() throws Exception {
        String baseUrl = MacEndpointUrls.baseUrl(InetAddress.getByName("192.0.2.10"), 55515);

        assertEquals("http://192.0.2.10:55515", baseUrl);
    }

    @Test
    public void formatsIpv6EndpointUrlWithBrackets() throws Exception {
        String baseUrl = MacEndpointUrls.baseUrl(InetAddress.getByName("2001:db8::10"), 55515);

        assertEquals("http://[2001:db8:0:0:0:0:0:10]:55515", baseUrl);
    }

    @Test
    public void formatsLinkLocalAddressFromNsdInsteadOfFilteringIt() throws Exception {
        String baseUrl = MacEndpointUrls.baseUrl(InetAddress.getByName("fe80::1"), 55515);

        assertEquals("http://[fe80:0:0:0:0:0:0:1]:55515", baseUrl);
    }

    @Test
    public void formatsLoopbackAddressFromNsdInsteadOfFilteringIt() throws Exception {
        String baseUrl = MacEndpointUrls.baseUrl(InetAddress.getByName("127.0.0.1"), 55515);

        assertEquals("http://127.0.0.1:55515", baseUrl);
    }

    @Test
    public void prefersTxtIpv4WhenResolvedHostIsLinkLocal() throws Exception {
        String baseUrl = MacEndpointUrls.baseUrl(
            Map.of("ipv4", "192.166.11.246".getBytes(StandardCharsets.UTF_8)),
            InetAddress.getByName("fe80::1"),
            55515
        );

        assertEquals("http://192.166.11.246:55515", baseUrl);
    }
}
