package com.local.farmreminder;

import static org.junit.Assert.*;
import org.junit.Test;

public class EntryFeedbackDiagnosticsTest {
    @Test public void requestedIsNotShownAndOldCallbacksDoNotPolluteNewStayOrRun() {
        EntryFeedbackDiagnostics d = new EntryFeedbackDiagnostics(); d.enable(true);
        d.start("USAGE", 1); d.enter("READY", 2);
        long oldRun = d.run(), oldStay = d.stay(), old = d.request(true, 3);
        assertTrue(d.snapshot(3).contains("toastRequested=true toastShown=false toastHidden=false"));
        d.enter("READY", 4); long current = d.request(true, 5);
        d.callback(old, oldRun, oldStay, true, 6); d.callback(old, oldRun, oldStay, false, 7);
        assertTrue(d.snapshot(7).contains("toastRequested=true toastShown=false toastHidden=false"));
        d.callback(current, d.run(), d.stay(), true, 8);
        assertTrue(d.snapshot(8).contains("toastRequested=true toastShown=true toastHidden=false"));
        d.start("USAGE", 9); d.callback(current, oldRun, oldStay + 1, false, 10);
        assertTrue(d.snapshot(10).contains("toastRequested=false toastShown=false toastHidden=false"));
    }
    @Test public void boundedAgesAndExceptionRedaction() {
        EntryFeedbackDiagnostics d = new EntryFeedbackDiagnostics(); d.enable(true);
        assertTrue(d.snapshot(0).contains("stayAge=unknown"));
        d.start("USAGE", 0); d.enter("READY", 0);
        long id = d.request(true, 0); d.exception(id, new IllegalStateException("secret text"), 0);
        String snapshot = d.snapshot(0);
        assertTrue(snapshot.contains("stayAge=0")); assertTrue(snapshot.contains("exceptionClass=IllegalStateException"));
        assertFalse(snapshot.contains("secret text"));
        for (int i = 0; i < 20; i++) d.reset(EntryFeedbackDiagnostics.Event.CHANGE_RESET, i);
        snapshot = d.snapshot(20); assertEquals(12, snapshot.split("CHANGE_RESET@", -1).length - 1);
        assertTrue(d.snapshot(-1).contains("stayAge=unknown"));
    }
    @Test public void verificationAndToastAreIndependent() {
        EntryFeedbackDiagnostics d = new EntryFeedbackDiagnostics(); d.enable(true);
        d.start("USAGE", 0); d.enter("READY", 1); d.probe("PENDING", 2);
        d.result(true, EntryFeedbackDiagnostics.Reason.NONE, "HOME", "VERIFIED", 3);
        long request = d.request(true, 4); d.callback(request, d.run(), d.stay(), true, 5);
        String snapshot = d.snapshot(5);
        assertTrue(snapshot.contains("attempts=1 pass=1 reject=0 fail=0"));
        assertTrue(snapshot.contains("classifier=HOME announcementBudget=CONSUMED toastRequested=true toastShown=true"));
        d.enter("READY", 6); d.probe("PENDING", 7);
        d.result(false, EntryFeedbackDiagnostics.Reason.NO_IDENTITY, "NO_MATCH", "FAILED", 8);
        snapshot = d.snapshot(8);
        assertTrue(snapshot.contains("attempts=2 pass=1 reject=1 fail=0"));
        assertTrue(snapshot.contains("lastReason=NO_IDENTITY classifier=NO_MATCH announcementBudget=CONSUMED toastRequested=false"));
    }
}
