package com.hippo2cat.smspusher.ui;

import com.hippo2cat.smspusher.net.SmsBridgeClient;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public final class PairingFeedbackTest {
    @Test
    public void mapsInvalidPairingCodeToInlineError() {
        PairingFeedback feedback = PairingFeedback.from(new SmsBridgeClient.PairingRequiredException("invalid_pairing_code"), copy());

        assertEquals("Pairing code is not correct.", feedback.inlineError);
        assertEquals("", feedback.sectionError);
    }

    @Test
    public void mapsExpiredPairingCodeToInlineError() {
        PairingFeedback feedback = PairingFeedback.from(new SmsBridgeClient.PairingRequiredException("pairing_code_expired"), copy());

        assertEquals("Pairing code expired. Check the refreshed code in the Mac menu bar.", feedback.inlineError);
        assertEquals("", feedback.sectionError);
    }

    @Test
    public void mapsNetworkFailureToSectionError() {
        PairingFeedback feedback = PairingFeedback.from(new IOException("timeout"), copy());

        assertEquals("", feedback.inlineError);
        assertEquals("Unable to connect to the selected Mac. Confirm both devices are on the same local network.", feedback.sectionError);
    }

    private static PairingFeedback.Copy copy() {
        return new PairingFeedback.Copy(
            "Pairing code is not correct.",
            "Pairing code expired. Check the refreshed code in the Mac menu bar.",
            "This device is not advertising a secure pairing session. Choose the device again.",
            "Unable to connect to the selected Mac. Confirm both devices are on the same local network."
        );
    }
}
