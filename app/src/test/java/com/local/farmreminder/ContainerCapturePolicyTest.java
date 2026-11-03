package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContainerCapturePolicyTest {
    private ContainerCapturePolicy start() {
        ContainerCapturePolicy p = new ContainerCapturePolicy();
        assertTrue(p.observe("key", false)); p.enter("session", 7, false); return p;
    }
    @Test public void oneRequestAndPendingHandoffThenVerified() {
        ContainerCapturePolicy p = start();
        assertTrue(p.canRequest()); assertTrue(p.begin("session", 7, 1));
        assertFalse(p.canRequest()); assertFalse(p.begin("session", 7, 2));
        assertTrue(p.permitsHandoff("session", 7, 1)); assertFalse(p.permitsHandoff("session", 7, 2));
        assertTrue(p.result("session", 7, 1, true, false, true, true)); assertTrue(p.canRequest());
    }
    @Test public void failureAndUnknownKeepBudgetUntilDepartureOrNewKey() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        assertFalse(p.result("session", 7, 1, true, false, true, false));
        assertFalse(p.observe(null, false)); assertFalse(p.observe("key", false)); assertFalse(p.canRequest());
        p.observe(null, true); assertTrue(p.observe("key", false)); p.enter("new", 7, false); assertTrue(p.canRequest());
        p.begin("new", 7, 1); p.fail("new", 7, 1); assertFalse(p.canRequest());
        assertTrue(p.observe("other", false)); p.enter("other", 7, false); assertTrue(p.canRequest());
        p.clear(); assertTrue(p.observe("other", false)); p.enter("restart", 8, false); assertTrue(p.canRequest());
    }
    @Test public void staleReplayAndUntrustedResultsCannotUnlock() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        assertFalse(p.result("old", 7, 1, true, false, true, true));
        assertFalse(p.result("session", 6, 1, true, false, true, true));
        assertFalse(p.result("session", 7, 2, true, false, true, true));
        assertFalse(p.canRequest());
        assertFalse(p.result("session", 7, 1, true, true, true, true)); assertFalse(p.canRequest());
        p.enter("next", 7, false); p.begin("next", 7, 1);
        assertFalse(p.result("next", 7, 1, true, false, false, true)); assertFalse(p.canRequest());
        p.enter("third", 7, false); p.begin("third", 7, 1);
        p.fail("old", 7, 1); assertEquals(ContainerCapturePolicy.State.PENDING, p.state());
        p.fail("third", 7, 1); assertFalse(p.canRequest());
    }
    @Test public void shizukuDoesNotRequireIdentity() {
        ContainerCapturePolicy p = new ContainerCapturePolicy(); p.enter("s", 1, true);
        assertTrue(p.begin("s", 1, 1)); assertTrue(p.canRequest());
        assertTrue(p.permitsHandoff("s", 1, 1)); assertTrue(p.begin("s", 1, 2));
    }
}
