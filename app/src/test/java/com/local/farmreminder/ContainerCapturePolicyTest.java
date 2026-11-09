package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContainerCapturePolicyTest {
    private ContainerCapturePolicy start() {
        ContainerCapturePolicy p = new ContainerCapturePolicy();
        assertTrue(p.observe("key", false)); p.enter("session", 7, false); return p;
    }
    @Test public void oneRequestAndPendingHandoffThenVerified() {
        ContainerCapturePolicy p = start();
        assertTrue(p.canRequest()); assertTrue(p.begin("session", 7, 1));
        assertFalse(p.canRequest()); assertFalse(p.begin("session", 7, 2));
        assertTrue(p.permitsHandoff("session", 7, 1)); assertFalse(p.permitsHandoff("session", 7, 2));
        assertTrue(p.result("session", 7, 1, true, false, true, true)); assertTrue(p.canRequest());
    }
    @Test public void failureAndUnknownKeepBudgetUntilDepartureOrNewKey() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        assertFalse(p.result("session", 7, 1, true, false, true, false));
        assertFalse(p.observe(null, false)); assertFalse(p.observe("key", false)); assertFalse(p.canRequest());
        p.observe(null, true); assertTrue(p.observe("key", false)); p.enter("new", 7, false); assertTrue(p.canRequest());
        p.begin("new", 7, 1); p.fail("new", 7, 1); assertFalse(p.canRequest());
        assertTrue(p.observe("other", false)); p.enter("other", 7, false); assertTrue(p.canRequest());
        p.clear(); assertTrue(p.observe("other", false)); p.enter("restart", 8, false); assertTrue(p.canRequest());
    }
    @Test public void staleReplayAndUntrustedResultsCannotUnlock() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        assertFalse(p.result("old", 7, 1, true, false, true, true));
        assertFalse(p.result("session", 6, 1, true, false, true, true));
        assertFalse(p.result("session", 7, 2, true, false, true, true));
        assertFalse(p.canRequest());
        assertFalse(p.result("session", 7, 1, true, true, true, true)); assertFalse(p.canRequest());
        p.enter("next", 7, false); p.begin("next", 7, 1);
        assertFalse(p.result("next", 7, 1, true, false, false, true)); assertFalse(p.canRequest());
        p.enter("third", 7, false); p.begin("third", 7, 1);
        p.fail("old", 7, 1); assertEquals(ContainerCapturePolicy.State.PENDING, p.state());
        p.fail("third", 7, 1); assertFalse(p.canRequest());
    }
    @Test public void shizukuDoesNotRequireIdentity() {
        ContainerCapturePolicy p = new ContainerCapturePolicy(); p.enter("s", 1, true);
        assertTrue(p.begin("s", 1, 1)); assertTrue(p.canRequest());
        assertTrue(p.permitsHandoff("s", 1, 1)); assertTrue(p.begin("s", 1, 2));
    }
    @Test public void rateLimitRetryProducesOnlyOneEffectiveProbe() {
        ContainerCapturePolicy p = start();
        assertTrue(p.begin("session", 7, 1));
        assertTrue(p.retryRateLimit("session", 7, 1, 100));
        assertEquals(2100, p.retryNotBefore());
        assertFalse(p.result("session", 7, 1, true, false, true, true));
        assertTrue(p.begin("session", 7, 2));
        assertFalse(p.begin("session", 7, 3));
        assertTrue(p.result("session", 7, 2, true, false, true, true));
        assertFalse(p.result("session", 7, 2, true, false, true, true));
    }
    @Test public void staleRateLimitCannotUnlockNewStayOrStoppedRun() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        p.observe("new", false); p.enter("new", 7, false); p.begin("new", 7, 1);
        assertFalse(p.retryRateLimit("session", 7, 1, 0));
        assertFalse(p.retryRateLimit("new", 6, 1, 0));
        assertEquals(ContainerCapturePolicy.State.PENDING, p.state());
        p.clear(); assertFalse(p.retryRateLimit("new", 7, 1, 0));
        p.enter("restart", 8, false); p.begin("restart", 8, 1);
        assertFalse(p.retryRateLimit("new", 7, 1, 0));
        assertEquals(ContainerCapturePolicy.State.PENDING, p.state());
    }
    @Test public void retriesAreBoundedAndClockRollbackDoesNotShortenBackoff() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        assertTrue(p.retryRateLimit("session", 7, 1, 1000));
        assertEquals(3000, p.retryNotBefore());
        p.begin("session", 7, 2);
        assertTrue(p.retryRateLimit("session", 7, 2, 500));
        assertEquals(5000, p.retryNotBefore());
        p.begin("session", 7, 3);
        assertFalse(p.retryRateLimit("session", 7, 3, 5000));
        p.fail("session", 7, 3);
        assertEquals(ContainerCapturePolicy.State.FAILED, p.state());
        assertFalse(p.canRequest());
        p.observe(null, true); p.observe("key", false); p.enter("again", 7, false);
        assertTrue(p.begin("again", 7, 1));
        assertTrue(p.retryRateLimit("again", 7, 1, 6000));
    }
    @Test public void unknownForegroundAndRealFailuresStayFailed() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        p.fail("session", 7, 1); // non-rate-limit or untrusted callback
        assertFalse(p.observe(null, false));
        assertFalse(p.retryRateLimit("session", 7, 1, 100));
        assertFalse(p.canRequest());
        p.enter("next", 7, false); p.begin("next", 7, 1);
        assertFalse(p.result("next", 7, 1, true, false, true, false));
        assertFalse(p.retryRateLimit("next", 7, 1, 100));
        assertEquals(ContainerCapturePolicy.State.FAILED, p.state());
    }
    @Test public void nativeIssueIsVisibleOnlyForMatchingFailedProbeAndClears() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        p.screenshotFailure("session", 7, 1, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("session", 7));
        p.fail("session", 7, 1);
        assertEquals(ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED, p.captureIssue("session", 7));
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("session", 8));
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("old", 7));
        p.observe("new", false); p.enter("new", 7, false); p.begin("new", 7, 1);
        p.screenshotFailure("session", 7, 1, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
        p.screenshotFailure("new", 6, 1, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
        p.screenshotFailure("new", 7, 2, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
        p.fail("new", 7, 1);
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("new", 7));
        p.clear(); p.enter("restart", 8, false); p.begin("restart", 8, 1);
        p.screenshotFailure("new", 7, 1, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_FAILED);
        assertTrue(p.result("restart", 8, 1, true, false, true, true));
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("restart", 8));
    }
    @Test public void rateLimitOnlyProjectsAfterRetryBudgetExhausted() {
        ContainerCapturePolicy p = start();
        for (int i = 1; i <= 2; i++) {
            assertTrue(p.begin("session", 7, i));
            assertTrue(p.retryRateLimit("session", 7, i, i * 2000));
            assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("session", 7));
        }
        assertTrue(p.begin("session", 7, 3));
        assertFalse(p.retryRateLimit("session", 7, 3, 6000));
        p.screenshotFailure("session", 7, 3, ContainerCapturePolicy.CaptureIssue.SCREENSHOT_RATE_LIMIT);
        p.fail("session", 7, 3);
        assertEquals(ContainerCapturePolicy.CaptureIssue.SCREENSHOT_RATE_LIMIT, p.captureIssue("session", 7));
        p.clear(); assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("session", 7));
    }
    @Test public void verificationRejectionAndStaleFailureAreNotNativeIssues() {
        ContainerCapturePolicy p = start(); p.begin("session", 7, 1);
        assertFalse(p.result("session", 7, 1, true, false, true, false));
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("session", 7));
        p.enter("next", 7, false); p.begin("next", 7, 1);
        p.fail("next", 7, 1);
        assertEquals(ContainerCapturePolicy.CaptureIssue.NONE, p.captureIssue("next", 7));
    }
}
