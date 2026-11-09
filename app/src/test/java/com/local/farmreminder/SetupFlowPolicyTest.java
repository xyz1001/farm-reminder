package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.local.farmreminder.SetupFlowPolicy.Step.*;

public class SetupFlowPolicyTest {
    @Test public void settingsModeReviewBackReturnsToSettings() {
        SetupFlowPolicy.Step displayed = SetupFlowPolicy.displayedStep(READY, true, null);
        assertEquals(MODE, displayed);
        assertEquals("settings", SetupFlowPolicy.pageAfterClosingSetup(displayed, "settings"));
    }

    @Test public void settingsModeReviewBackDoesNotGrantMissingSourcePermission() {
        SetupFlowPolicy.Step actual = next(true, false, false, false, false, false, false, false);
        SetupFlowPolicy.Step displayed = SetupFlowPolicy.displayedStep(actual, true, null);
        assertEquals("settings", SetupFlowPolicy.pageAfterClosingSetup(displayed, "settings"));
        assertEquals(SOURCE, SetupFlowPolicy.displayedStep(actual, false, null));
        assertFalse(SetupFlowPolicy.requiredReady(actual));
    }

    @Test public void firstRunModeBackReturnsHomeWithoutCompletingSetup() {
        SetupFlowPolicy.Step actual = next(false, false, false, false, false, false, false, false);
        SetupFlowPolicy.Step displayed = SetupFlowPolicy.displayedStep(actual, true, null);
        assertEquals(MODE, displayed);
        assertEquals("home", SetupFlowPolicy.pageAfterClosingSetup(displayed, "home"));
        assertEquals(MODE, next(false, false, false, false, false, false, false, false));
        assertEquals(SOURCE, next(true, false, false, false, false, false, false, false));
        assertFalse(SetupFlowPolicy.requiredReady(actual));
    }

    @Test public void homeModeReviewAndUnsupportedBackStillReturnHome() {
        assertEquals("home", SetupFlowPolicy.pageAfterClosingSetup(MODE, "home"));
        assertEquals("home", SetupFlowPolicy.pageAfterClosingSetup(UNSUPPORTED, "settings"));
    }

    @Test public void firstMissingPermissionStartsWithMode() {
        assertEquals(MODE, next(false, false, false, false, false, false, false, false));
    }

    @Test public void usageOnlyNeedsUsageAccess() {
        assertEquals(SOURCE, next(true, false, false, true, true, true, true, false));
        assertEquals(READY, next(true, false, true, false, true, true, true, false));
    }

    @Test public void chosenUsageModeCannotUseOldCaptureSetupToSkipMissingUsagePermission() {
        // An existing enabled/connected screenshot service is not usage authorization.
        assertEquals(SOURCE, next(true, false, false, true, true, true, true, true));
        assertEquals(SOURCE, next(true, false, false, false, false, false, false, false));
        // A legacy installation without modeChosen first confirms the mode, then needs SOURCE.
        assertEquals(MODE, next(false, false, false, true, true, true, true, true));
        assertEquals(SOURCE, next(true, false, false, true, true, true, true, true));
    }

    @Test public void returningWithoutUsageGrantNeverAdvancesOrEmitsAnAction() {
        for (int i = 0; i < 3; i++) {
            SetupFlowPolicy.Step actual = next(true, false, false, true, true, true, true, true);
            assertEquals(SOURCE, actual);
            assertEquals(SOURCE, SetupFlowPolicy.displayedStep(actual, false, SCREENSHOT));
            assertEquals(SOURCE, SetupFlowPolicy.displayedStep(actual, false, NOTIFICATIONS));
            assertEquals(MODE, SetupFlowPolicy.displayedStep(actual, true, SCREENSHOT));
            // Leaving mode review must expose the real missing permission again.
            assertEquals(SOURCE, SetupFlowPolicy.displayedStep(actual, false, null));
            assertFalse(SetupFlowPolicy.requiredReady(actual));
        }
    }

