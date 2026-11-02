package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorSessionPolicyTest {
    @Test public void stopNeverStartsAndUnknownIsIgnored() {
        assertEquals(MonitorSessionPolicy.Decision.STOP,
                MonitorSessionPolicy.decide(MonitorSessionPolicy.Action.STOP, false, false));
        assertEquals(MonitorSessionPolicy.Decision.STOP,
                MonitorSessionPolicy.decide(MonitorSessionPolicy.Action.STOP, true, true));
        assertEquals(MonitorSessionPolicy.Decision.IGNORE,
                MonitorSessionPolicy.decide(MonitorSessionPolicy.Action.IGNORE, false, false));
    }

    @Test public void repeatedStartKeepsRunningSessionWithoutReset() {
        assertEquals(MonitorSessionPolicy.Decision.START,
                MonitorSessionPolicy.decide(MonitorSessionPolicy.Action.START, false, false));
        assertEquals(MonitorSessionPolicy.Decision.KEEP,
                MonitorSessionPolicy.decide(MonitorSessionPolicy.Action.START, true, true));
        assertEquals(MonitorSessionPolicy.Decision.STOP,
                MonitorSessionPolicy.decide(MonitorSessionPolicy.Action.START, true, false));
    }

    @Test public void failedPromotionOrDisconnectedMonitoringEndsSession() {
        assertTrue(MonitorSessionPolicy.shouldEnd(false, true, true));
        assertTrue(MonitorSessionPolicy.shouldEnd(true, false, true));
        assertTrue(MonitorSessionPolicy.shouldEnd(true, true, false));
        assertFalse(MonitorSessionPolicy.shouldEnd(true, true, true));
    }
}
