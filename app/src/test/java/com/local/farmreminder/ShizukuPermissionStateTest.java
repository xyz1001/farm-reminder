package com.local.farmreminder;

import static org.junit.Assert.*;
import org.junit.Test;

public class ShizukuPermissionStateTest {
    @Test public void setupKeepsLastGrantWhileRuntimeExpiresAndRefreshIsPending() {
        ShizukuPermissionState state = new ShizukuPermissionState();
        state.grant(0);
        for (long now : new long[] {0, 2999, 3000, 10000}) {
            assertTrue(state.knownGrant());
            assertEquals(now < 3000, state.fresh(now));
            assertEquals(now < 3000, state.runtimeReady(true, true, now, 0));
        }
        // An in-flight refresh does not change the last known result.
        assertTrue(state.knownGrant());
        assertFalse(state.runtimeReady(true, true, 10000, 0));
        state.grant(10001);
        assertTrue(state.knownGrant());
        assertTrue(state.runtimeReady(true, true, 10001, 0));
        assertFalse(state.runtimeReady(true, true, 10001, GateState.TTL));
        assertFalse(state.runtimeReady(false, true, 10001, 0));
        assertFalse(state.runtimeReady(true, false, 10001, 0));
    }

    @Test public void allInvalidationEventsClearSetupAndRuntimeImmediately() {
        // Explicit denial, binder death, unsupported uid, new binder, check failure.
        for (int event = 0; event < 5; event++) {
            ShizukuPermissionState state = new ShizukuPermissionState();
            state.grant(0);
            state.invalidate();
            assertFalse(state.knownGrant());
            assertFalse(state.runtimeReady(true, true, 1, 0));
        }
    }

    @Test public void idleEnabledPollsWithoutTaskQuery() {
        assertTrue(ShizukuPermissionState.shouldPoll(true));
        assertFalse(ShizukuPermissionState.shouldQuery(false, true));
        assertTrue(ShizukuPermissionState.shouldQuery(true, true));
        assertFalse(ShizukuPermissionState.shouldPoll(false));
        assertFalse(ShizukuPermissionState.shouldQuery(true, false));
    }
}
