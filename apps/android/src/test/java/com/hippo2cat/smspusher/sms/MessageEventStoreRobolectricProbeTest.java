package com.hippo2cat.smspusher.sms;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
public final class MessageEventStoreRobolectricProbeTest {
    @Test
    public void robolectricProvidesAndroidContextForSqliteTests() {
        Context context = RuntimeEnvironment.getApplication();

        assertNotNull(context);
        assertNotNull(context.getDatabasePath("probe.db"));
    }
}
