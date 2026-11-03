package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContainerCapturePolicyTest {
    @Test public void modeControlsDeadlineWithoutChangingForegroundAdmission() {
        ContainerCapturePolicy p = new ContainerCapturePolicy();
        p.enter("shizuku", 1, 0, true);
        assertTrue(p.allows(21000)); assertFalse(p.expire(21000));
        assertTrue(p.recognize("shizuku", 1, true, 21000));
        assertNull(ForegroundSourcePolicy.key(true, null, true, false, true, true, true, false, "task", "component"));
        assertNull(ForegroundSourcePolicy.key(true, null, false, true, true, true, true, false, "task", "component"));
        p.enter("usage", 1, 0, false);
        assertFalse(p.allows(21000)); assertTrue(p.timedOut());
        assertFalse(p.recognize("usage", 1, true, 21001));
    }
    @Test public void announcementSurvivesUnknownButResetsOnConfirmedDepartureOrStop() {
        CaptureAnnouncementPolicy p = new CaptureAnnouncementPolicy();
        assertTrue(p.onAcceptedFrame());
        // UNKNOWN and stale snapshots do not call confirmedDeparture.
        assertFalse(p.onAcceptedFrame());
        p.confirmedDeparture(); assertTrue(p.onAcceptedFrame());
        assertFalse(p.onAcceptedFrame()); p.reset(); assertTrue(p.onAcceptedFrame());
    }
    @Test public void deadlineIsInclusiveAndOnlyExpiresOnce() {
        ContainerCapturePolicy p = new ContainerCapturePolicy(); p.enter("a", 1, 100);
        assertTrue(p.allows(20099)); assertTrue(p.expire(20100));
        assertFalse(p.expire(20101)); assertFalse(p.allows(999999));
        assertFalse(p.recognize("a", 1, true, 20101));
    }
    @Test public void validObservationPassesWithoutRequiringAdditionOrName() {
        ContainerCapturePolicy p = new ContainerCapturePolicy(); p.enter("a", 1, 0);
        assertFalse(p.recognize("a", 1, false, 1)); // NO_MATCH
        assertFalse(p.recognize("old", 1, true, 1));
        assertFalse(p.recognize("a", 0, true, 1));
        assertTrue(p.recognize("a", 1, true, 19999)); // duplicate also valid
        assertTrue(p.allows(1000000)); assertFalse(p.expire(1000000));
    }
    @Test public void freshContextAndStopResetBudgetAndFenceOldTimer() {
        ContainerCapturePolicy p = new ContainerCapturePolicy(); p.enter("a", 1, 0);
        assertTrue(p.expire(20000)); p.enter("b", 1, 20001);
        assertFalse(p.matches("a", 1)); assertFalse(p.recognize("a", 1, true, 20002));
        assertTrue(p.allows(40000)); p.clear(); assertFalse(p.allows(40000));
        p.enter("c", 2, 50000); assertFalse(p.matches("b", 1)); assertTrue(p.allows(69999));
        assertFalse(p.recognize("c", 2, true, 70000)); assertTrue(p.expire(70000));
    }
}
