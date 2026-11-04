package com.local.farmreminder;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class MonitorRuntimePolicyTest {
    private static MonitorIntentState start() { return MonitorIntentState.empty().start(MonitorIntentState.USAGE, Collections.emptyList(), 1); }
    @Test public void queuedStartCannotDefeatStopOrLaterStart() {
        MonitorIntentState first = start(), stopped = first.stop(); Object owner = new Object();
        MonitorRuntimePolicy policy = new MonitorRuntimePolicy(); policy.attach(owner, first.revision);
        assertFalse(policy.current(owner, first.revision, stopped));
        MonitorIntentState newer = stopped.start(MonitorIntentState.USAGE, Collections.emptyList(), 2);
        assertFalse(policy.current(owner, first.revision, newer));
        assertFalse(policy.attemptOnReady(owner, first.revision, newer, true, false));
    }
    @Test public void stickyMustReadWantedAndReadinessNeverLaunchesService() {
        MonitorIntentState wanted = start(), stopped = wanted.stop();
        assertTrue(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.SYSTEM_STICKY, wanted, false));
        assertFalse(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.SYSTEM_STICKY, stopped, false));
        assertFalse(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.READINESS, wanted, false));
        assertFalse(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.BOOT, wanted, true));
    }
    @Test public void oneAttemptOnReadyAndFatalBlocksAutomaticRetry() {
        MonitorIntentState state = start(); Object owner = new Object(); MonitorRuntimePolicy p = new MonitorRuntimePolicy();
        p.attach(owner, state.revision);
        assertFalse(p.attemptOnReady(owner, state.revision, state, false, false));
        assertTrue(p.attemptOnReady(owner, state.revision, state, true, false));
        assertFalse(p.attemptOnReady(owner, state.revision, state, true, false));
        MonitorIntentState suspended = state.suspendFatal(state.revision);
        assertFalse(p.attemptOnReady(owner, state.revision, suspended, true, false));
        assertFalse(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.BOOT, suspended, false));
        assertFalse(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.VISIBLE_RESUME, suspended, false));
    }
    @Test public void staleOwnerCannotUseReadinessAfterReplacement() {
        MonitorIntentState state = start(); Object old = new Object(), current = new Object();
        MonitorRuntimePolicy p = new MonitorRuntimePolicy(); p.attach(old, state.revision); p.attach(current, state.revision);
        assertFalse(p.current(old, state.revision, state));
        assertFalse(p.attemptOnReady(old, state.revision, state, true, false));
        assertTrue(p.attemptOnReady(current, state.revision, state, true, false));
        p.detach(old); assertTrue(p.current(current, state.revision, state));
    }
    @Test public void sourceModeChangeNeverFallsBack() {
        MonitorIntentState state = start();
        assertTrue(MonitorRuntimePolicy.modeMatches(state, false));
        assertFalse(MonitorRuntimePolicy.modeMatches(state, true));
        MonitorIntentState shizuku = state.start(MonitorIntentState.SHIZUKU, Collections.emptyList(), 2);
        assertTrue(MonitorRuntimePolicy.modeMatches(shizuku, true));
        assertFalse(MonitorRuntimePolicy.modeMatches(shizuku, false));
    }
    @Test public void interruptRequiresManualRetryWithoutDisconnectOrFenceReset() {
        MonitorIntentState state = start(); Object owner = new Object();
        MonitorRuntimePolicy policy = new MonitorRuntimePolicy(); policy.attach(owner, state.revision);
        boolean connected = true;
        assertTrue(policy.attemptOnReady(owner, state.revision, state, connected, false));
        FramePipeline<Object> capture = new FramePipeline<>(ignored -> { });
        long request = capture.beginCapture(1);
        assertTrue(MonitorRuntimePolicy.interruptRequiresSuspension(true));
        assertTrue(policy.current(owner, state.revision, state));
        MonitorIntentState suspended = state.suspendFatal(state.revision);
        assertTrue(connected); // Interrupt is not a binding transition.
        assertTrue(suspended.wanted);
        assertEquals(MonitorIntentState.Suspension.MANUAL_RETRY_REQUIRED, suspended.suspension);
        assertFalse(policy.attemptOnReady(owner, state.revision, suspended, connected, false));
        assertFalse(MonitorRuntimePolicy.mayLaunch(MonitorRuntimePolicy.Trigger.VISIBLE_RESUME, suspended, false));
        MonitorIntentState retry = suspended.start(MonitorIntentState.USAGE, Collections.emptyList(), 2);
        assertTrue(retry.mayRun(retry.revision));
        assertFalse(MonitorRuntimePolicy.captureAvailable(capture.captureBusy()));
        assertTrue(capture.endCapture(request));
        assertTrue(MonitorRuntimePolicy.captureAvailable(capture.captureBusy()));
        assertFalse(MonitorRuntimePolicy.interruptRequiresSuspension(false));
        assertFalse(policy.current(owner, state.revision, retry));
    }
    @Test public void actualDisconnectCanReconnectWithoutNewIntent() {
        MonitorIntentState state = start(); Object owner = new Object();
        MonitorRuntimePolicy policy = new MonitorRuntimePolicy(); policy.attach(owner, state.revision);
        assertTrue(policy.attemptOnReady(owner, state.revision, state, true, false));
        assertFalse(policy.attemptOnReady(owner, state.revision, state, false, false));
        assertTrue(policy.attemptOnReady(owner, state.revision, state, true, false));
    }
}
