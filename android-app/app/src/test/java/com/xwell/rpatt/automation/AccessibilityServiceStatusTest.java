package com.xwell.rpatt.automation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AccessibilityServiceStatusTest {
    @Test
    public void liveBindingOverridesStaleOemManagerLists() {
        assertEquals(AccessibilityServiceStatus.State.CONNECTED,
                AccessibilityServiceStatus.classify(false, false, true));
    }

    @Test
    public void authorizedServiceCanStillBeWaitingForBinding() {
        assertEquals(AccessibilityServiceStatus.State.AUTHORIZED_NOT_CONNECTED,
                AccessibilityServiceStatus.classify(true, true, false));
    }
}
