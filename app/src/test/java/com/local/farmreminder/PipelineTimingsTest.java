package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class PipelineTimingsTest {
    @Test public void boundedAndPreservesBothClocks() {
        PipelineTimings timings = new PipelineTimings();
        for (int i = 0; i < 70; i++) timings.record(PipelineTimings.Event.NATIVE_START, i, 100 + i, 50 + i, 0);
        String snapshot = timings.snapshot();
        assertEquals(64, snapshot.split("\n").length);
        assertFalse(snapshot.contains("id=5 "));
        assertTrue(snapshot.contains("id=6 elapsed=106 uptime=56"));
        assertTrue(snapshot.contains("id=69 elapsed=169 uptime=119"));
    }
}
