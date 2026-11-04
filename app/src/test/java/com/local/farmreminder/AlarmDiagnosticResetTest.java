package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlarmDiagnosticResetTest {
    private static final class FakePendingIntent {
        boolean alarmExists = true;
        String id = "business-id", data = "business-data";
        long token = 1;
        void updateDiagnosticToken(long value) { token = value; }
        void clearExtrasKeepingBusinessIdentity() { token = 0; }
    }
    @Test public void failedReplacementLeavesOldAlarmWithUnknownNotNewToken() {
        FakePendingIntent alarm = new FakePendingIntent();
        alarm.updateDiagnosticToken(2); // FLAG_UPDATE_CURRENT before failed system schedule.
        assertNull(AlarmDiagnosticReset.afterFailure(true, true, alarm::clearExtrasKeepingBusinessIdentity));
        assertTrue(alarm.alarmExists);
        assertEquals(0, alarm.token);
        assertEquals("business-id", alarm.id); assertEquals("business-data", alarm.data);
    }
    @Test public void successfulOrReleaseScheduleDoesNotClearAndResetFailureIsContained() {
        FakePendingIntent alarm = new FakePendingIntent(); alarm.updateDiagnosticToken(2);
        assertNull(AlarmDiagnosticReset.afterFailure(false, true, alarm::clearExtrasKeepingBusinessIdentity));
        assertNull(AlarmDiagnosticReset.afterFailure(true, false, alarm::clearExtrasKeepingBusinessIdentity));
        assertEquals(2, alarm.token);
        RuntimeException error = AlarmDiagnosticReset.afterFailure(true, true, () -> { throw new IllegalStateException("private"); });
        assertTrue(error instanceof IllegalStateException); assertTrue(alarm.alarmExists);
    }
}