    @Test public void grantedUsageSkipsSourceAndShizukuNeverNeedsUsage() {
        assertEquals(SCREENSHOT, next(true, false, true, false, false, false, false, false));
        assertEquals(SCREENSHOT, next(true, true, false, true, false, false, false, false));
        assertEquals(READY, next(true, true, false, true, true, true, false, true));
    }

    @Test public void waitingCanReturnToScreenshotSettingsWithoutChangingRequiredState() {
        assertEquals(SCREENSHOT, SetupFlowPolicy.displayedStep(WAITING, false, SCREENSHOT));
        assertEquals(WAITING, SetupFlowPolicy.displayedStep(WAITING, false, null));
    }

    @Test public void shizukuDoesNotNeedUsageAccessAndAcceptsEitherGrantedIdentity() {
        assertEquals(READY, next(true, true, false, true, true, true, true, false));
        assertEquals(SOURCE, next(true, true, true, false, true, true, true, false));
    }

    @Test public void enabledButDisconnectedWaitsInsteadOfAskingToEnableAgain() {
        assertEquals(WAITING, next(true, false, true, false, true, false, true, false));
        assertEquals(SCREENSHOT, next(true, false, true, false, false, false, true, false));
    }

    @Test public void eachSettingsReturnIsReevaluatedFromCurrentFacts() {
        assertEquals(SOURCE, next(true, false, false, false, false, false, false, false));
        assertEquals(SCREENSHOT, next(true, false, true, false, false, false, false, false));
        assertEquals(WAITING, next(true, false, true, false, true, false, false, false));
        assertEquals(NOTIFICATIONS, next(true, false, true, false, true, true, false, false));
        assertEquals(READY, next(true, false, true, false, true, true, true, false));
    }

    @Test public void deniedOrSkippedNotificationNeverBlocksRequiredReadiness() {
        assertTrue(SetupFlowPolicy.requiredReady(NOTIFICATIONS));
        assertEquals(READY, next(true, false, true, false, true, true, false, true));
    }

    @Test public void noExactAlarmPermissionIsNeededToReachReady() {
        // Exact alarms deliberately are not an input to the required setup policy.
        assertEquals(READY, next(true, false, true, false, true, true, true, false));
    }

    @Test public void existingAuthorizedInstallSkipsModeAndCompletedPermissions() {
        assertEquals(READY, next(false, false, true, false, true, true, true, false));
        assertEquals(READY, next(false, true, false, true, true, true, true, false));
    }

    @Test public void deferredNotificationsDoNotHideRevokedRequiredPermissions() {
        assertEquals(SOURCE, next(true, false, false, false, true, true, false, true));
        assertEquals(SCREENSHOT, next(true, false, true, false, false, true, false, true));
        assertEquals(WAITING, next(true, false, true, false, true, false, false, true));
    }

    @Test public void runningServiceOrPendingStartLocksMode() {
        assertTrue(SetupFlowPolicy.canChangeMode(false, false, false));
        assertFalse(SetupFlowPolicy.canChangeMode(true, false, false));
        assertFalse(SetupFlowPolicy.canChangeMode(false, true, false));
        assertFalse(SetupFlowPolicy.canChangeMode(false, false, true));
    }

    @Test public void readinessIsOnlyNavigationAndDoesNotContainStartAction() {
        for (SetupFlowPolicy.Step step : SetupFlowPolicy.Step.values())
            assertFalse(step.name().contains("START"));
        // Rebuilding the same UI state only produces READY, never a start command.
        for (int i = 0; i < 3; i++)
            assertEquals(READY, next(true, false, true, false, true, true, true, false));
    }

    @Test public void oldAndroidCanStillVisitHomeButCannotPrepareCapture() {
        assertEquals(UNSUPPORTED, SetupFlowPolicy.next(false, true, false, true, false,
                true, true, true, true));
        assertFalse(SetupFlowPolicy.requiredReady(UNSUPPORTED));
    }

    private SetupFlowPolicy.Step next(boolean chosen, boolean shizuku, boolean usage,
            boolean shizukuGranted, boolean enabled, boolean connected, boolean notified, boolean deferred) {
        return SetupFlowPolicy.next(true, chosen, shizuku, usage, shizukuGranted,
                enabled, connected, notified, deferred);
    }
}
