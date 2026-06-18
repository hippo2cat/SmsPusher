package com.hippo2cat.smspusher.state;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public final class ServiceHealthStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ServiceHealthStore.clearHeartbeat(context);
    }

    @Test
    public void connectionHealthRequiresFreshHeartbeatAndAvailableNetwork() {
        ServiceHealthStore.recordHeartbeat(context);
        ServiceHealthStore.recordNetworkAvailable(context);

        assertTrue(ServiceHealthStore.isHealthy(context));
        assertTrue(ServiceHealthStore.isNetworkAvailable(context));
        assertTrue(ServiceHealthStore.isConnectionHealthy(context));
    }

    @Test
    public void lostNetworkMarksConnectionUnhealthyEvenWhenHeartbeatIsFresh() {
        ServiceHealthStore.recordHeartbeat(context);
        ServiceHealthStore.recordNetworkLost(context);

        assertTrue(ServiceHealthStore.isHealthy(context));
        assertFalse(ServiceHealthStore.isNetworkAvailable(context));
        assertFalse(ServiceHealthStore.isConnectionHealthy(context));
    }

    @Test
    public void clearHeartbeatClearsNetworkAvailabilityOverride() {
        ServiceHealthStore.recordHeartbeat(context);
        ServiceHealthStore.recordNetworkLost(context);
        ServiceHealthStore.clearHeartbeat(context);

        assertFalse(ServiceHealthStore.isHealthy(context));
        assertTrue(ServiceHealthStore.isNetworkAvailable(context));
        assertFalse(ServiceHealthStore.isConnectionHealthy(context));
    }
}
