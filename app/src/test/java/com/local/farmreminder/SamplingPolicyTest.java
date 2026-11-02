package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class SamplingPolicyTest {
    @Test public void burstCoalescesAndRespectsMinimumInterval() {
        SamplingPolicy p = new SamplingPolicy();
        p.event(1000, true); p.event(1100, true);
        assertEquals(1200, p.next(1100, true));
        p.captured(1200); p.event(1250, true);
        assertEquals(1600, p.next(1250, true));
        p.captured(1600); p.captured(2000);
        p.event(2100, true); // flood cannot start another burst yet
        assertEquals(4000, p.next(2100, false));
    }
    @Test public void nonFarmEventFloodDoesNotAccelerateProbes() {
        SamplingPolicy p = new SamplingPolicy();
        p.captured(1000);
        for (long now = 1100; now < 3000; now += 100) {
            p.event(now, false);
            assertEquals(3000, p.next(now, false));
        }
    }
    @Test public void leavingFarmClearsBurstImmediately() {
        SamplingPolicy p = new SamplingPolicy();
        p.captured(1000);
        p.event(1100, true);
        assertEquals(1400, p.next(1100, true));
        p.clearBurst();
        assertEquals(3000, p.next(1200, false));
        assertEquals(1700, p.next(1200, true)); // no stale burst on re-entry
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
