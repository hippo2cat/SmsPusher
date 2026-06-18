package com.hippo2cat.smspusher.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PairingEndpointTest {
    @Test
    public void manualEndpointKeepsOnlyBaseUrlAndPort() {
        PairingEndpoint endpoint = PairingEndpoint.manual("http://192.0.2.10:55515");

        assertEquals("http://192.0.2.10:55515", endpoint.baseUrl);
        assertEquals("", endpoint.serviceName);
        assertEquals("_smspusher._tcp.", endpoint.serviceType);
        assertEquals(55515, endpoint.port);
        assertFalse(endpoint.hasServiceIdentity());
    }

    @Test
    public void discoveredEndpointStoresServiceIdentity() {
        PairingEndpoint endpoint = PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop");

        assertEquals("http://192.0.2.10:55515", endpoint.baseUrl);
        assertEquals("Test Desktop", endpoint.serviceName);
        assertEquals("_smspusher._tcp.", endpoint.serviceType);
        assertEquals(55515, endpoint.port);
        assertTrue(endpoint.hasServiceIdentity());
    }

    @Test
    public void endpointWithoutExplicitPortUsesDefaultPort() {
        PairingEndpoint endpoint = PairingEndpoint.manual("http://macbook.local");

        assertEquals(55515, endpoint.port);
    }

    @Test
    public void withBaseUrlPreservesBonjourIdentityAndUpdatesPort() {
        PairingEndpoint original = PairingEndpoint.discovered("http://192.0.2.10:55515", "Test Desktop");

        PairingEndpoint updated = original.withBaseUrl("http://192.0.2.20:60000");

        assertEquals("http://192.0.2.20:60000", updated.baseUrl);
        assertEquals("Test Desktop", updated.serviceName);
        assertEquals("_smspusher._tcp.", updated.serviceType);
        assertEquals(60000, updated.port);
        assertTrue(updated.hasServiceIdentity());
    }
}
