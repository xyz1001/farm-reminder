package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlarmTimingHistoryTest {
    @Test public void historyIsBoundedAndRestoresPrevious() {
        AlarmTimingHistory h = new AlarmTimingHistory();
        for (int i = 1; i <= 70; i++) h.add(i, 100, 90, 10, 120, 40,
                AlarmTimingHistory.Event.SCHEDULE, AlarmTimingHistory.Mode.EXACT, 1, 1, -1, null);
        assertEquals(64, h.serialize().split("\n").length);
        assertTrue(h.serialize().startsWith("7|100|90|10|120|40|"));
        AlarmTimingHistory next = new AlarmTimingHistory(); next.prepend(h.serialize());
        assertEquals(h.serialize(), next.serialize());
    }
    @Test public void invalidTokenAndFileFailOpenAndExceptionIsAnonymous() {
        AlarmTimingHistory h = new AlarmTimingHistory();
        h.add(-1, 99, 98, 97, 96, 95, AlarmTimingHistory.Event.RECEIVE, AlarmTimingHistory.Mode.UNKNOWN, -1, -1, -1, new IllegalStateException("private"));
        h.prepend("invalid\n");
        assertTrue(h.serialize().startsWith("0|0|0|0|96|95|"));
        assertFalse(h.serialize().contains("private"));
        assertTrue(h.serialize().contains("IllegalStateException"));
        assertEquals(0, AlarmTimingHistory.validToken(-1));
    }
    @Test public void wallClockShiftAndElapsedArePreservedWithoutLatenessInference() {
        AlarmTimingHistory h = new AlarmTimingHistory();
        h.add(12, 1000, 900, 100, 400, 300, AlarmTimingHistory.Event.RECEIVE, AlarmTimingHistory.Mode.UNKNOWN, -1, -1, -1, null);
        assertTrue(h.serialize().startsWith("12|1000|900|100|400|300|RECEIVE|"));
        assertFalse(h.serialize().contains("late"));
    }
}
