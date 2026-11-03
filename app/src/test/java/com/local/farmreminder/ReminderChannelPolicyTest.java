package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReminderChannelPolicyTest {
    @Test public void freshInstallCreatesStableHeadsUpChannelWithShortVibration() {
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(null, false, false, false);
        assertEquals("maturity_heads_up_v2", plan.channelId);
        assertTrue(plan.createChannel);
        assertTrue(plan.vibrate);
    }

    @Test public void defaultLegacyChannelMigratesWithoutEnablingDisabledVibration() {
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(3, false, false, false);
        assertEquals(ReminderChannelPolicy.HEADS_UP_ID, plan.channelId);
        assertTrue(plan.createChannel);
        assertFalse(plan.vibrate);
        assertTrue(ReminderChannelPolicy.select(3, false, true, false).vibrate);
    }

    @Test public void blockedLegacyChannelNeverBypassedEvenWhenNewChannelExists() {
        for (boolean exists : new boolean[]{false, true}) {
            ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(0, false, true, exists);
            assertNull(plan.channelId);
            assertFalse(plan.createChannel);
        }
    }

    @Test public void userImportanceChoiceAndOlderAndroidLowImportanceStayOnLegacy() {
        assertEquals(ReminderChannelPolicy.LEGACY_ID,
                ReminderChannelPolicy.select(3, true, true, false).channelId);
        for (int importance : new int[]{1, 2}) {
            ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(importance, false, true, false);
            assertEquals(ReminderChannelPolicy.LEGACY_ID, plan.channelId);
            assertFalse(plan.createChannel);
        }
    }

    @Test public void existingHeadsUpChannelIsReusedWithoutOverwritingUserSettings() {
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(3, false, true, true);
        assertEquals(ReminderChannelPolicy.HEADS_UP_ID, plan.channelId);
        assertFalse(plan.createChannel);
        assertFalse(ReminderChannelPolicy.select(null, false, true, true).createChannel);
    }
}
