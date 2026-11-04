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

    @Test public void settingsCanOpenBlockedLegacyWithoutBypassingIt() {
        for (boolean headsUpExists : new boolean[]{false, true}) {
            ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(0, false, false, headsUpExists);
            assertEquals(ReminderChannelPolicy.LEGACY_ID, ReminderChannelPolicy.settingsChannelId(0, plan));
            assertNull(plan.channelId);
            assertFalse(plan.createChannel);
        }
    }

    @Test public void settingsReuseExistingHeadsUpEvenIfItIsBlocked() {
        // Importance of an existing new channel must not cause migration to a different ID.
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(null, false, false, true);
        assertEquals(ReminderChannelPolicy.HEADS_UP_ID, ReminderChannelPolicy.settingsChannelId(null, plan));
        assertFalse(plan.createChannel);
        assertEquals(NotificationSettingsPolicy.State.CHANNEL_BLOCKED,
                NotificationSettingsPolicy.state(true, 0, false));
    }

    @Test public void missingChannelSettingsUseSameStableCreationPlanAsDelivery() {
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(null, false, false, false);
        assertEquals(ReminderChannelPolicy.HEADS_UP_ID, ReminderChannelPolicy.settingsChannelId(null, plan));
        assertTrue(plan.createChannel);
        assertEquals(NotificationSettingsPolicy.State.CHANNEL_MISSING,
                NotificationSettingsPolicy.state(true, null, plan.vibrate));
    }

    @Test public void settingsHonorSelectedLegacyImportanceChoice() {
        ReminderChannelPolicy.Plan plan = ReminderChannelPolicy.select(2, false, false, false);
        assertEquals(ReminderChannelPolicy.LEGACY_ID, ReminderChannelPolicy.settingsChannelId(2, plan));
        assertFalse(plan.createChannel);
    }
}
