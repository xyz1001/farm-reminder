package com.local.farmreminder;

import static org.junit.Assert.*;
import org.junit.Test;

public class ForegroundSourcePolicyTest {
    private static String select(boolean shizuku, boolean match, boolean fresh, boolean permission,
                                 boolean unlocked, boolean interactive, boolean locked, String task) {
        return ForegroundSourcePolicy.key(shizuku, "usage-candidate", match, fresh, permission,
                unlocked, interactive, locked, task, "component");
    }

    @Test public void sourceDoesNotFallbackAndRequiresFreshMatch() {
        assertEquals("usage\u0000usage-candidate", select(false, false, false, false, true, true, false, null));
        assertNull(select(true, false, true, true, true, true, false, "task"));
        assertNull(select(true, true, false, true, true, true, false, "task"));
        assertNull(select(true, true, true, false, true, true, false, "task"));
        assertNull(select(true, true, true, true, false, true, false, "task"));
        assertNull(select(true, true, true, true, true, false, false, "task"));
        assertNull(select(true, true, true, true, true, true, true, "task"));
        assertNull(select(true, true, true, true, true, true, false, null));
        assertNotNull(select(true, true, true, true, true, true, false, "task"));
    }

    @Test public void stableTaskKeyAndCaptureSessionFence() {
        String first = select(true, true, true, true, true, true, false, "task-a");
        assertEquals(first, select(true, true, true, true, true, true, false, "task-a"));
        assertNotEquals(first, select(true, true, true, true, true, true, false, "task-b"));
        assertNull(select(true, false, true, true, true, true, false, "task-a"));
        assertEquals(first, select(true, true, true, true, true, true, false, "task-a"));
        // Service creates a new capture session on each source-key transition and run restart.
        assertFalse(CaptureAdmissionPolicy.allowed(true, true, true, true, "old", "new"));
        assertFalse(CaptureAdmissionPolicy.allowed(true, true, false, false, "old", "old"));
        assertFalse(CaptureAdmissionPolicy.allowed(true, true, true, true, "old", "restarted"));
        assertTrue(CaptureAdmissionPolicy.allowed(true, true, true, true, "new", "new"));
    }
}
