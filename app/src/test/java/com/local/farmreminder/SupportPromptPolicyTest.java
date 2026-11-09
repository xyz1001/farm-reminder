package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class SupportPromptPolicyTest {
    @Test public void existingValidRemindersOrNewRecognitionQualifyUnlessAlreadyShown() {
        for (int flags = 0; flags < 8; flags++) {
            boolean eligible = (flags & 1) != 0;
            boolean hasExistingReminder = (flags & 2) != 0;
            boolean shown = (flags & 4) != 0;
            assertEquals((eligible || hasExistingReminder) && !shown,
                    SupportPromptPolicy.eligible(eligible, hasExistingReminder, shown));
        }
        // Both a displayed automatic dialog (including decline) and a manual dialog set shown.
        assertFalse(SupportPromptPolicy.eligible(true, true, true));
        assertFalse(SupportPromptPolicy.eligible(false, true, true));
    }

    @Test public void oldValidReminderDoesNotRequireNewRecognitionFlag() {
        // The existing-record input includes fired reminders that have not expired.
        assertTrue(SupportPromptPolicy.eligible(false, true, false));
        assertTrue(SupportPromptPolicy.eligible(true, false, false));
        assertFalse(SupportPromptPolicy.eligible(false, false, false));
    }

    @Test public void onlyFreshCommittedConfirmedFramesQualify() {
        assertTrue(SupportPromptPolicy.recognitionQualifies(true, false, true)); // ADDED or DUPLICATE
        assertFalse(SupportPromptPolicy.recognitionQualifies(true, false, false)); // NO_MATCH
        assertFalse(SupportPromptPolicy.recognitionQualifies(true, true, true)); // replay
        assertFalse(SupportPromptPolicy.recognitionQualifies(false, false, true)); // failed
    }
}
