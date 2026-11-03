package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsageForegroundPolicyTest {
    private static final String WX = "com.tencent.mm", OTHER = "other", APP = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI", APP2 = APP + "1";
    private static boolean allowed(UsageForegroundPolicy p) { return p.canCapture(true, true, true, false); }
    @Test public void candidatesAreExactUniqueAndResumeIdentified() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(1, WX, APP + "1", UsageForegroundPolicy.RESUMED);
        String first = p.candidate(true, true, true, false).key();
        p.event(2, WX, APP + "1", UsageForegroundPolicy.PAUSED);
        p.event(3, WX, APP + "1", UsageForegroundPolicy.RESUMED);
        assertNotEquals(first, p.candidate(true, true, true, false).key());
        p.event(4, WX, APP + "1", UsageForegroundPolicy.PAUSED);
        p.event(5, WX, APP + "00", UsageForegroundPolicy.RESUMED);
        assertEquals(APP + "00", p.candidate(true, true, true, false).className);
        p.event(6, WX, "chat", UsageForegroundPolicy.RESUMED); assertFalse(allowed(p));
        p.event(7, WX, APP + "00", UsageForegroundPolicy.PAUSED); assertFalse(allowed(p));
        p.event(8, WX, "chat", UsageForegroundPolicy.PAUSED);
        p.event(9, WX, APP + "Fake", UsageForegroundPolicy.RESUMED); assertFalse(allowed(p));
    }
    @Test public void emptyAndDeviceGates() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        assertFalse(allowed(p));
        p.event(1, WX, APP, UsageForegroundPolicy.RESUMED);
        assertTrue(allowed(p));
        assertFalse(p.canCapture(false, true, true, false)); // absent or revoked
        assertFalse(p.canCapture(true, false, true, false));
        assertFalse(p.canCapture(true, true, false, false));
        assertFalse(p.canCapture(true, true, true, true));
    }
    @Test public void resumePauseStopAndOther() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(1, WX, APP, UsageForegroundPolicy.RESUMED); assertTrue(allowed(p));
        p.event(2, WX, APP, UsageForegroundPolicy.PAUSED); assertFalse(allowed(p));
        p.event(3, OTHER, "B", UsageForegroundPolicy.RESUMED); assertFalse(allowed(p));
        p.event(4, OTHER, "B", UsageForegroundPolicy.STOPPED); assertFalse(allowed(p));
        p.event(5, WX, APP, UsageForegroundPolicy.RESUMED); assertTrue(allowed(p));
        p.event(6, WX, APP, UsageForegroundPolicy.STOPPED); assertFalse(allowed(p));
    }
    @Test public void crossActivityAndMultiResume() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(1, WX, APP, UsageForegroundPolicy.RESUMED);
        p.event(2, WX, APP2, UsageForegroundPolicy.RESUMED);
        p.event(3, WX, APP, UsageForegroundPolicy.PAUSED); assertTrue(allowed(p));
        p.event(4, OTHER, "C", UsageForegroundPolicy.RESUMED); assertFalse(allowed(p));
        p.event(5, OTHER, "C", UsageForegroundPolicy.PAUSED); assertTrue(allowed(p));
        p.event(6, WX, APP2, UsageForegroundPolicy.PAUSED); assertFalse(allowed(p));
    }
    @Test public void malformedOrUnorderedFailsClosed() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(2, WX, APP, UsageForegroundPolicy.RESUMED);
        p.event(1, WX, APP, UsageForegroundPolicy.PAUSED); assertFalse(allowed(p));
        UsageForegroundPolicy q = new UsageForegroundPolicy();
        q.event(1, WX, null, UsageForegroundPolicy.RESUMED); assertFalse(allowed(q));
    }
    @Test public void rebootClearsPreRebootActivities() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(1, WX, APP, UsageForegroundPolicy.RESUMED);
        p.event(2, null, null, UsageForegroundPolicy.RESET); assertFalse(allowed(p));
        p.event(3, null, null, UsageForegroundPolicy.RESET); assertFalse(allowed(p));
        p.event(4, OTHER, "B", UsageForegroundPolicy.RESUMED);
        p.event(5, OTHER, "B", UsageForegroundPolicy.PAUSED); assertFalse(allowed(p));
        p.event(6, WX, APP, UsageForegroundPolicy.RESUMED); assertTrue(allowed(p));
    }
    @Test public void repeatedResumeRemainsAmbiguousUntilReset() {
        for (int end : new int[] {UsageForegroundPolicy.PAUSED, UsageForegroundPolicy.STOPPED}) {
            UsageForegroundPolicy p = new UsageForegroundPolicy();
            p.event(1, WX, APP, UsageForegroundPolicy.RESUMED);
            p.event(2, OTHER, "B", UsageForegroundPolicy.RESUMED);
            p.event(3, OTHER, "B", UsageForegroundPolicy.RESUMED);
            p.event(4, OTHER, "B", end); assertFalse(allowed(p));
            p.event(5, null, null, UsageForegroundPolicy.RESET); assertFalse(allowed(p));
            p.event(6, WX, APP, UsageForegroundPolicy.RESUMED); assertTrue(allowed(p));
        }
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(1, WX, APP, UsageForegroundPolicy.RESUMED);
        p.event(2, WX, APP, UsageForegroundPolicy.RESUMED); assertFalse(allowed(p));
        p.event(3, WX, APP, UsageForegroundPolicy.PAUSED); assertFalse(allowed(p));
    }
    @Test public void resetDoesNotBypassTimestampOrdering() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        p.event(2, WX, APP, UsageForegroundPolicy.RESUMED);
        p.event(1, null, null, UsageForegroundPolicy.RESET);
        p.event(3, WX, APP2, UsageForegroundPolicy.RESUMED); assertFalse(allowed(p));
        UsageForegroundPolicy q = new UsageForegroundPolicy();
        q.event(2, null, null, UsageForegroundPolicy.RESET);
        q.event(1, WX, APP, UsageForegroundPolicy.RESUMED); assertFalse(allowed(q));
    }
    @Test public void staleCaptureIsNotAdmitted() {
        assertTrue(CaptureAdmissionPolicy.allowed(true, true, true, true, "a", "a"));
        assertFalse(CaptureAdmissionPolicy.allowed(false, true, true, true, "a", "a"));
        assertFalse(CaptureAdmissionPolicy.allowed(true, true, true, true, "a", "b"));
        assertFalse(CaptureAdmissionPolicy.allowed(true, true, false, true, "a", "a"));
        assertFalse(CaptureAdmissionPolicy.allowed(true, true, true, false, "a", "a"));
        assertFalse(CaptureAdmissionPolicy.allowed(true, false, true, true, "a", "a"));
    }
    @Test public void triStateOnlyExplicitSingleNonCandidateConfirmsDeparture() {
        UsageForegroundPolicy p = new UsageForegroundPolicy();
        assertEquals(UsageForegroundPolicy.State.UNKNOWN, p.state(true, true, true, false));
        p.event(1, WX, APP, UsageForegroundPolicy.RESUMED);
        assertEquals(UsageForegroundPolicy.State.CANDIDATE, p.state(true, true, true, false));
        assertEquals(UsageForegroundPolicy.State.UNKNOWN, p.state(false, true, true, false));
        assertEquals(UsageForegroundPolicy.State.UNKNOWN, p.state(true, true, true, true));
        p.event(2, WX, APP, UsageForegroundPolicy.PAUSED);
        assertEquals(UsageForegroundPolicy.State.UNKNOWN, p.state(true, true, true, false));
        p.event(3, OTHER, "B", UsageForegroundPolicy.RESUMED);
        assertEquals(UsageForegroundPolicy.State.CONFIRMED_NON_CANDIDATE, p.state(true, true, true, false));
        p.event(4, WX, APP, UsageForegroundPolicy.RESUMED);
        assertEquals(UsageForegroundPolicy.State.UNKNOWN, p.state(true, true, true, false));
    }
}
