package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;
import static com.local.farmreminder.NotificationSettingsPolicy.State.*;
import static com.local.farmreminder.NotificationSettingsPolicy.Target.*;

public class NotificationSettingsPolicyTest {
    @Test public void appBlockTakesPrecedenceOverMissingBlockedAndVibratingChannel() {
        assertEquals(APP_BLOCKED, NotificationSettingsPolicy.state(false, null, false));
        assertEquals(APP_BLOCKED, NotificationSettingsPolicy.state(false, 0, true));
        assertEquals(APP_BLOCKED, NotificationSettingsPolicy.state(false, 4, true));
    }

    @Test public void channelAbsenceIsNotReportedAsVibrationOff() {
        assertEquals(CHANNEL_MISSING, NotificationSettingsPolicy.state(true, null, false));
    }

    @Test public void channelBlockTakesPrecedenceOverVibrationConfiguration() {
        assertEquals(CHANNEL_BLOCKED, NotificationSettingsPolicy.state(true, 0, false));
        assertEquals(CHANNEL_BLOCKED, NotificationSettingsPolicy.state(true, 0, true));
    }

    @Test public void enabledChannelReportsConfigurationNotDeliveryGuarantee() {
        assertEquals(VIBRATION_OFF, NotificationSettingsPolicy.state(true, 4, false));
        assertEquals(VIBRATION_ON, NotificationSettingsPolicy.state(true, 4, true));
        assertEquals(VIBRATION_ON, NotificationSettingsPolicy.state(true, 1, true));
    }

    @Test public void actualChannelIsFirstTargetAndSuccessStopsFallbacks() {
        List<NotificationSettingsPolicy.Target> calls = new ArrayList<>();
        assertEquals(CHANNEL, NotificationSettingsPolicy.open(true, target -> { calls.add(target); return true; }));
        assertEquals(Arrays.asList(CHANNEL), calls);
    }

    @Test public void failedChannelFallsBackToAppNotifications() {
        List<NotificationSettingsPolicy.Target> calls = new ArrayList<>();
        assertEquals(APP, NotificationSettingsPolicy.open(true, target -> { calls.add(target); return target == APP; }));
        assertEquals(Arrays.asList(CHANNEL, APP), calls);
    }

    @Test public void failedAppFallsBackToOwnAppDetails() {
        List<NotificationSettingsPolicy.Target> calls = new ArrayList<>();
        assertEquals(DETAILS, NotificationSettingsPolicy.open(true, target -> { calls.add(target); return target == DETAILS; }));
        assertEquals(Arrays.asList(CHANNEL, APP, DETAILS), calls);
    }

    @Test public void unavailableChannelSkipsChannelPageAndFallsBack() {
        List<NotificationSettingsPolicy.Target> calls = new ArrayList<>();
        assertEquals(DETAILS, NotificationSettingsPolicy.open(false, target -> { calls.add(target); return target == DETAILS; }));
        assertEquals(Arrays.asList(APP, DETAILS), calls);
    }

    @Test public void allPagesUnavailableReportFailure() {
        List<NotificationSettingsPolicy.Target> calls = new ArrayList<>();
        assertNull(NotificationSettingsPolicy.open(true, target -> { calls.add(target); return false; }));
        assertEquals(Arrays.asList(CHANNEL, APP, DETAILS), calls);
    }

    @Test(expected = IllegalStateException.class) public void policyDoesNotSwallowUnrelatedAdapterFailures() {
        NotificationSettingsPolicy.open(true, target -> { throw new IllegalStateException("not a supported settings failure"); });
    }
}
