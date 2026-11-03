package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class SamplingPolicyTest {
    @Test public void firstFrameIsImmediateAndNextIsAtLeast500msLater() {
        SamplingPolicy p = new SamplingPolicy();
        assertEquals(0, p.next(0));
        p.captured(0);
        assertEquals(500, p.next(499));
        assertEquals(500, p.next(500));
    }
    @Test public void repeatedSchedulerWakeupsCannotAdvanceTheDeadline() {
        SamplingPolicy p = new SamplingPolicy();
        p.captured(1000);
        // Click and content-change events wake the same scheduler without changing policy state.
        for (long now = 1100; now < 1500; now += 25) assertEquals(1500, p.next(now));
    }
    @Test public void delayedRequestsDoNotCatchUpOrResetAcrossWindowChanges() {
        SamplingPolicy p = new SamplingPolicy();
        p.captured(0);
        assertEquals(700, p.next(700));
        p.captured(700);
        assertEquals(1200, p.next(700));
        p.invalidate(); // switching windows or restarting does not bypass the minimum
        assertEquals(1200, p.next(900));
        assertEquals(3000, p.next(3000)); // backpressure recovers late
        p.captured(3000);
        assertEquals(3500, p.next(3000));
    }
    @Test public void stopAndBackgroundInvalidate() {
        SamplingPolicy p = new SamplingPolicy();
        int token = p.generation();
        assertTrue(p.valid(token, true, true));
        assertFalse(p.valid(token, true, false));
        assertFalse(p.valid(token, false, true));
        p.invalidate();
        assertFalse(p.valid(token, true, true));
    }
}
